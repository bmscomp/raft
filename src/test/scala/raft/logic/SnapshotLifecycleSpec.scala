package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.Effect
import raft.effect.Effect.*

/** Snapshot lifecycle tests — creation, chunked transfer, and restoration.
  *
  * Tests the data structures and message construction for log compaction
  * snapshots without IO, verifying:
  *   - Snapshot creation from state
  *   - PendingSnapshot chunk accumulation
  *   - InstallSnapshotRequest/Response message construction
  *   - Cluster config preservation across snapshots
  */
class SnapshotLifecycleSpec extends AnyFlatSpec with Matchers:

  val n1 = NodeId("node-1")
  val n2 = NodeId("node-2")
  val config = RaftConfig.default(n1)

  "Snapshot" should "capture state at a specific index and term" in {
    val snapshot = Snapshot(
      lastIncludedIndex = 100,
      lastIncludedTerm = 5,
      data = "state-machine-data".getBytes
    )

    snapshot.lastIncludedIndex shouldBe 100
    snapshot.lastIncludedTerm shouldBe 5
    new String(snapshot.data) shouldBe "state-machine-data"
    snapshot.config shouldBe None
  }

  it should "preserve cluster configuration" in {
    val clusterConfig = ClusterConfig(Set(n1, n2, NodeId("node-3")))
    val snapshot = Snapshot(
      lastIncludedIndex = 200,
      lastIncludedTerm = 8,
      data = "snapshot-data".getBytes,
      config = Some(clusterConfig)
    )

    snapshot.config shouldBe defined
    snapshot.config.get.voters.size shouldBe 3
    snapshot.config.get.voters should contain(n1)
  }

  "PendingSnapshot" should "accumulate chunks in order" in {
    val pending = PendingSnapshot(
      lastIncludedIndex = 100,
      lastIncludedTerm = 5,
      offset = 0,
      chunks = Vector.empty
    )

    val chunk1 = "chunk-1-".getBytes
    val chunk2 = "chunk-2-".getBytes
    val chunk3 = "chunk-3".getBytes

    val after1 = pending.appendChunk(chunk1)
    after1.offset shouldBe chunk1.length
    after1.chunks.size shouldBe 1

    val after2 = after1.appendChunk(chunk2)
    after2.offset shouldBe (chunk1.length + chunk2.length)
    after2.chunks.size shouldBe 2

    val after3 = after2.appendChunk(chunk3)
    after3.offset shouldBe (chunk1.length + chunk2.length + chunk3.length)
    after3.chunks.size shouldBe 3
  }

  it should "reconstruct complete snapshot from chunks" in {
    val data1 = "Hello, ".getBytes
    val data2 = "RAFT ".getBytes
    val data3 = "World!".getBytes

    val pending = PendingSnapshot(
      lastIncludedIndex = 50,
      lastIncludedTerm = 3,
      offset = 0,
      chunks = Vector.empty
    )

    val complete = pending
      .appendChunk(data1)
      .appendChunk(data2)
      .appendChunk(data3)
      .toSnapshot

    complete.lastIncludedIndex shouldBe 50
    complete.lastIncludedTerm shouldBe 3
    new String(complete.data) shouldBe "Hello, RAFT World!"
  }

  it should "handle single-chunk snapshots" in {
    val allData = "single-chunk-snapshot-data".getBytes

    val snapshot = PendingSnapshot(100, 5, 0, Vector.empty)
      .appendChunk(allData)
      .toSnapshot

    new String(snapshot.data) shouldBe "single-chunk-snapshot-data"
  }

  it should "handle empty snapshot data" in {
    val snapshot = PendingSnapshot(0, 0, 0, Vector.empty).toSnapshot

    snapshot.data shouldBe empty
    snapshot.lastIncludedIndex shouldBe 0
    snapshot.lastIncludedTerm shouldBe 0
  }

  "InstallSnapshotRequest" should "carry correct fields for first chunk" in {
    val snapshotData = "snapshot-content".getBytes
    val req: InstallSnapshotRequest = InstallSnapshotRequest(
      term = 10,
      leaderId = n1,
      lastIncludedIndex = 100,
      lastIncludedTerm = 8,
      offset = 0,
      data = snapshotData,
      done = false
    )

    req.term shouldBe 10
    req.leaderId shouldBe n1
    req.lastIncludedIndex shouldBe 100
    req.lastIncludedTerm shouldBe 8
    req.offset shouldBe 0
    req.done shouldBe false
    new String(req.data) shouldBe "snapshot-content"
  }

  it should "mark final chunk with done=true" in {
    val finalChunk: InstallSnapshotRequest = InstallSnapshotRequest(
      term = 10,
      leaderId = n1,
      lastIncludedIndex = 100,
      lastIncludedTerm = 8,
      offset = 1024,
      data = "final-piece".getBytes,
      done = true
    )

    finalChunk.done shouldBe true
    finalChunk.offset shouldBe 1024
  }

  "InstallSnapshotResponse" should "carry term and success flag" in {
    val successResp: InstallSnapshotResponse =
      InstallSnapshotResponse(term = 10, success = true)
    successResp.term shouldBe 10
    successResp.success shouldBe true

    val failResp: InstallSnapshotResponse =
      InstallSnapshotResponse(term = 12, success = false)
    failResp.term shouldBe 12
    failResp.success shouldBe false
  }

  "Multi-chunk transfer simulation" should "reconstruct data from chunked InstallSnapshotRequests" in {
    val originalData = "ABCDEFGHIJKLMNOPQRSTUVWXYZ-0123456789"
    val chunkSize = 10

    val chunks = originalData.getBytes.grouped(chunkSize).toList

    var pending = PendingSnapshot(200, 10, 0, Vector.empty)
    var offset = 0L

    for (chunk, idx) <- chunks.zipWithIndex do
      val isDone = idx == chunks.size - 1
      val req: InstallSnapshotRequest = InstallSnapshotRequest(
        term = 10,
        leaderId = n1,
        lastIncludedIndex = 200,
        lastIncludedTerm = 10,
        offset = offset,
        data = chunk,
        done = isDone
      )

      req.offset shouldBe offset
      pending = pending.appendChunk(req.data)
      offset += chunk.length

      if isDone then
        val snapshot = pending.toSnapshot
        new String(snapshot.data) shouldBe originalData
        snapshot.lastIncludedIndex shouldBe 200

    pending.chunks.size shouldBe chunks.size
  }

  "Snapshot with follower term check" should "leave follower state unchanged for unhandled InstallSnapshot" in {
    val follower = Follower(term = 15, votedFor = None, leaderId = None)

    val staleReq = InstallSnapshotRequest(
      term = 10,
      leaderId = n2,
      lastIncludedIndex = 100,
      lastIncludedTerm = 8,
      offset = 0,
      data = "data".getBytes,
      done = true
    )

    // onMessage does not dispatch InstallSnapshotRequest — the runtime
    // handles it separately. The state should remain unchanged.
    val trans = RaftLogic.onMessage(follower, staleReq, config, 50, 15, 3)

    trans.state.term shouldBe 15
    trans.state shouldBe a[Follower]
    trans.effects shouldBe empty
  }
