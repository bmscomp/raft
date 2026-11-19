package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.*
import raft.state.NodeState.*
import raft.message.RaftMessage.*
import raft.effect.Effect.*

/** Pure snapshot lifecycle tests in zero-dependency `raft-core` (§7). */
class SnapshotLifecycleSpec extends AnyFlatSpec with Matchers:

  val n1 = NodeId("node-1")
  val n2 = NodeId("node-2")
  val config = RaftConfig.default(n1)

  "SnapshotMetadata" should "capture index, term, and optional cluster config" in {
    val clusterConfig = ClusterConfig(Set(n1, n2, NodeId("node-3")))
    val meta = SnapshotMetadata(200, 8, Some(clusterConfig))

    meta.lastIncludedIndex shouldBe 200
    meta.lastIncludedTerm shouldBe 8
    meta.config shouldBe defined
    meta.config.get.voters.size shouldBe 3
  }

  "PendingSnapshot" should "accumulate chunks in order and reconstruct bytes" in {
    val data1 = "Hello, ".getBytes
    val data2 = "RAFT ".getBytes
    val data3 = "World!".getBytes

    val complete = PendingSnapshot(
      lastIncludedIndex = 50,
      lastIncludedTerm = 3,
      offset = 0,
      chunks = Vector.empty
    ).appendChunk(data1)
      .appendChunk(data2)
      .appendChunk(data3)

    complete.offset shouldBe (data1.length + data2.length + data3.length)
    complete.chunks.size shouldBe 3
    new String(complete.toBytes) shouldBe "Hello, RAFT World!"
    complete.metadata shouldBe SnapshotMetadata(50, 3, None)
  }

  it should "handle single-chunk and empty snapshots" in {
    val single = PendingSnapshot(100, 5, 0, Vector.empty)
      .appendChunk("single-chunk-snapshot-data".getBytes)
    new String(single.toBytes) shouldBe "single-chunk-snapshot-data"

    val emptySnap = PendingSnapshot(0, 0, 0, Vector.empty)
    emptySnap.toBytes shouldBe empty
  }

  "InstallSnapshotRequest handling in RaftLogic" should "reject stale term requests" in {
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

    val trans = RaftLogic.onMessage(follower, staleReq, config, 50, 15, 3)

    trans.state.term shouldBe 15
    trans.effects should contain(
      SendMessage(
        n2,
        InstallSnapshotResponse(15, success = false, matchIndex = 0L)
      )
    )
  }

  it should "acknowledge intermediate chunks and reset election timer without installing yet" in {
    val follower = Follower(term = 10, votedFor = None, leaderId = None)
    val chunkReq = InstallSnapshotRequest(
      term = 10,
      leaderId = n2,
      lastIncludedIndex = 200,
      lastIncludedTerm = 9,
      offset = 0,
      data = "part-1".getBytes,
      done = false
    )

    val trans = RaftLogic.onMessage(follower, chunkReq, config, 50, 8, 3)

    trans.state shouldBe Follower(10, None, Some(n2), commitIndex = 0L)
    trans.effects should contain(ResetElectionTimer)
    trans.effects should contain(
      SendMessage(
        n2,
        InstallSnapshotResponse(10, success = true, matchIndex = 0L)
      )
    )
    trans.effects.collect { case InstallSnapshot(_) => () } shouldBe empty
  }

  it should "emit InstallSnapshot and CommitEntries on final chunk when snapshot advances log" in {
    val follower = Follower(term = 8, votedFor = Some(n1), leaderId = None)
    val finalReq = InstallSnapshotRequest(
      term = 10,
      leaderId = n2,
      lastIncludedIndex = 200,
      lastIncludedTerm = 9,
      offset = 64,
      data = "part-2".getBytes,
      done = true
    )

    val trans = RaftLogic.onMessage(follower, finalReq, config, 50, 8, 3)

    trans.state shouldBe Follower(10, None, Some(n2), commitIndex = 200L)
    trans.effects should contain(PersistHardState(10, None))
    trans.effects should contain(
      InstallSnapshot(SnapshotMetadata(200, 9, None))
    )
    trans.effects should contain(CommitEntries(LogIndex.unsafeFrom(200)))
    trans.effects should contain(
      SendMessage(
        n2,
        InstallSnapshotResponse(10, success = true, matchIndex = 200L)
      )
    )
  }

  "InstallSnapshotResponse handling on Leader" should "advance follower matchIndex and nextIndex" in {
    val leader = Leader(
      term = 10,
      nextIndex = Map(n2 -> 1L),
      matchIndex = Map(n2 -> 0L)
    )
    val resp =
      InstallSnapshotResponse(term = 10, success = true, matchIndex = 200L)

    val trans = RaftLogic.onMessage(
      leader,
      resp,
      config,
      lastLogIndex = 250L,
      lastLogTerm = 10L,
      clusterSize = 3,
      sender = Some(n2)
    )

    val updated = trans.state.asInstanceOf[Leader]
    updated.matchIndex(n2) shouldBe 200L
    updated.nextIndex(n2) shouldBe 201L
  }
