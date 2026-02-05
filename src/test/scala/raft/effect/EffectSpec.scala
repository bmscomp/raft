package raft.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.Effect.*

/**
 * Comprehensive tests for all Effect types.
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
    val effect = SendMessage(node2, msg)
    
    effect match
      case SendMessage(to, m) =>
        to shouldBe node2
        m shouldBe msg
  }
  
  "Broadcast effect" should "hold message for all nodes" in {
    val msg = AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0)
    val effect = Broadcast(msg)
    
    effect match
      case Broadcast(m) => m shouldBe msg
  }
  
  // === PERSISTENCE EFFECTS ===
  
  "PersistHardState effect" should "hold term and votedFor" in {
    val effect = PersistHardState(5, Some(node1))
    
    effect match
      case PersistHardState(term, votedFor) =>
        term shouldBe 5
        votedFor shouldBe Some(node1)
  }
  
  it should "handle None votedFor" in {
    val effect = PersistHardState(3, None)
    
    effect match
      case PersistHardState(_, votedFor) => votedFor shouldBe None
  }
  
  "AppendLogs effect" should "hold log entries" in {
    val entries = Seq(
      Log.command(1, 1, "a".getBytes),
      Log.command(2, 1, "b".getBytes)
    )
    val effect = AppendLogs(entries)
    
    effect match
      case AppendLogs(e) => e.size shouldBe 2
  }
  
  "TruncateLog effect" should "hold fromIndex" in {
    val effect = TruncateLog(42)
    
    effect match
      case TruncateLog(idx) => idx shouldBe 42
  }
  
  // === STATE MACHINE EFFECTS ===
  
  "ApplyToStateMachine effect" should "hold log entry" in {
    val entry = Log.command(5, 2, "data".getBytes)
    val effect = ApplyToStateMachine(entry)
    
    effect match
      case ApplyToStateMachine(e) => e shouldBe entry
  }
  
  "CommitEntries effect" should "hold upToIndex" in {
    val effect = CommitEntries(100)
    
    effect match
      case CommitEntries(idx) => idx shouldBe 100
  }
  
  // === TIMER EFFECTS ===
  
  "ResetElectionTimer effect" should "be a singleton" in {
    val effect = ResetElectionTimer
    
    effect shouldBe ResetElectionTimer
  }
  
  "ResetHeartbeatTimer effect" should "be a singleton" in {
    val effect = ResetHeartbeatTimer
    
    effect shouldBe ResetHeartbeatTimer
  }
  
  // === SNAPSHOT EFFECTS ===
  
  "TakeSnapshot effect" should "hold index and term" in {
    val effect = TakeSnapshot(1000, 5)
    
    effect match
      case TakeSnapshot(idx, term) =>
        idx shouldBe 1000
        term shouldBe 5
  }
  
  // === LEADER EFFECTS ===
  
  "BecomeLeader effect" should "be a singleton" in {
    val effect = BecomeLeader
    
    effect shouldBe BecomeLeader
  }
  
  "TransferLeadership effect" should "hold target node" in {
    val effect = TransferLeadership(node2)
    
    effect match
      case TransferLeadership(target) => target shouldBe node2
  }
  
  "InitializeLeaderState effect" should "hold followers and lastLogIndex" in {
    val followers = Set(node2, node3)
    val effect = InitializeLeaderState(followers, 50)
    
    effect match
      case InitializeLeaderState(f, idx) =>
        f shouldBe followers
        idx shouldBe 50
  }
  
  "UpdateFollowerIndex effect" should "hold all indices" in {
    val effect = UpdateFollowerIndex(node2, matchIndex = 99, nextIndex = 100)
    
    effect match
      case UpdateFollowerIndex(id, matchIdx, nextIdx) =>
        id shouldBe node2
        matchIdx shouldBe 99
        nextIdx shouldBe 100
  }
  
  // === PARALLEL REPLICATION EFFECTS ===
  
  "ParallelReplicate effect" should "hold targets and message" in {
    val targets = Set(node2, node3)
    val msg = AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0)
    val effect = ParallelReplicate(targets, msg)
    
    effect match
      case ParallelReplicate(t, m) =>
        t shouldBe targets
        m shouldBe msg
  }
  
  // === BATCHING EFFECTS ===
  
  "BatchAppend effect" should "hold entries and batchId" in {
    val entries = Seq(Log.command(1, 1, "x".getBytes))
    val effect = BatchAppend(entries, "batch-42")
    
    effect match
      case BatchAppend(e, id) =>
        e shouldBe entries
        id shouldBe "batch-42"
  }
  
  "BatchComplete effect" should "hold batchId and commitIndex" in {
    val effect = BatchComplete("batch-42", 75)
    
    effect match
      case BatchComplete(id, idx) =>
        id shouldBe "batch-42"
        idx shouldBe 75
  }
  
  // === PIPELINING EFFECTS ===
  
  "PipelinedSend effect" should "hold target, message, and sequence number" in {
    val msg = AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0)
    val effect = PipelinedSend(node2, msg, 123)
    
    effect match
      case PipelinedSend(to, m, seq) =>
        to shouldBe node2
        m shouldBe msg
        seq shouldBe 123
  }
  
  "TrackInflight effect" should "hold followerId, sequence, and lastIndex" in {
    val effect = TrackInflight(node2, 123, 500)
    
    effect match
      case TrackInflight(id, seq, idx) =>
        id shouldBe node2
        seq shouldBe 123
        idx shouldBe 500
  }
  
  // === EFFECT EQUALITY ===
  
  "Effects" should "be equal when constructed with same values" in {
    val e1 = SendMessage(node2, AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0))
    val e2 = SendMessage(node2, AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0))
    
    e1 shouldBe e2
  }
  
  it should "not be equal when constructed with different values" in {
    val e1 = SendMessage(node2, AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0))
    val e2 = SendMessage(node3, AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0))
    
    e1 should not be e2
  }
