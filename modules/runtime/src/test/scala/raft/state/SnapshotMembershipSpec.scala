package raft.state

import fs2.Stream
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests for snapshot configuration metadata and chunk assembly in
  * `raft-runtime`.
  */
class SnapshotMembershipSpec extends AnyFlatSpec with Matchers:

  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")
  val node4 = NodeId("node-4")

  type Pure[A] = fs2.Pure[A]

  "Snapshot" should "include cluster configuration" in {
    val config = ClusterConfig(Set(node1, node2, node3))
    val snapshot = Snapshot[Pure](
      lastIncludedIndex = 100,
      lastIncludedTerm = 5,
      data = Stream.emits("state machine data".getBytes),
      config = Some(config)
    )

    snapshot.config shouldBe defined
    snapshot.config.get.voters shouldBe Set(node1, node2, node3)
  }

  "New node" should "receive snapshot with current config" in {
    val leaderConfig = ClusterConfig(Set(node1, node2, node3, node4))

    val snapshot = Snapshot[Pure](
      lastIncludedIndex = 500,
      lastIncludedTerm = 10,
      data = Stream.emits("compacted state".getBytes),
      config = Some(leaderConfig)
    )

    snapshot.config.get.allNodes should contain(node4)
    snapshot.config.get.voters.size shouldBe 4
  }

  "Pending snapshot" should "accumulate chunks and produce final snapshot" in {
    val pending = PendingSnapshot(
      lastIncludedIndex = 100,
      lastIncludedTerm = 5,
      offset = 0,
      chunks = Vector.empty
    )

    val chunk1 = "first part".getBytes
    val chunk2 = " second part".getBytes

    val withChunks = pending
      .appendChunk(chunk1)
      .appendChunk(chunk2)

    withChunks.offset shouldBe (chunk1.length + chunk2.length)

    val finalSnapshot = withChunks.toSnapshot[Pure]
    val dataBytes = finalSnapshot.data.compile.toVector.toArray
    new String(dataBytes) shouldBe "first part second part"
    finalSnapshot.lastIncludedIndex shouldBe 100
    finalSnapshot.lastIncludedTerm shouldBe 5
  }
