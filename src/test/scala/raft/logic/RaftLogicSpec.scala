package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.*
import raft.effect.Effect.*

/**
 * Comprehensive unit tests for RaftLogic - the pure state machine.
 * 
 * RaftLogic implements the RAFT algorithm as pure functions:
 *   (State, Message) -> (NewState, Effects)
 * 
 * This makes testing deterministic and easy to reason about.
 * 
 * Test Organization:
 * 1. Follower behavior tests
 * 2. Candidate behavior tests  
 * 3. Leader behavior tests
 * 4. Term management tests
 * 5. Election tests
 * 6. Log replication tests
 */
class RaftLogicSpec extends AnyFlatSpec with Matchers:

  // Test fixtures
  val nodeId1 = NodeId("node1")
  val nodeId2 = NodeId("node2")
  val nodeId3 = NodeId("node3")
  val config = RaftConfig.default(nodeId1)
  
  
  // 1.1 Vote Granting
  
  "Follower receiving RequestVote" should "grant vote when term is higher and log is up-to-date" in {
    // Scenario: Candidate with higher term requests vote from follower
    // Expected: Follower grants vote and updates term
    
    // Given: A follower at term 1 with no vote
    val state = Follower(term = 1, votedFor = None, leaderId = None)
    
    // When: Receiving vote request from candidate at term 2
    val voteReq = RequestVoteRequest(
      term = 2,
      candidateId = nodeId2,
      lastLogIndex = 5,
      lastLogTerm = 1,
      isPreVote = false
    )
    
    val transition = RaftLogic.onMessage(state, voteReq, config, 
      lastLogIndex = 5, lastLogTerm = 1, clusterSize = 3)
    
    // Then: Vote should be granted
    transition.state shouldBe a[Follower]
    transition.state.term shouldBe 2
    transition.state.asInstanceOf[Follower].votedFor shouldBe Some(nodeId2)
    
    // And: Response sent with grant=true
    val response = findSentMessage[RequestVoteResponse](transition.effects, nodeId2)
    response.voteGranted shouldBe true
    response.term shouldBe 2
  }
  
  it should "reject vote if already voted in current term" in {
    // Scenario: Follower already voted for another candidate
    // Expected: Vote denied (one vote per term rule)
    
    // Given: Follower that already voted for node3
    val state = Follower(term = 2, votedFor = Some(nodeId3), leaderId = None)
    
    // When: node2 requests vote
    val voteReq = RequestVoteRequest(
      term = 2,
      candidateId = nodeId2,
      lastLogIndex = 5,
      lastLogTerm = 1,
      isPreVote = false
    )
    
    val transition = RaftLogic.onMessage(state, voteReq, config, 5, 1, 3)
    
    // Then: Vote should be denied
    val response = findSentMessage[RequestVoteResponse](transition.effects, nodeId2)
    response.voteGranted shouldBe false
  }
  
  it should "reject vote if candidate's log is less up-to-date" in {
    // Scenario: Candidate has older log than follower
    // Expected: Vote denied (log comparison rule)
    
    // Given: Follower with log at term 5, index 100
    val state = Follower(term = 4, votedFor = None, leaderId = None)
    
    // When: Candidate with older log requests vote
    val voteReq = RequestVoteRequest(
      term = 5,
      candidateId = nodeId2,
      lastLogIndex = 50,  // Candidate's log is shorter
      lastLogTerm = 3,    // Candidate's last term is older
      isPreVote = false
    )
    
    val transition = RaftLogic.onMessage(state, voteReq, config,
      lastLogIndex = 100, lastLogTerm = 5, clusterSize = 3)
    
    // Then: Vote denied because candidate's log is behind
    val response = findSentMessage[RequestVoteResponse](transition.effects, nodeId2)
    response.voteGranted shouldBe false
  }
  
  // 1.2 Leader Stickiness (Disruption Prevention)
  
  "Follower with leader stickiness" should "reject vote if leader is known" in {
    // Scenario: Follower knows current leader, receives vote request
    // Expected: Reject to prevent unnecessary leader changes
    
    val configWithStickiness = config.copy(leaderStickinessEnabled = true)
    
    // Given: Follower with known leader
    val state = Follower(term = 2, votedFor = None, leaderId = Some(NodeId("current-leader")))
    
    // When: Another node requests vote at same term
    val voteReq = RequestVoteRequest(
      term = 2,
      candidateId = nodeId2,
      lastLogIndex = 10,
      lastLogTerm = 2,
      isPreVote = false
    )
    
    val transition = RaftLogic.onMessage(state, voteReq, configWithStickiness, 5, 2, 3)
    
    // Then: Vote denied to maintain stability
    val response = findSentMessage[RequestVoteResponse](transition.effects, nodeId2)
    response.voteGranted shouldBe false
  }
  
  // 1.3 AppendEntries Handling
  
  "Follower receiving AppendEntries" should "accept empty heartbeat and record leader" in {
    // Scenario: Leader sends heartbeat (empty AppendEntries)
    // Expected: Follower acknowledges and records leader
    
    // Given: Follower at term 2 with no known leader
    val state = Follower(term = 2, votedFor = None, leaderId = None)
    
    // When: Leader sends heartbeat
    val heartbeat = AppendEntriesRequest(
      term = 2,
      leaderId = nodeId2,
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq.empty,
      leaderCommit = 0
    )
    
    val transition = RaftLogic.onMessage(state, heartbeat, config, 0, 0, 3)
    
    // Then: Leader should be recorded
    transition.state shouldBe a[Follower]
    transition.state.asInstanceOf[Follower].leaderId shouldBe Some(nodeId2)
    
    // And: Election timer should be reset
    transition.effects should contain(ResetElectionTimer)
    
    // And: Success response sent
    val response = findSentMessage[AppendEntriesResponse](transition.effects, nodeId2)
    response.success shouldBe true
  }
  
  it should "step down and accept leader on higher term" in {
    // Scenario: Message from leader with higher term
    // Expected: Update term and accept new leader
    
    // Given: Follower at term 1
    val state = Follower(term = 1, votedFor = None, leaderId = None)
    
    // When: Leader at term 2 sends AppendEntries
    val appendReq = AppendEntriesRequest(
      term = 2,
      leaderId = nodeId2,
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq.empty,
      leaderCommit = 0
    )
    
    val transition = RaftLogic.onMessage(state, appendReq, config, 0, 0, 3)
    
    // Then: Term should be updated and leader recorded
    transition.state.term shouldBe 2
    transition.state.asInstanceOf[Follower].leaderId shouldBe Some(nodeId2)
  }
  
  
  "Candidate receiving RequestVoteResponse" should "become leader on majority" in {
    // Scenario: Candidate receives enough votes to win election
    // Expected: Transition to Leader state
    
    // Given: Candidate with vote from self
    val state: Candidate = Candidate(term = 5, votesReceived = Set(nodeId1))
    
    // When: Receiving vote from node2 (now has 2/3 = majority)
    val voteResp: RequestVoteResponse = RequestVoteResponse(term = 5, voteGranted = true, isPreVote = false)
    
    val transition = RaftLogic.onVoteResponse(state, nodeId2, voteResp, config, 3)
    
    // Then: Should become Leader
    transition.state shouldBe a[Leader]
    transition.state.term shouldBe 5
    
    // And: Should announce leadership via heartbeats
    transition.effects should contain(BecomeLeader)
  }
  
  "Candidate receiving AppendEntries" should "step down on same or higher term" in {
    // Scenario: Another leader is established at same/higher term
    // Expected: Step down to Follower
    
    // Given: Candidate running election at term 5
    val state = Candidate(term = 5, votesReceived = Set(nodeId1))
    
    // When: Leader at term 5 sends heartbeat
    val heartbeat = AppendEntriesRequest(
      term = 5,
      leaderId = nodeId2,
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq.empty,
      leaderCommit = 0
    )
    
    val transition = RaftLogic.onMessage(state, heartbeat, config, 0, 0, 3)
    
    // Then: Should become Follower accepting the leader
    transition.state shouldBe a[Follower]
    transition.state.term shouldBe 5
    transition.state.asInstanceOf[Follower].leaderId shouldBe Some(nodeId2)
  }
  
  
  "Leader receiving HeartbeatTimeout" should "broadcast AppendEntries to all followers" in {
    // Scenario: Heartbeat timer fires
    // Expected: Send empty AppendEntries to maintain authority
    
    // Given: Leader at term 3
    val state = Leader(term = 3, nextIndex = Map.empty, matchIndex = Map.empty)
    
    // When: Heartbeat timeout
    val transition = RaftLogic.onMessage(state, HeartbeatTimeout, config, 10, 3, 3)
    
    // Then: Should broadcast heartbeat and reset timer
    transition.effects.exists {
      case Broadcast(_: AppendEntriesRequest) => true
      case _ => false
    } shouldBe true
    
    transition.effects should contain(ResetHeartbeatTimer)
  }
  
  "Leader" should "step down on higher term message" in {
    // Scenario: Leader discovers higher term
    // Expected: Immediately step down to Follower
    
    // Given: Leader at term 5
    val state = Leader(term = 5, nextIndex = Map(nodeId2 -> 100), matchIndex = Map(nodeId2 -> 99))
    
    // When: Receiving message with term 10
    val higherTermMsg = AppendEntriesRequest(
      term = 10,
      leaderId = nodeId2,
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq.empty,
      leaderCommit = 0
    )
    
    val transition = RaftLogic.onMessage(state, higherTermMsg, config, 100, 5, 3)
    
    // Then: Should become Follower at term 10
    transition.state shouldBe a[Follower]
    transition.state.term shouldBe 10
  }
  
  
  "Election timeout on Follower" should "start pre-vote election and become PreCandidate" in {
    // Scenario: Follower hasn't heard from leader (pre-vote enabled)
    // Expected: Start pre-vote, become PreCandidate (term not yet incremented)
    
    // Given: Follower at term 5 with pre-vote enabled (default)
    val state = Follower(term = 5, votedFor = None, leaderId = None)
    
    // When: Election times out
    val transition = RaftLogic.onMessage(state, ElectionTimeout, config, 10, 5, 3)
    
    // Then: Should become PreCandidate at SAME term (pre-vote doesn't increment)
    transition.state shouldBe a[PreCandidate]
    transition.state.term shouldBe 5  // Term NOT incremented in pre-vote
    
    // And: Should have pre-voted for self
    transition.state.asInstanceOf[PreCandidate].preVotesReceived should contain(config.localId)
    
    // And: Should broadcast PreVote RequestVote (isPreVote = true)
    transition.effects.exists {
      case Broadcast(req: RequestVoteRequest) => req.isPreVote && req.term == 6
      case _ => false
    } shouldBe true
  }
  
  it should "start direct election with pre-vote disabled" in {
    // Scenario: Follower with pre-vote disabled
    // Expected: Start real election, become Candidate, increment term
    
    val configNoPreVote = config.copy(preVoteEnabled = false)
    val state = Follower(term = 5, votedFor = None, leaderId = None)
    
    val transition = RaftLogic.onMessage(state, ElectionTimeout, configNoPreVote, 10, 5, 3)
    
    transition.state shouldBe a[Candidate]
    transition.state.term shouldBe 6
    transition.state.asInstanceOf[Candidate].votesReceived should contain(configNoPreVote.localId)
  }
  
  "Election timeout on Candidate" should "start new election with higher term" in {
    // Scenario: Candidate's election times out (split vote)
    // Expected: Start new election with incremented term
    
    // Given: Candidate at term 5 with only self vote
    val state = Candidate(term = 5, votesReceived = Set(nodeId1))
    
    // When: Election times out
    val transition = RaftLogic.onMessage(state, ElectionTimeout, config, 10, 5, 3)
    
    // Then: Should start new election at term 6
    transition.state shouldBe a[Candidate]
    transition.state.term shouldBe 6
  }
  
  
  private def findSentMessage[M <: RaftMessage](effects: List[Effect], to: NodeId)(using ct: scala.reflect.ClassTag[M]): M =
    effects.collectFirst {
      case SendMessage(`to`, msg) if ct.runtimeClass.isInstance(msg) => msg.asInstanceOf[M]
    }.getOrElse(fail(s"Expected SendMessage to $to with ${ct.runtimeClass.getSimpleName}"))
