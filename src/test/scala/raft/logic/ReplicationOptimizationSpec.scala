package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

import cats.effect.IO
import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.Effect
import raft.effect.Effect.*

/** Tests for Parallel Append, Batching, and Pipelining features.
  */
class ReplicationOptimizationSpec extends AnyFlatSpec with Matchers:

  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")

  // === PARALLEL REPLICATION EFFECT ===

  "ParallelReplicate effect" should "target multiple followers" in {
    val appendReq = AppendEntriesRequest(
      term = 5,
      leaderId = node1,
      prevLogIndex = 10,
      prevLogTerm = 5,
      entries = Seq.empty,
      leaderCommit = 8
    )

    val effect: Effect[IO] = ParallelReplicate(Set(node2, node3), appendReq)

    effect match
      case ParallelReplicate(targets, msg) =>
        targets shouldBe Set(node2, node3)
        msg shouldBe appendReq
      case _ => fail("Expected ParallelReplicate")
  }

  it should "be more efficient than individual SendMessage effects" in {
    val followers = (1 to 100).map(i => NodeId(s"node-$i")).toSet
    val msg = AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0)

    val parallelEffect: Effect[IO] = ParallelReplicate(followers, msg)
    parallelEffect match
      case ParallelReplicate(targets, _) => targets.size shouldBe 100
      case _                             => fail("Expected ParallelReplicate")

    val individualEffects = followers.map(f => SendMessage(f, msg))
    individualEffects.size shouldBe 100
  }

  // === BATCH CONFIG ===

  "BatchConfig" should "have sensible defaults" in {
    val config = BatchConfig()

    config.enabled shouldBe false
    config.maxSize shouldBe 100
    config.maxWait shouldBe 10.millis
  }

  it should "be configurable" in {
    val config = BatchConfig(
      enabled = true,
      maxSize = 50,
      maxWait = 5.millis
    )

    config.enabled shouldBe true
    config.maxSize shouldBe 50
    config.maxWait shouldBe 5.millis
  }

  // === BATCH EFFECTS ===

  "BatchAppend effect" should "hold multiple entries" in {
    val entries = Seq(
      Log.command(1, 1, "cmd1".getBytes),
      Log.command(2, 1, "cmd2".getBytes),
      Log.command(3, 1, "cmd3".getBytes)
    )

    val effect: Effect[IO] = BatchAppend(entries)

    effect match
      case BatchAppend(e, _) =>
        e.size shouldBe 3
      case _ => fail("Expected BatchAppend")
  }

  "BatchComplete effect" should "track completion" in {
    val effect: Effect[IO] = BatchComplete("batch-123", Right(()))

    effect match
      case BatchComplete(batchId, res) =>
        batchId shouldBe "batch-123"
        res shouldBe Right(())
      case _ => fail("Expected BatchComplete")
  }

  // === PIPELINE CONFIG ===

  "PipelineConfig" should "have sensible defaults" in {
    val config = PipelineConfig()

    config.enabled shouldBe false
    config.maxInflight shouldBe 10
  }

  it should "be configurable" in {
    val config = PipelineConfig(enabled = true, maxInflight = 5)

    config.enabled shouldBe true
    config.maxInflight shouldBe 5
  }

  // === PIPELINE EFFECTS ===

  "PipelinedSend effect" should "include pipeline ID" in {
    val msg = AppendEntriesRequest(5, node1, 10, 5, Seq.empty, 8)
    val effect: Effect[IO] = PipelinedSend(node2, msg, "pipe-123")

    effect match
      case PipelinedSend(to, m, pid) =>
        to shouldBe node2
        m shouldBe msg
        pid shouldBe "pipe-123"
      case _ => fail("Expected PipelinedSend")
  }

  "TrackInflight effect" should "track pending requests" in {
    val effect: Effect[IO] = TrackInflight(node2, "msg-42", () => ())

    effect match
      case TrackInflight(followerId, msgId, _) =>
        followerId shouldBe node2
        msgId shouldBe "msg-42"
      case _ => fail("Expected TrackInflight")
  }

  // === RAFT CONFIG INTEGRATION ===

  "RaftConfig" should "include batching and pipelining config" in {
    val config = RaftConfig(
      localId = node1,
      batching = BatchConfig(enabled = true, maxSize = 50),
      pipelining = PipelineConfig(enabled = true, maxInflight = 5)
    )

    config.batching.enabled shouldBe true
    config.batching.maxSize shouldBe 50
    config.pipelining.enabled shouldBe true
    config.pipelining.maxInflight shouldBe 5
  }

  it should "have parallel replication enabled by default" in {
    val config = RaftConfig.default(node1)

    config.parallelReplicationEnabled shouldBe true
  }

  // === ORDERED SEQUENCE NUMBERS (PIPELINE ID) ===

  "Pipeline IDs" should "enable tracking response order" in {
    val effect1 = PipelinedSend(
      node2,
      AppendEntriesRequest(5, node1, 10, 5, Seq.empty, 8),
      "pipe-1"
    )
    val effect2 = PipelinedSend(
      node2,
      AppendEntriesRequest(5, node1, 15, 5, Seq.empty, 10),
      "pipe-2"
    )
    val effect3 = PipelinedSend(
      node2,
      AppendEntriesRequest(5, node1, 20, 5, Seq.empty, 15),
      "pipe-3"
    )

    (effect1, effect2, effect3) match
      case (
            PipelinedSend(_, _, p1),
            PipelinedSend(_, _, p2),
            PipelinedSend(_, _, p3)
          ) =>
        p1 shouldBe "pipe-1"
        p2 shouldBe "pipe-2"
        p3 shouldBe "pipe-3"
      case _ => fail("Expected PipelinedSend effects")
  }
