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
 * Tests for Witness Nodes functionality.
 * 
 * Witness nodes are tie-breaker members that:
 * - Vote in elections (count toward quorum)
 * - Do NOT store the full log (only metadata)
 * - Primarily used to break ties in even-sized clusters
 * - Lower resource requirements than full voters
 */
class WitnessNodeSpec extends AnyFlatSpec with Matchers:
  
  val node1 = NodeId("node-1")    // Voter
  val node2 = NodeId("node-2")    // Voter
  val witness1 = NodeId("witness-1")  // Witness
  
  // 2 voters + 1 witness = odd cluster for tie-breaking
  val clusterConfig = ClusterConfig(Map(
    node1 -> NodeRole.Voter,
    node2 -> NodeRole.Voter,
    witness1 -> NodeRole.Witness
  ))
  
  // === WITNESS COUNTS AS VOTER ===
  
  "ClusterConfig with witness" should "count witness in voters set" in {
    // Witnesses can vote, so they're included in voters
    clusterConfig.voters shouldBe Set(node1, node2, witness1)
    clusterConfig.learners shouldBe Set.empty
    clusterConfig.allNodes shouldBe Set(node1, node2, witness1)
  }
  
  it should "include witness in majority calculation" in {
    // 3 voting members = majority is 2
    clusterConfig.majoritySize shouldBe 2
  }
  
  it should "count witness vote toward quorum" in {
    // Witness vote counts!
    clusterConfig.hasQuorum(Set(node1, witness1)) shouldBe true      // 2/3
    clusterConfig.hasQuorum(Set(witness1)) shouldBe false            // 1/3
    clusterConfig.hasQuorum(Set(node1, node2, witness1)) shouldBe true  // 3/3
  }
  
  // === WITNESS AS TIE-BREAKER ===
  
  "Witness as tie-breaker" should "enable odd quorum in even data nodes" in {
    // Scenario: 2 full data nodes + 1 witness
    // Without witness: split vote possible (1 vs 1)
    // With witness: clear majority (2 vs 1)
    
    val twoNodeCluster = ClusterConfig(Set(node1, node2))
    twoNodeCluster.majoritySize shouldBe 2  // Need both for quorum!
    twoNodeCluster.hasQuorum(Set(node1)) shouldBe false  // Split vote
    
    // Add witness for tie-breaking
    val withWitness = twoNodeCluster.addMember(witness1, NodeRole.Witness)
    withWitness.majoritySize shouldBe 2  // Now 3 voters, need 2
    withWitness.hasQuorum(Set(node1, witness1)) shouldBe true  // Tie broken!
    withWitness.hasQuorum(Set(node2, witness1)) shouldBe true  // Either side can win
  }
  
  // === WITNESS IN LARGER CLUSTERS ===
  
  "Witness in 4+1 cluster" should "provide odd quorum" in {
    val node3 = NodeId("node-3")
    val node4 = NodeId("node-4")
    
    // 4 data nodes + 1 witness = 5 voters
    val fiveNodeCluster = ClusterConfig(Map(
      node1 -> NodeRole.Voter,
      node2 -> NodeRole.Voter,
      node3 -> NodeRole.Voter,
      node4 -> NodeRole.Voter,
      witness1 -> NodeRole.Witness
    ))
    
    fiveNodeCluster.voters.size shouldBe 5
    fiveNodeCluster.majoritySize shouldBe 3
    
    // 3 nodes can form quorum (including witness)
    fiveNodeCluster.hasQuorum(Set(node1, node2, witness1)) shouldBe true
    fiveNodeCluster.hasQuorum(Set(node3, node4, witness1)) shouldBe true
    
    // 2 nodes cannot
    fiveNodeCluster.hasQuorum(Set(node1, node2)) shouldBe false
  }
  
  // === MIXED CLUSTER ===
  
  "Mixed cluster" should "correctly handle voters, learners, and witnesses" in {
    val learner1 = NodeId("learner-1")
    
    // 2 voters + 1 witness + 1 learner
    val mixedCluster = ClusterConfig(Map(
      node1 -> NodeRole.Voter,
      node2 -> NodeRole.Voter,
      witness1 -> NodeRole.Witness,
      learner1 -> NodeRole.Learner
    ))
    
    mixedCluster.voters shouldBe Set(node1, node2, witness1)  // Witness votes
    mixedCluster.learners shouldBe Set(learner1)               // Learner doesn't
    mixedCluster.allNodes shouldBe Set(node1, node2, witness1, learner1)
    
    // Quorum is 2 (from 3 voting members)
    mixedCluster.majoritySize shouldBe 2
    
    // Learner doesn't count toward quorum
    mixedCluster.hasQuorum(Set(node1, learner1)) shouldBe false
    mixedCluster.hasQuorum(Set(node1, witness1)) shouldBe true
  }
  
  // === WITNESS ROLE CHANGES ===
  
  "Witness role" should "be addable to cluster" in {
    val simple = ClusterConfig(Set(node1, node2))
    val withWitness = simple.addMember(witness1, NodeRole.Witness)
    
    withWitness.voters should contain(witness1)
    withWitness.allNodes.size shouldBe 3
  }
  
  it should "be removable from cluster" in {
    val withWitness = clusterConfig
    val withoutWitness = withWitness.removeMember(witness1)
    
    withoutWitness.voters shouldBe Set(node1, node2)
    withoutWitness.majoritySize shouldBe 2  // Both needed now
  }
  
  // === WITNESS ELECTION BEHAVIOR ===
  
  "Witness in election" should "be able to grant vote" in {
    // Witness node receives vote request and can grant vote
    val config = RaftConfig(localId = witness1, preVoteEnabled = false)
    val witnessState = Follower(term = 0, votedFor = None, leaderId = None)
    
    val voteReq = RequestVoteRequest(
      term = 1,
      candidateId = node1,
      lastLogIndex = 0,
      lastLogTerm = 0,
      isPreVote = false
    )
    
    val transition = RaftLogic.onMessage(witnessState, voteReq, config, 0, 0, 3)
    
    // Witness grants vote
    val response = transition.effects.collectFirst {
      case SendMessage(_, resp: RequestVoteResponse) => resp
    }
    response shouldBe defined
    response.get.voteGranted shouldBe true
  }
