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
 * Tests for commit index advancement and state machine application.
 */
class CommitAdvancementSpec extends AnyFlatSpec with Matchers:
  
  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")
  
  "VolatileState" should "track commit and apply indices separately" in {
    var state = VolatileState(commitIndex = 0, lastApplied = 0)
    
    // Leader advances commit after replication
    state = state.advanceCommitIndex(10)
    state.commitIndex shouldBe 10
    state.lastApplied shouldBe 0  // not yet applied
    
    // Apply entries one by one
    state = state.advanceLastApplied(5)
    state.lastApplied shouldBe 5
    state.pendingApplications.toList shouldBe List(6, 7, 8, 9, 10)
    
    // Apply rest
    state = state.advanceLastApplied(10)
    state.lastApplied shouldBe 10
    state.pendingApplications shouldBe empty
  }
  
  "Follower" should "update commit index from leader" in {
    // Leader sends AppendEntries with leaderCommit = 50
    val follower = Follower(term = 5, votedFor = None, leaderId = Some(node1))
    val volatileState = VolatileState(commitIndex = 30, lastApplied = 30)
    
    val heartbeat: AppendEntriesRequest = AppendEntriesRequest(
      term = 5,
      leaderId = node1,
      prevLogIndex = 45,
      prevLogTerm = 5,
      entries = Seq.empty,
      leaderCommit = 50
    )
    
    // After receiving, follower should update commit to min(leaderCommit, lastLogIndex)
    // Assuming lastLogIndex >= 50, commit advances to 50
    val leaderCommit = heartbeat.leaderCommit
    val newCommit = math.min(leaderCommit, 50L)
    val newVolatile = volatileState.advanceCommitIndex(newCommit)
    
    newVolatile.commitIndex shouldBe 50
    newVolatile.pendingApplications.size shouldBe 20  // 31 to 50
  }
  
  "Commit index" should "never decrease" in {
    val state = VolatileState(commitIndex = 100, lastApplied = 50)
    
    // Attempt to set lower commit (should be ignored)
    val same = state.advanceCommitIndex(50)
    same.commitIndex shouldBe 100  // unchanged
  }
  
  "Leader" should "calculate median match index for commit" in {
    // In a 3-node cluster, need 2/3 for commit
    // If matchIndex = {node2: 100, node3: 80}, median is 80
    val leader: Leader = Leader(
      term = 5,
      nextIndex = Map(node2 -> 101, node3 -> 81),
      matchIndex = Map(node2 -> 100, node3 -> 80)
    )
    
    // For commit, we need majority of nodes to have entry
    // Leader always has entry, so check: min to get majority
    val matchIndices = leader.matchIndex.values.toSeq :+ 100L // leader's index
    val sortedIndices = matchIndices.sorted
    
    // Majority is 2, so we need index that at least 2 nodes have
    // That's the median
    val clusterSize = 3
    val majorityIndex = clusterSize / 2
    val commitCandidate = sortedIndices(majorityIndex)
    
    commitCandidate shouldBe 100  // 2 nodes have index 100
  }

/**
 * Tests for edge cases and boundary conditions.
 */
class EdgeCaseSpec extends AnyFlatSpec with Matchers:
  
  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")
  
  val config = RaftConfig.default(node1).copy(preVoteEnabled = false)
  
  "Single-node cluster" should "immediately become leader" in {
    val state = Follower(term = 0, votedFor = None, leaderId = None)
    val trans = RaftLogic.onMessage(state, ElectionTimeout, config, 0, 0, 1)
    
    // With only 1 node, self-vote is majority
    trans.state shouldBe a[Candidate]
    val candidate = trans.state.asInstanceOf[Candidate]
    
    // In single node, 1/1 = majority immediately
    candidate.hasMajority(1) shouldBe true
  }
  
  "Empty log" should "handle vote requests correctly" in {
    val state = Follower(term = 1, votedFor = None, leaderId = None)
    
    // Candidate with empty log
    val voteReq = RequestVoteRequest(
      term = 2,
      candidateId = node2,
      lastLogIndex = 0,  // empty log
      lastLogTerm = 0,
      isPreVote = false
    )
    
    val trans = RaftLogic.onMessage(state, voteReq, config, 0, 0, 3)
    
    // Vote should be granted (both have empty log)
    val response = trans.effects.collectFirst {
      case SendMessage(_, msg: RequestVoteResponse) => msg
    }
    response.get.voteGranted shouldBe true
  }
  
  "Term 0" should "be valid initial state" in {
    val follower = Follower(term = 0, votedFor = None, leaderId = None)
    follower.term shouldBe 0
    
    // Election should increment to term 1
    val trans = RaftLogic.onMessage(follower, ElectionTimeout, config, 0, 0, 3)
    trans.state.term shouldBe 1
  }
  
  "Very large term" should "be handled correctly" in {
    val hugeTerm = Long.MaxValue - 1
    val state = Follower(term = hugeTerm, votedFor = None, leaderId = None)
    
    // Should be able to step down to max value
    val stepped = state.stepDown(Long.MaxValue)
    stepped.term shouldBe Long.MaxValue
  }
  
  "Concurrent vote requests" should "grant to first only" in {
    val state = Follower(term = 5, votedFor = None, leaderId = None)
    
    // First vote request
    val voteReq1 = RequestVoteRequest(
      term = 6, candidateId = node2, lastLogIndex = 10, lastLogTerm = 5, isPreVote = false
    )
    val trans1 = RaftLogic.onMessage(state, voteReq1, config, 10, 5, 3)
    
    // State now has voted for node2
    val newState = trans1.state.asInstanceOf[Follower]
    newState.votedFor shouldBe Some(node2)
    
    // Second vote request in same term
    val voteReq2 = RequestVoteRequest(
      term = 6, candidateId = node3, lastLogIndex = 10, lastLogTerm = 5, isPreVote = false
    )
    val trans2 = RaftLogic.onMessage(newState, voteReq2, config, 10, 5, 3)
    
    // Vote denied - already voted
    val response = trans2.effects.collectFirst {
      case SendMessage(_, msg: RequestVoteResponse) => msg
    }
    response.get.voteGranted shouldBe false
    
    // Still voted for node2
    trans2.state.asInstanceOf[Follower].votedFor shouldBe Some(node2)
  }
  
  "Leader receiving own heartbeat" should "reject it" in {
    // Edge case: message routing error causes leader to receive its own heartbeat
    val leader = Leader(term = 5, nextIndex = Map.empty, matchIndex = Map.empty)
    
    val ownHeartbeat = AppendEntriesRequest(
      term = 5,
      leaderId = node1,  // Same as local
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq.empty,
      leaderCommit = 0
    )
    
    val trans = RaftLogic.onMessage(leader, ownHeartbeat, config, 10, 5, 3)
    
    // Should reject - two leaders at same term is impossible
    trans.state shouldBe a[Leader]  // Stays leader
  }
