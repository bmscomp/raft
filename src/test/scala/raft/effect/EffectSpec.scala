package raft.effect

import cats.effect.IO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.Effect.*

/** Comprehensive tests for all Effect types.
  *
  * Each Effect is tested for correct construction and pattern matching.
  */
class EffectSpec extends AnyFlatSpec with Matchers:

  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")

  // === MESSAGING EFFECTS ===

  "SendMessage effect" should "hold target and message" in {
    val msg = AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0)
    val effect: Effect[IO] = SendMessage(node2, msg)

    effect match
      case SendMessage(to, m) =>
        to shouldBe node2
        m shouldBe msg
      case _ => fail("Match failed")
  }

  "Broadcast effect" should "hold message for all nodes" in {
    val msg = AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0)
    val effect: Effect[IO] = Broadcast(msg)

    effect match
      case Broadcast(m) => m shouldBe msg
      case _            => fail("Match failed")
  }

  // === PERSISTENCE EFFECTS ===

  "PersistHardState effect" should "hold term and votedFor" in {
    val effect: Effect[IO] = PersistHardState(5, Some(node1))

    effect match
      case PersistHardState(term, votedFor) =>
        term shouldBe 5
        votedFor shouldBe Some(node1)
      case _ => fail("Match failed")
  }

  it should "handle None votedFor" in {
    val effect: Effect[IO] = PersistHardState(3, None)

    effect match
      case PersistHardState(_, votedFor) => votedFor shouldBe None
      case _                             => fail("Match failed")
  }

  "AppendLogs effect" should "hold log entries" in {
    val entries = Seq(
      Log.command(1, 1, "a".getBytes),
      Log.command(2, 1, "b".getBytes)
    )
    val effect: Effect[IO] = AppendLogs(entries)

    effect match
      case AppendLogs(e) => e.size shouldBe 2
      case _             => fail("Match failed")
  }

  "TruncateLog effect" should "hold fromIndex" in {
    val effect: Effect[IO] = TruncateLog(42)

    effect match
      case TruncateLog(idx) => idx shouldBe 42
      case _                => fail("Match failed")
  }

  // === STATE MACHINE EFFECTS ===

  "ApplyToStateMachine effect" should "hold log entry" in {
    val entry = Log.command(5, 2, "data".getBytes)
    val effect: Effect[IO] = ApplyToStateMachine(entry)

    effect match
      case ApplyToStateMachine(e) => e shouldBe entry
      case _                      => fail("Match failed")
  }

  "CommitEntries effect" should "hold upToIndex" in {
    val effect: Effect[IO] = CommitEntries(LogIndex.unsafeFrom(100))

    effect match
      case CommitEntries(idx) => idx shouldBe LogIndex.unsafeFrom(100)
      case _                  => fail("Match failed")
  }

  // === TIMER EFFECTS ===

  "ResetElectionTimer effect" should "be a singleton" in {
    val effect: Effect[IO] = ResetElectionTimer

    effect shouldBe ResetElectionTimer
  }

  "ResetHeartbeatTimer effect" should "be a singleton" in {
    val effect: Effect[IO] = ResetHeartbeatTimer

    effect shouldBe ResetHeartbeatTimer
  }

  // === SNAPSHOT EFFECTS ===

  "RequestSnapshot effect" should "hold lastApplied and logSize" in {
    val effect: Effect[IO] = RequestSnapshot(1000, 5000)

    effect match
      case RequestSnapshot(lastApplied, logSize) =>
        lastApplied shouldBe 1000
        logSize shouldBe 5000
      case _ => fail("Match failed")
  }

  // === LEADER EFFECTS ===

  "BecomeLeader effect" should "be a singleton" in {
    val effect: Effect[IO] = BecomeLeader

    effect shouldBe BecomeLeader
  }

  "TransferLeadership effect" should "hold target node" in {
    val effect: Effect[IO] = TransferLeadership(node2)

    effect match
      case TransferLeadership(target) => target shouldBe node2
      case _                          => fail("Match failed")
  }

  "InitializeLeaderState effect" should "hold followers and lastLogIndex" in {
    val followers = Set(node2, node3)
    val effect: Effect[IO] = InitializeLeaderState(followers, 50)

    effect match
      case InitializeLeaderState(f, idx) =>
        f shouldBe followers
        idx shouldBe 50
      case _ => fail("Match failed")
  }

  "UpdateFollowerIndex effect" should "hold all indices" in {
    val effect: Effect[IO] = UpdateFollowerIndex(
      node2,
      matchIndex = LogIndex.unsafeFrom(99),
      nextIndex = LogIndex.unsafeFrom(100)
    )

    effect match
      case UpdateFollowerIndex(id, matchIdx, nextIdx) =>
        id shouldBe node2
        matchIdx shouldBe LogIndex.unsafeFrom(99)
        nextIdx shouldBe LogIndex.unsafeFrom(100)
      case _ => fail("Match failed")
  }

  // === PARALLEL REPLICATION EFFECTS ===

  "ParallelReplicate effect" should "hold targets and message" in {
    val targets = Set(node2, node3)
    val msg = AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0)
    val effect: Effect[IO] = ParallelReplicate(targets, msg)

    effect match
      case ParallelReplicate(t, m) =>
        t shouldBe targets
        m shouldBe msg
      case _ => fail("Match failed")
  }

  // === BATCHING EFFECTS ===

  "BatchAppend effect" should "hold entries" in {
    val entries = Seq(Log.command(1, 1, "x".getBytes))
    val effect: Effect[IO] = BatchAppend(entries)

    effect match
      case BatchAppend(e, cb) =>
        e shouldBe entries
        cb shouldBe None
      case _ => fail("Match failed")
  }

  "BatchComplete effect" should "hold batchId and result" in {
    val effect: Effect[IO] = BatchComplete("batch-42", Right(()))

    effect match
      case BatchComplete(id, res) =>
        id shouldBe "batch-42"
        res shouldBe Right(())
      case _ => fail("Match failed")
  }

  // === PIPELINING EFFECTS ===

  "PipelinedSend effect" should "hold target, message, and pipelineId" in {
    val msg = AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0)
    val effect: Effect[IO] = PipelinedSend(node2, msg, "pipe-1")

    effect match
      case PipelinedSend(to, m, pid) =>
        to shouldBe node2
        m shouldBe msg
        pid shouldBe "pipe-1"
      case _ => fail("Match failed")
  }

  "TrackInflight effect" should "hold node, messageId, and callback" in {
    val cb = () => ()
    val effect: Effect[IO] = TrackInflight(node2, "msg-123", cb)

    effect match
      case TrackInflight(id, msgId, c) =>
        id shouldBe node2
        msgId shouldBe "msg-123"
        c shouldBe cb
      case _ => fail("Match failed")
  }

  // === EFFECT EQUALITY ===

  "Effects" should "be equal when constructed with same values" in {
    val e1: Effect[IO] =
      SendMessage(node2, AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0))
    val e2: Effect[IO] =
      SendMessage(node2, AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0))

    e1 shouldBe e2
  }

  it should "not be equal when constructed with different values" in {
    val e1: Effect[IO] =
      SendMessage(node2, AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0))
    val e2: Effect[IO] =
      SendMessage(node3, AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0))

    e1 should not be e2
  }
