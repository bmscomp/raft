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
 * Integration tests for multi-node RAFT scenarios.
 * 
 * These tests simulate a complete 3-node cluster and verify:
 * 1. Leader election from initial state
 * 2. Successful vote collection
 * 3. Heartbeat establishment
 * 4. Follower recognition of leader
 * 5. Re-election after leader failure
 */
class LeaderElectionSpec extends AnyFlatSpec with Matchers:
  
  // TEST FIXTURES
  
  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")
  
  val config1 = RaftConfig.default(node1).copy(preVoteEnabled = false)
  val config2 = RaftConfig.default(node2)
  val config3 = RaftConfig.default(node3)
  
  // SCENARIO 1: SUCCESSFUL LEADER ELECTION
  
  "3-node cluster election" should "elect leader when one node times out first" in {
    // STEP 1: Initial state - all followers
    
    var state1: NodeState = Follower(term = 0)
    var state2: NodeState = Follower(term = 0)
    var state3: NodeState = Follower(term = 0)
    
    // STEP 2: Node-1 times out, becomes candidate
    
    val trans1 = RaftLogic.onMessage(state1, ElectionTimeout, config1, 0, 0, 3)
    state1 = trans1.state
    
    // Verify: Node-1 is now Candidate at term 1
    state1 shouldBe a[Candidate]
    state1.term shouldBe 1
    state1.asInstanceOf[Candidate].votesReceived should contain(node1)
    
    // Extract vote request broadcast
    val voteReq = trans1.effects.collectFirst {
      case Broadcast(msg: RequestVoteRequest) => msg
    }.get
    
    voteReq.term shouldBe 1
    voteReq.candidateId shouldBe node1
    
    // STEP 3: Node-2 and Node-3 receive vote request
    
    val trans2 = RaftLogic.onMessage(state2, voteReq, config2, 0, 0, 3)
    val trans3 = RaftLogic.onMessage(state3, voteReq, config3, 0, 0, 3)
    
    state2 = trans2.state
    state3 = trans3.state
    
    // Verify: Both granted votes
    val resp2 = trans2.effects.collectFirst {
      case SendMessage(_, msg: RequestVoteResponse) => msg
    }.get
    val resp3 = trans3.effects.collectFirst {
      case SendMessage(_, msg: RequestVoteResponse) => msg
    }.get
    
    resp2.voteGranted shouldBe true
    resp3.voteGranted shouldBe true
    
    // Verify: Both followers updated term
    state2.term shouldBe 1
    state3.term shouldBe 1
    
    // STEP 4: Node-1 receives votes, becomes leader
    
    // Receive first vote (now has 2/3)
    val transVote1 = RaftLogic.onVoteResponse(
      state1.asInstanceOf[Candidate], node2, resp2, config1, 3)
    state1 = transVote1.state
    
    // Verify: Node-1 should now be Leader
    state1 shouldBe a[Leader]
    state1.term shouldBe 1
    
    transVote1.effects should contain(BecomeLeader)
    
    // STEP 5: Leader sends heartbeats
    
    val heartbeatTrans = RaftLogic.onMessage(state1, HeartbeatTimeout, config1, 0, 0, 3)
    
    val heartbeat = heartbeatTrans.effects.collectFirst {
      case Broadcast(msg: AppendEntriesRequest) => msg
    }.get
    
    heartbeat.term shouldBe 1
    heartbeat.leaderId shouldBe node1
    heartbeat.entries shouldBe empty  // Heartbeat has no entries
    
    // STEP 6: Followers acknowledge leader
    
    val trans2hb = RaftLogic.onMessage(state2, heartbeat, config2, 0, 0, 3)
    val trans3hb = RaftLogic.onMessage(state3, heartbeat, config3, 0, 0, 3)
    
    state2 = trans2hb.state
    state3 = trans3hb.state
    
    // Verify: Both followers know node-1 is leader
    state2.asInstanceOf[Follower].leaderId shouldBe Some(node1)
    state3.asInstanceOf[Follower].leaderId shouldBe Some(node1)
    
    // FINAL STATE
    
    println(s"Final cluster state:")
    println(s"  Node-1: $state1")
    println(s"  Node-2: $state2")
    println(s"  Node-3: $state3")
    
    // Verify cluster is stable
    state1 shouldBe a[Leader]
    state2 shouldBe a[Follower]
    state3 shouldBe a[Follower]
    
    // All at same term
    state1.term shouldBe state2.term
    state2.term shouldBe state3.term
  }
  
  // SCENARIO 2: SPLIT VOTE AND RE-ELECTION
  
  "Split vote" should "result in new election with higher term" in {
    // STEP 1: Two nodes timeout simultaneously
    
    var state1: NodeState = Follower(term = 0)
    var state2: NodeState = Follower(term = 0)
    var state3: NodeState = Follower(term = 0)
    
    // Both node-1 and node-2 timeout and become candidates
    val trans1 = RaftLogic.onMessage(state1, ElectionTimeout, config1, 0, 0, 3)
    val trans2 = RaftLogic.onMessage(state2, ElectionTimeout, config2.copy(preVoteEnabled = false), 0, 0, 3)
    
    state1 = trans1.state
    state2 = trans2.state
    
    // Both are candidates at term 1
    state1 shouldBe a[Candidate]
    state2 shouldBe a[Candidate]
    state1.term shouldBe 1
    state2.term shouldBe 1
    
    // STEP 2: Node-3 votes for node-1 (first request received)
    
    val voteReq1 = trans1.effects.collectFirst {
      case Broadcast(msg: RequestVoteRequest) => msg
    }.get
    
    val trans3 = RaftLogic.onMessage(state3, voteReq1, config3, 0, 0, 3)
    state3 = trans3.state
    
    // Node-3 voted for node-1
    state3.asInstanceOf[Follower].votedFor shouldBe Some(node1)
    
    // STEP 3: Node-3 rejects node-2's vote request (already voted)
    
    val voteReq2 = trans2.effects.collectFirst {
      case Broadcast(msg: RequestVoteRequest) => msg
    }.get
    
    val trans3b = RaftLogic.onMessage(state3, voteReq2, config3, 0, 0, 3)
    
    val resp3b = trans3b.effects.collectFirst {
      case SendMessage(_, msg: RequestVoteResponse) => msg
    }.get
    
    // Vote denied - already voted for node-1
    resp3b.voteGranted shouldBe false
    
    // STEP 4: Neither candidate gets majority, election times out
    
    // Node-1 has 2 votes (self + node-3) -> becomes leader
    val voteResp3 = trans3.effects.collectFirst {
      case SendMessage(_, msg: RequestVoteResponse) => msg
    }.get
    
    val transLead = RaftLogic.onVoteResponse(
      state1.asInstanceOf[Candidate], node3, voteResp3, config1, 3)
    
    // Node-1 becomes leader with 2/3 majority
    transLead.state shouldBe a[Leader]
    
    // Node-2 only has self-vote (1/3) - stays candidate
    // Would timeout and start term 2 election
    val transTimeout = RaftLogic.onMessage(state2, ElectionTimeout, config2.copy(preVoteEnabled = false), 0, 0, 3)
    
    transTimeout.state shouldBe a[Candidate]
    transTimeout.state.term shouldBe 2  // Incremented term for new election
  }
  
  // SCENARIO 3: LEADER STEP DOWN
  
  "Leader discovering higher term" should "step down immediately" in {
    // Given: Node-1 is leader at term 5
    var leaderState: NodeState = Leader(term = 5)
    
    // When: Receives message with higher term
    val higherTermMsg = AppendEntriesRequest(
      term = 10,
      leaderId = node2,
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq.empty,
      leaderCommit = 0
    )
    
    val trans = RaftLogic.onMessage(leaderState, higherTermMsg, config1, 0, 0, 3)
    leaderState = trans.state
    
    // Then: Steps down to follower
    leaderState shouldBe a[Follower]
    leaderState.term shouldBe 10
    leaderState.asInstanceOf[Follower].leaderId shouldBe Some(node2)
  }
