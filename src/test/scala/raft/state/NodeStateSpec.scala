package raft.state

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.NodeState.*

/**
 * Unit tests for NodeState enum and its operations.
 * 
 * NodeState represents the three possible roles in RAFT:
 * - Follower: Passive node waiting for leader heartbeats
 * - Candidate: Node running an election  
 * - Leader: Node actively replicating logs
 */
class NodeStateSpec extends AnyFlatSpec with Matchers:

  // FOLLOWER STATE TESTS
  
  "Follower" should "be created with default values" in {
    // Given: A new follower at term 0
    val follower: Follower = Follower(term = 0)
    
    // Then: Should have no vote and no known leader
    follower.term shouldBe 0
    follower.votedFor shouldBe None
    follower.leaderId shouldBe None
  }
  
  it should "track voted-for candidate in current term" in {
    // Given: A follower that voted for node-2
    val follower: Follower = Follower(
      term = 5,
      votedFor = Some(NodeId("node-2")),
      leaderId = None
    )
    
    // Then: The vote should be recorded
    follower.votedFor shouldBe Some(NodeId("node-2"))
  }
  
  it should "track known leader" in {
    // Given: A follower that knows node-1 is the leader
    val follower: Follower = Follower(
      term = 3,
      votedFor = None,
      leaderId = Some(NodeId("node-1"))
    )
    
    // Then: Leader should be recorded
    follower.leaderId shouldBe Some(NodeId("node-1"))
  }
  
  // CANDIDATE STATE TESTS
  
  "Candidate" should "start with votes from self" in {
    // Given: A candidate starting election
    val selfId = NodeId("self")
    val candidate: Candidate = Candidate(term = 5, votesReceived = Set(selfId))
    
    // Then: Should have self-vote
    candidate.votesReceived should contain(selfId)
    candidate.votesReceived.size shouldBe 1
  }
  
  it should "accumulate votes from multiple nodes" in {
    // Given: A candidate with initial self-vote
    val candidate: Candidate = Candidate(term = 5, votesReceived = Set(NodeId("self")))
    
    // When: Receiving votes from other nodes
    val withVote1 = candidate.withVote(NodeId("node-2"))
    val withVote2 = withVote1.withVote(NodeId("node-3"))
    
    // Then: All votes should be recorded
    withVote2.votesReceived.size shouldBe 3
    withVote2.votesReceived should contain(NodeId("self"))
    withVote2.votesReceived should contain(NodeId("node-2"))
    withVote2.votesReceived should contain(NodeId("node-3"))
  }
  
  it should "correctly determine majority in 3-node cluster" in {
    // Given: A 3-node cluster (majority = 2)
    val candidate: Candidate = Candidate(term = 5, votesReceived = Set(NodeId("self")))
    
    // Then: 1 vote is not majority
    candidate.hasMajority(clusterSize = 3) shouldBe false
    
    // When: Receiving one more vote
    val withSecondVote = candidate.withVote(NodeId("node-2"))
    
    // Then: 2 votes IS majority in 3-node cluster
    withSecondVote.hasMajority(clusterSize = 3) shouldBe true
  }
  
  it should "correctly determine majority in 5-node cluster" in {
    // Given: A 5-node cluster (majority = 3)
    val candidate: Candidate = Candidate(term = 1, votesReceived = Set(NodeId("n1"), NodeId("n2")))
    
    // Then: 2 votes is NOT majority
    candidate.hasMajority(clusterSize = 5) shouldBe false
    
    // When: Getting third vote
    val withThird = candidate.withVote(NodeId("n3"))
    
    // Then: 3 votes IS majority
    withThird.hasMajority(clusterSize = 5) shouldBe true
  }
  
  // LEADER STATE TESTS
  
  "Leader" should "initialize with empty index maps" in {
    // Given: A new leader
    val leader: Leader = Leader(term = 10)
    
    // Then: No follower indices tracked yet
    leader.nextIndex shouldBe empty
    leader.matchIndex shouldBe empty
  }
  
  it should "track next index for each follower" in {
    // Given: A leader initializing follower indices
    val leader: Leader = Leader(term = 10)
      .withNextIndex(NodeId("follower-1"), 100)
      .withNextIndex(NodeId("follower-2"), 95)
    
    // Then: Each follower has correct next index
    leader.nextIndex(NodeId("follower-1")) shouldBe 100
    leader.nextIndex(NodeId("follower-2")) shouldBe 95
  }
  
  it should "track match index for each follower" in {
    // Given: A leader tracking replication progress
    val leader: Leader = Leader(term = 10)
      .withMatchIndex(NodeId("follower-1"), 50)
      .withMatchIndex(NodeId("follower-2"), 48)
    
    // Then: Each follower has correct match index
    leader.matchIndex(NodeId("follower-1")) shouldBe 50
    leader.matchIndex(NodeId("follower-2")) shouldBe 48
  }
  
  // TERM OPERATIONS (ALL STATES)
  
  "NodeState term" should "extract term from Follower" in {
    val follower: Follower = Follower(term = 42)
    follower.term shouldBe 42
  }
  
  it should "extract term from Candidate" in {
    val candidate: Candidate = Candidate(term = 99)
    candidate.term shouldBe 99
  }
  
  it should "extract term from Leader" in {
    val leader: Leader = Leader(term = 7)
    leader.term shouldBe 7
  }
  
  // STEP DOWN BEHAVIOR
  
  "stepDown" should "convert Follower to new Follower with higher term" in {
    // Given: A follower at term 5 with a vote recorded
    val follower: Follower = Follower(term = 5, votedFor = Some(NodeId("old")), leaderId = Some(NodeId("leader")))
    
    // When: Stepping down to term 10
    val newState = follower.stepDown(10)
    
    // Then: Should be Follower with new term, cleared vote and leader
    newState shouldBe a[Follower]
    newState.term shouldBe 10
  }
  
  it should "convert Candidate to Follower when discovering higher term" in {
    // Given: A candidate running election at term 5
    val candidate: Candidate = Candidate(term = 5, votesReceived = Set(NodeId("self"), NodeId("voter")))
    
    // When: Discovering a message with term 7
    val newState = candidate.stepDown(7)
    
    // Then: Should become Follower, lose candidate state
    newState shouldBe a[Follower]
    newState.term shouldBe 7
  }
  
  it should "convert Leader to Follower when discovering higher term" in {
    // Given: A leader at term 10
    val leader: Leader = Leader(term = 10, nextIndex = Map(NodeId("f1") -> 50), matchIndex = Map(NodeId("f1") -> 40))
    
    // When: Discovering term 15 (another leader elected)
    val newState = leader.stepDown(15)
    
    // Then: Should become Follower, lose leader state
    newState shouldBe a[Follower]
    newState.term shouldBe 15
  }
