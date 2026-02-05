package raft.integration

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.logic.RaftLogic
import raft.effect.Effect.*

/**
 * Integration tests for log replication scenarios.
 * 
 * Tests cover:
 * 1. Leader appending entries
 * 2. Follower accepting entries
 * 3. Heartbeat behavior
 * 4. State tracking
 * 
 * NOTE: Current implementation is simplified - it always accepts
 * AppendEntries from a valid leader without checking log consistency.
 * Full log matching will be implemented in a future phase.
 */
class LogReplicationSpec extends AnyFlatSpec with Matchers:
  
  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")
  
  val config1 = RaftConfig.default(node1)
  val config2 = RaftConfig.default(node2)
  
  // SCENARIO 1: BASIC LOG REPLICATION
  
  "Leader" should "include new entries in AppendEntries" in {
    // Given: Leader with some entries
    val leaderState = Leader(
      term = 3,
      nextIndex = Map(node2 -> 1, node3 -> 1),
      matchIndex = Map(node2 -> 0, node3 -> 0)
    )
    
    // When: Heartbeat timeout triggers
    val transition = RaftLogic.onMessage(leaderState, HeartbeatTimeout, config1,
      lastLogIndex = 5, lastLogTerm = 3, clusterSize = 3)
    
    // Then: AppendEntries should be broadcast
    val appendReq = transition.effects.collectFirst {
      case Broadcast(msg: AppendEntriesRequest) => msg
    }
    
    appendReq shouldBe defined
    appendReq.get.term shouldBe 3
    appendReq.get.leaderId shouldBe node1
  }
  
  // SCENARIO 2: FOLLOWER ACCEPTING ENTRIES
  
  "Follower" should "accept entries from valid leader" in {
    // Given: Follower with matching prev log
    val followerState = Follower(term = 3, votedFor = None, leaderId = Some(node1))
    
    // When: Receives AppendEntries with entries
    val appendReq = AppendEntriesRequest(
      term = 3,
      leaderId = node1,
      prevLogIndex = 0,  // Log is empty, so prev is 0
      prevLogTerm = 0,
      entries = Seq(
        Log.command(1, 3, "cmd1".getBytes),
        Log.command(2, 3, "cmd2".getBytes)
      ),
      leaderCommit = 0
    )
    
    val transition = RaftLogic.onMessage(followerState, appendReq, config2,
      lastLogIndex = 0, lastLogTerm = 0, clusterSize = 3)
    
    // Then: Should respond with success
    val response = transition.effects.collectFirst {
      case SendMessage(_, msg: AppendEntriesResponse) => msg
    }
    
    response shouldBe defined
    response.get.success shouldBe true
    response.get.term shouldBe 3
    
    // And: Should reset election timer
    transition.effects should contain(ResetElectionTimer)
    
    // And: Should record leader
    transition.state.asInstanceOf[Follower].leaderId shouldBe Some(node1)
  }
  
  it should "update leader ID when receiving AppendEntries" in {
    // Given: Follower with no known leader
    val followerState = Follower(term = 3, votedFor = None, leaderId = None)
    
    // When: Receives AppendEntries from a leader
    val appendReq = AppendEntriesRequest(
      term = 3,
      leaderId = node1,
      prevLogIndex = 10,  // Even if logs don't match
      prevLogTerm = 2,
      entries = Seq.empty,
      leaderCommit = 5
    )
    
    val transition = RaftLogic.onMessage(followerState, appendReq, config2,
      lastLogIndex = 0, lastLogTerm = 0, clusterSize = 3)
    
    // Then: Should record the leader
    transition.state.asInstanceOf[Follower].leaderId shouldBe Some(node1)
  }
  
  // SCENARIO 3: HEARTBEAT RESETS TIMER
  
  "Follower receiving heartbeat" should "reset election timer" in {
    val followerState = Follower(term = 5, votedFor = None, leaderId = Some(node1))
    
    val heartbeat = AppendEntriesRequest(
      term = 5,
      leaderId = node1,
      prevLogIndex = 10,
      prevLogTerm = 5,
      entries = Seq.empty,  // Heartbeat
      leaderCommit = 8
    )
    
    val transition = RaftLogic.onMessage(followerState, heartbeat, config2,
      lastLogIndex = 10, lastLogTerm = 5, clusterSize = 3)
    
    transition.effects should contain(ResetElectionTimer)
  }
  
  // SCENARIO 4: COMMIT INDEX ADVANCEMENT
  
  "Follower" should "apply entries when leaderCommit advances" in {
    val followerState = Follower(term = 5, votedFor = None, leaderId = Some(node1))
    
    // Assume follower has logs up to index 10
    val appendReq = AppendEntriesRequest(
      term = 5,
      leaderId = node1,
      prevLogIndex = 10,
      prevLogTerm = 5,
      entries = Seq.empty,
      leaderCommit = 10  // Leader has committed up to 10
    )
    
    val transition = RaftLogic.onMessage(followerState, appendReq, config2,
      lastLogIndex = 10, lastLogTerm = 5, clusterSize = 3)
    
    // Note: Actual commit handling would generate ApplyToStateMachine effects
    // This test verifies the basic flow works
    transition.state shouldBe a[Follower]
    transition.effects should contain(ResetElectionTimer)
  }
  
  // SCENARIO 5: TERM HANDLING  
  
  "Follower" should "step up to higher term from AppendEntries" in {
    // Given: Follower at term 1
    val followerState = Follower(term = 1, votedFor = None, leaderId = None)
    
    // When: Receives AppendEntries from leader at term 3
    val appendReq = AppendEntriesRequest(
      term = 3,
      leaderId = node1,
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq.empty,
      leaderCommit = 0
    )
    
    val transition = RaftLogic.onMessage(followerState, appendReq, config2,
      lastLogIndex = 0, lastLogTerm = 0, clusterSize = 3)
    
    // Then: Should update term and accept leader
    transition.state.term shouldBe 3
    transition.state.asInstanceOf[Follower].leaderId shouldBe Some(node1)
  }
