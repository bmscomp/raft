package raft.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.state.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.Effect.*

/** Comprehensive tests for all pure, monomorphic [[Effect]] variants. */
class EffectSpec extends AnyFlatSpec with Matchers:

  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")

  "SendMessage effect" should "hold target and message" in {
    val msg = AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0)
    val effect: Effect = SendMessage(node2, msg)

    effect match
      case SendMessage(to, m) =>
        to shouldBe node2
        m shouldBe msg
      case _ => fail("Match failed")
  }

  "Broadcast effect" should "hold message for all nodes" in {
    val msg = AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0)
    val effect: Effect = Broadcast(msg)

    effect match
      case Broadcast(m) => m shouldBe msg
      case _            => fail("Match failed")
  }

  "PersistHardState effect" should "hold term and votedFor" in {
    val effect: Effect = PersistHardState(5, Some(node1))

    effect match
      case PersistHardState(term, votedFor) =>
        term shouldBe 5
        votedFor shouldBe Some(node1)
      case _ => fail("Match failed")
  }

  it should "handle None votedFor" in {
    val effect: Effect = PersistHardState(3, None)

    effect match
      case PersistHardState(_, votedFor) => votedFor shouldBe None
      case _                             => fail("Match failed")
  }

  "AppendLogs effect" should "hold log entries" in {
    val entries = Seq(
      Log.command(1, 1, "a".getBytes),
      Log.command(2, 1, "b".getBytes)
    )
    val effect: Effect = AppendLogs(entries)

    effect match
      case AppendLogs(e) => e.size shouldBe 2
      case _             => fail("Match failed")
  }

  "TruncateLog effect" should "hold fromIndex" in {
    val effect: Effect = TruncateLog(42)

    effect match
      case TruncateLog(idx) => idx shouldBe 42
      case _                => fail("Match failed")
  }

  "ApplyToStateMachine effect" should "hold log entry" in {
    val entry = Log.command(5, 2, "data".getBytes)
    val effect: Effect = ApplyToStateMachine(entry)

    effect match
      case ApplyToStateMachine(e) => e shouldBe entry
      case _                      => fail("Match failed")
  }

  "CommitEntries effect" should "hold upToIndex" in {
    val effect: Effect = CommitEntries(LogIndex.unsafeFrom(100))

    effect match
      case CommitEntries(idx) => idx shouldBe LogIndex.unsafeFrom(100)
      case _                  => fail("Match failed")
  }

  "ResetElectionTimer effect" should "be a singleton" in {
    val effect: Effect = ResetElectionTimer
    effect shouldBe ResetElectionTimer
  }

  "ResetHeartbeatTimer effect" should "be a singleton" in {
    val effect: Effect = ResetHeartbeatTimer
    effect shouldBe ResetHeartbeatTimer
  }

  "RequestSnapshot effect" should "hold lastApplied and logSize" in {
    val effect: Effect = RequestSnapshot(1000, 5000)

    effect match
      case RequestSnapshot(lastApplied, logSize) =>
        lastApplied shouldBe 1000
        logSize shouldBe 5000
      case _ => fail("Match failed")
  }

  "InstallSnapshot effect" should "hold pure SnapshotMetadata with structural equality" in {
    val meta = SnapshotMetadata(200, 7, Some(ClusterConfig(Set(node1, node2))))
    val e1: Effect = InstallSnapshot(meta)
    val e2: Effect = InstallSnapshot(meta)
    e1 shouldBe e2
  }

  "BecomeLeader effect" should "be a singleton" in {
    val effect: Effect = BecomeLeader
    effect shouldBe BecomeLeader
  }

  "TransferLeadership effect" should "hold target node" in {
    val effect: Effect = TransferLeadership(node2)

    effect match
      case TransferLeadership(target) => target shouldBe node2
      case _                          => fail("Match failed")
  }

  "InitializeLeaderState effect" should "hold followers and lastLogIndex" in {
    val followers = Set(node2, node3)
    val effect: Effect = InitializeLeaderState(followers, 50)

    effect match
      case InitializeLeaderState(f, idx) =>
        f shouldBe followers
        idx shouldBe 50
      case _ => fail("Match failed")
  }

  "UpdateFollowerIndex effect" should "hold all indices" in {
    val effect: Effect = UpdateFollowerIndex(
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

  "ParallelReplicate effect" should "hold targets and message" in {
    val targets = Set(node2, node3)
    val msg = AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0)
    val effect: Effect = ParallelReplicate(targets, msg)

    effect match
      case ParallelReplicate(t, m) =>
        t shouldBe targets
        m shouldBe msg
      case _ => fail("Match failed")
  }

  "BatchAppend effect" should "hold entries and pure batchId" in {
    val entries = Seq(Log.command(1, 1, "x".getBytes))
    val effect: Effect = BatchAppend(entries, "batch-7")

    effect match
      case BatchAppend(e, id) =>
        e shouldBe entries
        id shouldBe "batch-7"
      case _ => fail("Match failed")
  }

  "BatchComplete effect" should "hold batchId and result" in {
    val effect: Effect = BatchComplete("batch-42", Right(()))

    effect match
      case BatchComplete(id, res) =>
        id shouldBe "batch-42"
        res shouldBe Right(())
      case _ => fail("Match failed")
  }

  "PipelinedSend effect" should "hold target, message, and pipelineId" in {
    val msg = AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0)
    val effect: Effect = PipelinedSend(node2, msg, "pipe-1")

    effect match
      case PipelinedSend(to, m, pid) =>
        to shouldBe node2
        m shouldBe msg
        pid shouldBe "pipe-1"
      case _ => fail("Match failed")
  }

  "TrackInflight effect" should "hold node, messageId, and maxIndex with value equality" in {
    val e1: Effect = TrackInflight(node2, "msg-123", 42L)
    val e2: Effect = TrackInflight(node2, "msg-123", 42L)
    e1 shouldBe e2
  }

  "Effects" should "be equal when constructed with same values" in {
    val e1: Effect =
      SendMessage(node2, AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0))
    val e2: Effect =
      SendMessage(node2, AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0))

    e1 shouldBe e2
  }

  it should "not be equal when constructed with different values" in {
    val e1: Effect =
      SendMessage(node2, AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0))
    val e2: Effect =
      SendMessage(node3, AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0))

    e1 should not be e2
  }
