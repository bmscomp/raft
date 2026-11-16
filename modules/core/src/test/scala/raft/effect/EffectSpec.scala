package raft.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.state.*
import raft.message.RaftMessage.*
import raft.effect.Effect.*

/** Comprehensive tests for all pure, monomorphic [[Effect]] variants emitted by
  * [[raft.logic.RaftLogic]].
  */
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

  "CommitEntries effect" should "hold upToIndex" in {
    val effect: Effect = CommitEntries(LogIndex.unsafeFrom(100))

    effect match
      case CommitEntries(idx) => idx shouldBe LogIndex.unsafeFrom(100)
      case _                  => fail("Match failed")
  }

  "ResetElectionTimer and ResetHeartbeatTimer effects" should "be singletons" in {
    (ResetElectionTimer: Effect) shouldBe ResetElectionTimer
    (ResetHeartbeatTimer: Effect) shouldBe ResetHeartbeatTimer
  }

  "BecomeLeader and StopLeader effects" should "be singletons" in {
    (BecomeLeader: Effect) shouldBe BecomeLeader
    (StopLeader: Effect) shouldBe StopLeader
  }

  "ReadIndex effects" should "capture confirmation, readiness, and rejection" in {
    val confirm: Effect = ConfirmLeadership(5L, "r-1")
    val ready: Effect = ReadIndexReady("r-1", 42L)
    val rejected: Effect = ReadIndexRejected("r-2", "Not leader", Some(node1))

    confirm shouldBe ConfirmLeadership(5L, "r-1")
    ready shouldBe ReadIndexReady("r-1", 42L)
    rejected shouldBe ReadIndexRejected("r-2", "Not leader", Some(node1))
  }

  "RequestSnapshot effect" should "hold lastApplied and logSize" in {
    val effect: Effect = RequestSnapshot(1000, 5000)

    effect match
      case RequestSnapshot(lastApplied, logSize) =>
        lastApplied shouldBe 1000
        logSize shouldBe 5000
      case _ => fail("Match failed")
  }

  "InstallSnapshot effect" should "hold pure SnapshotMetadata with byte-array structural equality" in {
    val cfg = Some(ClusterConfig(Set(node1, node2)))
    val meta1 = SnapshotMetadata(200, 7, cfg, "state-bytes".getBytes)
    val meta2 = SnapshotMetadata(200, 7, cfg, "state-bytes".getBytes)
    val e1: Effect = InstallSnapshot(meta1)
    val e2: Effect = InstallSnapshot(meta2)
    e1 shouldBe e2
    e1.hashCode() shouldBe e2.hashCode()
  }

  "TransferLeadership and TimeoutNow effects" should "hold target node" in {
    val transfer: Effect = TransferLeadership(node2)
    val timeoutNow: Effect = TimeoutNow(node2)

    transfer shouldBe TransferLeadership(node2)
    timeoutNow shouldBe TimeoutNow(node2)
  }

  "ApplyClusterConfig effect" should "hold config, index, and term" in {
    val cfg = ClusterConfig(Set(node1, node2, node3))
    val eff: Effect = ApplyClusterConfig(cfg, 12L, Term.unsafeFrom(4L))
    eff shouldBe ApplyClusterConfig(cfg, 12L, Term.unsafeFrom(4L))
  }

  "Transition helpers" should "construct pure and effectful transitions cleanly" in {
    val st = NodeState.Follower(1L, None, None)
    Transition.pure(st) shouldBe Transition(st, Nil)
    Transition.withEffect(st, BecomeLeader) shouldBe Transition(
      st,
      List(BecomeLeader)
    )
    Transition.withEffects(
      st,
      BecomeLeader,
      ResetHeartbeatTimer
    ) shouldBe Transition(st, List(BecomeLeader, ResetHeartbeatTimer))
  }

  "Effects" should "be equal when constructed with same values and unequal otherwise" in {
    val e1: Effect =
      SendMessage(node2, AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0))
    val e2: Effect =
      SendMessage(node2, AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0))
    val e3: Effect =
      SendMessage(node3, AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0))

    e1 shouldBe e2
    e1 should not be e3
  }
