package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

import raft.state.*
import raft.message.RaftMessage.*
import raft.effect.Effect
import raft.effect.Effect.*

/** Tests for Parallel Append, Batching, Pipelining, and `Inflights` windowing.
  */
class ReplicationOptimizationSpec extends AnyFlatSpec with Matchers:

  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")

  "ParallelReplicate effect" should "target multiple followers" in {
    val appendReq = AppendEntriesRequest(
      term = 5,
      leaderId = node1,
      prevLogIndex = 10,
      prevLogTerm = 5,
      entries = Seq.empty,
      leaderCommit = 8
    )

    val effect: Effect = ParallelReplicate(Set(node2, node3), appendReq)

    effect match
      case ParallelReplicate(targets, msg) =>
        targets shouldBe Set(node2, node3)
        msg shouldBe appendReq
      case _ => fail("Expected ParallelReplicate")
  }

  it should "represent fan-out replication in a single effect value" in {
    val followers = (1 to 100).map(i => NodeId(s"node-$i")).toSet
    val msg = AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0)

    val parallelEffect: Effect = ParallelReplicate(followers, msg)
    parallelEffect match
      case ParallelReplicate(targets, _) => targets.size shouldBe 100
      case _                             => fail("Expected ParallelReplicate")
  }

  "BatchConfig" should "have sensible defaults and be configurable" in {
    val defaults = BatchConfig()
    defaults.enabled shouldBe false
    defaults.maxSize shouldBe 100
    defaults.maxWait shouldBe 10.millis

    val custom = BatchConfig(enabled = true, maxSize = 50, maxWait = 5.millis)
    custom.enabled shouldBe true
    custom.maxSize shouldBe 50
    custom.maxWait shouldBe 5.millis
  }

  "BatchAppend and BatchComplete effects" should "preserve entries and batch IDs" in {
    val entries = Seq(
      Log.command(1, 1, "cmd1".getBytes),
      Log.command(2, 1, "cmd2".getBytes),
      Log.command(3, 1, "cmd3".getBytes)
    )

    val appendEff: Effect = BatchAppend(entries, "batch-123")
    appendEff shouldBe BatchAppend(entries, "batch-123")

    val completeEff: Effect = BatchComplete("batch-123", Right(()))
    completeEff shouldBe BatchComplete("batch-123", Right(()))
  }

  "PipelineConfig" should "have sensible defaults and bound Inflights" in {
    val defaults = PipelineConfig()
    defaults.enabled shouldBe false
    defaults.maxInflight shouldBe 10

    val inflights = Inflights(capacity = 3)
      .add(10L)
      .add(20L)
      .add(30L)
    inflights.isFull shouldBe true
    inflights.freeTo(20L).count shouldBe 1
  }

  "PipelinedSend and TrackInflight effects" should "preserve pipeline ordering" in {
    val msg = AppendEntriesRequest(5, node1, 10, 5, Seq.empty, 8)
    val sendEff: Effect = PipelinedSend(node2, msg, "pipe-123")
    val trackEff: Effect = TrackInflight(node2, "msg-42", 15L)

    sendEff shouldBe PipelinedSend(node2, msg, "pipe-123")
    trackEff shouldBe TrackInflight(node2, "msg-42", 15L)
  }

  "RaftConfig" should "include batching, pipelining, and parallel replication settings" in {
    val config = RaftConfig(
      localId = node1,
      batching = BatchConfig(enabled = true, maxSize = 50),
      pipelining = PipelineConfig(enabled = true, maxInflight = 5)
    )

    config.batching.enabled shouldBe true
    config.batching.maxSize shouldBe 50
    config.pipelining.enabled shouldBe true
    config.pipelining.maxInflight shouldBe 5
    RaftConfig.default(node1).parallelReplicationEnabled shouldBe true
  }
