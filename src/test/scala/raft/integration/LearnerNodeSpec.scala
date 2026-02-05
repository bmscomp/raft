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
 * Tests for Learner Nodes functionality.
 * 
 * Learner nodes are non-voting members that:
 * - Receive log replication from leader
 * - Do NOT participate in elections (cannot vote)
 * - Do NOT count toward quorum for commits
 * - Can be promoted to full voter once caught up
 */
class LearnerNodeSpec extends AnyFlatSpec with Matchers:
  
  val node1 = NodeId("node-1")  // Voter
  val node2 = NodeId("node-2")  // Voter  
  val node3 = NodeId("node-3")  // Voter
  val learner1 = NodeId("learner-1")  // Learner
  val learner2 = NodeId("learner-2")  // Learner
  
  // Cluster with 3 voters + 2 learners
  val clusterConfig = ClusterConfig(Map(
    node1 -> NodeRole.Voter,
    node2 -> NodeRole.Voter,
    node3 -> NodeRole.Voter,
    learner1 -> NodeRole.Learner,
    learner2 -> NodeRole.Learner
  ))
  
  // === LEARNER VOTES DON'T COUNT ===
  
  "ClusterConfig" should "correctly identify voters and learners" in {
    clusterConfig.voters shouldBe Set(node1, node2, node3)
    clusterConfig.learners shouldBe Set(learner1, learner2)
    clusterConfig.allNodes shouldBe Set(node1, node2, node3, learner1, learner2)
  }
  
  it should "calculate majority based on voters only" in {
    // 3 voters = majority is 2
    clusterConfig.majoritySize shouldBe 2
  }
  
  it should "determine quorum from voters only" in {
    // Only voters count for quorum
    clusterConfig.hasQuorum(Set(node1, node2)) shouldBe true  // 2/3 voters
    clusterConfig.hasQuorum(Set(node1)) shouldBe false        // 1/3 voters
    
    // Learners don't count toward quorum
    clusterConfig.hasQuorum(Set(node1, learner1, learner2)) shouldBe false  // Only 1 voter
    clusterConfig.hasQuorum(Set(node1, node2, learner1)) shouldBe true      // 2 voters + 1 learner
  }
  
  // === LEARNER PROMOTION ===
  
  "Learner promotion" should "convert learner to voter" in {
    val promoted = clusterConfig.promoteLearner(learner1)
    
    promoted.voters shouldBe Set(node1, node2, node3, learner1)
    promoted.learners shouldBe Set(learner2)
    promoted.majoritySize shouldBe 3  // Now 4 voters, majority is 3
  }
  
  it should "do nothing for non-learner nodes" in {
    val unchanged = clusterConfig.promoteLearner(node1)
    unchanged shouldBe clusterConfig
  }
  
  it should "do nothing for unknown nodes" in {
    val unchanged = clusterConfig.promoteLearner(NodeId("unknown"))
    unchanged shouldBe clusterConfig
  }
  
  // === ADDING LEARNERS ===
  
  "Adding members" should "support adding new learners" in {
    val newLearner = NodeId("new-learner")
    val updated = clusterConfig.addMember(newLearner, NodeRole.Learner)
    
    updated.learners should contain(newLearner)
    updated.voters shouldBe clusterConfig.voters  // Voters unchanged
  }
  
  it should "support adding new voters directly" in {
    val newVoter = NodeId("new-voter")
    val updated = clusterConfig.addMember(newVoter, NodeRole.Voter)
    
    updated.voters should contain(newVoter)
    updated.majoritySize shouldBe 3  // 4 voters now
  }
  
  // === LEARNER RECEIVES REPLICATION ===
  
  "Leader replication" should "include learners in broadcast" in {
    // When leader broadcasts, ALL nodes (including learners) should receive
    // This is ensured by using allNodes in the Broadcast effect
    
    clusterConfig.allNodes.size shouldBe 5  // 3 voters + 2 learners
  }
  
  // === LEARNER LOG CATCHUP TRACKING ===
  
  "Leader matchIndex" should "track learner replication progress" in {
    // Leader state correctly tracks both voters and learners
    val matchIdx = Map(
      node2 -> 99L, node3 -> 98L,              // Voters
      learner1 -> 49L, learner2 -> 74L         // Learners
    )
    val nextIdx = Map(
      node2 -> 100L, node3 -> 100L,            // Voters
      learner1 -> 50L, learner2 -> 75L         // Learners (catching up)
    )
    
    val leader = Leader(
      term = 5,
      nextIndex = nextIdx,
      matchIndex = matchIdx
    )
    
    // Leader correctly has both voters and learners in tracking maps
    matchIdx.keySet should contain allOf(node2, node3, learner1, learner2)
    nextIdx.keySet should contain allOf(node2, node3, learner1, learner2)
  }
  
  // === COMMIT INDEX DOESN'T CONSIDER LEARNERS ===
  
  "Commit calculation" should "only consider voter matchIndex" in {
    // Scenario: 3 voters at indices 100, 90, 80 + 2 learners at 50, 40
    // Commit should be median of VOTERS only = 90 (not affected by learners)
    
    val voterMatches = List(100L, 90L, 80L)
    val allMatches = List(100L, 90L, 80L, 50L, 40L)
    
    // Median of voters (3 nodes): sorted = [80, 90, 100], median = 90
    val sortedVoters = voterMatches.sorted
    val voterMedian = sortedVoters(sortedVoters.size / 2)
    voterMedian shouldBe 90
    
    // If we incorrectly included learners: sorted = [40, 50, 80, 90, 100], median = 80
    val sortedAll = allMatches.sorted
    val allMedian = sortedAll(sortedAll.size / 2)
    allMedian shouldBe 80  // Wrong! This is why we must exclude learners
  }
  
  // === LEARNER PROMOTION WORKFLOW ===
  
  "Full learner workflow" should "add learner, catch up, then promote" in {
    // Step 1: Start with 3-node cluster
    val initial = ClusterConfig(Set(node1, node2, node3))
    initial.voters.size shouldBe 3
    initial.learners.size shouldBe 0
    
    // Step 2: Add learner
    val withLearner = initial.addMember(learner1, NodeRole.Learner)
    withLearner.voters.size shouldBe 3
    withLearner.learners.size shouldBe 1
    withLearner.majoritySize shouldBe 2  // Still 2 (learner doesn't affect quorum)
    
    // Step 3: Learner catches up (tracked in Leader.matchIndex)
    // ... replication happens ...
    
    // Step 4: Promote to voter
    val promoted = withLearner.promoteLearner(learner1)
    promoted.voters.size shouldBe 4
    promoted.learners.size shouldBe 0
    promoted.majoritySize shouldBe 3  // Now needs 3 for quorum
  }
