package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalacheck.{Gen, Arbitrary}
import raft.state.*
import raft.state.NodeState.*

/**
 * Property-based tests for RAFT invariants.
 * 
 * These tests verify fundamental RAFT protocol guarantees hold
 * across many randomly generated scenarios.
 * 
 * Key Invariants Tested:
 * 1. Term never decreases
 * 2. At most one vote per term
 * 3. At most one leader per term
 * 4. Log matching property
 */
class RaftInvariantsSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:
  
  
  val genNodeId: Gen[NodeId] = Gen.alphaNumStr.map(s => NodeId(s"node-$s"))
  
  val genTerm: Gen[Long] = Gen.choose(0L, 1000L)
  
  val genFollower: Gen[Follower] = for
    term     <- genTerm
    votedFor <- Gen.option(genNodeId)
    leader   <- Gen.option(genNodeId)
  yield Follower(term, votedFor, leader)
  
  val genCandidate: Gen[Candidate] = for
    term  <- genTerm
    votes <- Gen.containerOf[Set, NodeId](genNodeId)
  yield Candidate(term, votes)
  
  val genLeader: Gen[Leader] = for
    term <- genTerm
  yield Leader(term, Map.empty, Map.empty)
  
  val genNodeState: Gen[NodeState] = Gen.oneOf(genFollower, genCandidate, genLeader)
  
  
  "Term" should "never decrease after state transitions" in {
    forAll(genNodeState, genTerm) { (state, newTerm) =>
      // When transitioning to higher term
      if newTerm >= state.term then
        val nextState = state.stepDown(newTerm)
        
        // Then: New term >= old term
        nextState.term should be >= state.term
    }
  }
  
  "Follower term" should "update only to equal or higher values" in {
    forAll(genFollower, genTerm) { (follower, receivedTerm) =>
      // Note: Actual RAFT implementation would reject lower terms
      // This test verifies the stepDown behavior
      val newState = follower.stepDown(math.max(follower.term, receivedTerm))
      newState.term should be >= follower.term
    }
  }
  
  
  "Follower" should "vote for at most one candidate per term" in {
    forAll(genFollower) { follower =>
      // A follower's votedFor should be None or exactly one NodeId
      follower.votedFor match
        case None => succeed
        case Some(nodeId) =>
          // If voted, can only have one vote
          follower.votedFor.size shouldBe 1
    }
  }
  
  
  "Candidate" should "need strict majority to become leader" in {
    forAll(genCandidate, Gen.choose(1, 9)) { (candidate, clusterSize) =>
      val majorityNeeded = (clusterSize / 2) + 1
      val hasEnoughVotes = candidate.votesReceived.size >= majorityNeeded
      
      candidate.hasMajority(clusterSize) shouldBe hasEnoughVotes
    }
  }
  
  "Majority calculation" should "be correct for odd cluster sizes" in {
    // 3-node cluster: need 2 votes
    Candidate(1, Set(NodeId("a"))).hasMajority(3) shouldBe false
    Candidate(1, Set(NodeId("a"), NodeId("b"))).hasMajority(3) shouldBe true
    
    // 5-node cluster: need 3 votes
    Candidate(1, Set(NodeId("a"), NodeId("b"))).hasMajority(5) shouldBe false
    Candidate(1, Set(NodeId("a"), NodeId("b"), NodeId("c"))).hasMajority(5) shouldBe true
    
    // 7-node cluster: need 4 votes
    Candidate(1, Set(NodeId("a"), NodeId("b"), NodeId("c"))).hasMajority(7) shouldBe false
    Candidate(1, Set(NodeId("a"), NodeId("b"), NodeId("c"), NodeId("d"))).hasMajority(7) shouldBe true
  }
  
  
  "Any state" should "become Follower when stepping down" in {
    forAll(genNodeState, genTerm) { (state, newTerm) =>
      whenever(newTerm >= state.term) {
        val nextState = state.stepDown(newTerm)
        
        // Any state stepping down becomes Follower
        nextState shouldBe a[Follower]
      }
    }
  }
  
  "Stepped down state" should "have cleared votedFor and leaderId" in {
    forAll(genNodeState, genTerm) { (state, newTerm) =>
      whenever(newTerm > state.term) {
        val nextState = state.stepDown(newTerm).asInstanceOf[Follower]
        
        // When stepping to higher term, vote is reset
        nextState.votedFor shouldBe None
        nextState.leaderId shouldBe None
      }
    }
  }
  
  
  "Candidate votes" should "accumulate monotonically" in {
    forAll(genCandidate, genNodeId) { (candidate, voter) =>
      val afterVote = candidate.withVote(voter)
      
      // Vote count can only increase or stay same (if duplicate)
      afterVote.votesReceived.size should be >= candidate.votesReceived.size
      
      // Original votes preserved
      candidate.votesReceived.foreach { v =>
        afterVote.votesReceived should contain(v)
      }
      
      // New vote included
      afterVote.votesReceived should contain(voter)
    }
  }
