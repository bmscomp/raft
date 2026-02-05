package raft.integration

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.*
import raft.state.NodeState.*

/**
 * Tests for cluster membership changes using joint consensus.
 * 
 * Joint consensus is the safe way to change cluster membership:
 * 1. Begin transition: use BOTH old and new config
 * 2. Commit transition: switch to new config only
 * 
 * This prevents split-brain during reconfiguration.
 */
class MembershipChangeSpec extends AnyFlatSpec with Matchers:
  
  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")
  val node4 = NodeId("node-4")
  val node5 = NodeId("node-5")
  
  "Adding single node" should "work without joint consensus for small change" in {
    // Simple case: add one node to 3-node cluster
    val config = ClusterConfig(Set(node1, node2, node3))
    config.voters.size shouldBe 3
    config.majoritySize shouldBe 2
    
    val expanded = config.addMember(node4, NodeRole.Voter)
    expanded.voters.size shouldBe 4
    expanded.majoritySize shouldBe 3  // now need 3 for majority
  }
  
  "Adding multiple nodes" should "use joint consensus" in {
    // Adding 2 nodes to 3-node cluster = significant change
    val oldConfig = ClusterConfig(Set(node1, node2, node3))
    
    // Enter joint consensus with new nodes
    val newMembers = Map(node4 -> NodeRole.Voter, node5 -> NodeRole.Voter)
    val joint = oldConfig.beginJointConsensus(newMembers)
    
    joint.isInJointConsensus shouldBe true
    joint.voters.size shouldBe 5  // all nodes vote during joint
  }
  
  "Joint consensus quorum" should "require majority in both configs" in {
    val oldConfig = ClusterConfig(Set(node1, node2, node3))
    val newMembers = Map(node4 -> NodeRole.Voter, node5 -> NodeRole.Voter)
    val joint = oldConfig.beginJointConsensus(newMembers)
    
    // Old config needs 2/3, new has 5 nodes needs 3/5
    
    // Just old majority: fails (need new majority too)
    joint.hasQuorum(Set(node1, node2)) shouldBe false
    
    // Just new majority: fails (need old majority too)
    joint.hasQuorum(Set(node1, node4, node5)) shouldBe false
    
    // Both majorities: succeeds
    joint.hasQuorum(Set(node1, node2, node4)) shouldBe true  // 2/3 old, 3/5 new
    joint.hasQuorum(Set(node1, node2, node3, node4, node5)) shouldBe true
  }
  
  "Completing joint consensus" should "switch to new config only" in {
    val oldConfig = ClusterConfig(Set(node1, node2, node3))
    val newMembers = Map(node4 -> NodeRole.Voter, node5 -> NodeRole.Voter)
    val joint = oldConfig.beginJointConsensus(newMembers)
    val completed = joint.completeJointConsensus
    
    completed.isInJointConsensus shouldBe false
    completed.voters.size shouldBe 5
    // Now only new config's majority matters: 3/5
    completed.hasQuorum(Set(node1, node2, node3)) shouldBe true  // 3/5
    completed.hasQuorum(Set(node1, node2)) shouldBe false  // 2/5
  }
  
  "Removing node" should "update majority requirement" in {
    val config = ClusterConfig(Set(node1, node2, node3, node4, node5))
    config.majoritySize shouldBe 3
    
    val shrunk = config.removeMember(node5).removeMember(node4)
    shrunk.voters.size shouldBe 3
    shrunk.majoritySize shouldBe 2
  }
  
  "Learner nodes" should "not affect quorum calculations" in {
    val config = ClusterConfig(Set(node1, node2, node3))
      .addMember(node4, NodeRole.Learner)
      .addMember(node5, NodeRole.Learner)
    
    config.voters.size shouldBe 3  // only original 3
    config.learners.size shouldBe 2
    config.allNodes.size shouldBe 5
    config.majoritySize shouldBe 2  // still based on 3 voters
    
    // Learners don't count for quorum
    config.hasQuorum(Set(node4, node5)) shouldBe false
    config.hasQuorum(Set(node1, node2)) shouldBe true
  }
  
  "Promoting learner" should "add to voter set" in {
    val config = ClusterConfig(Set(node1, node2, node3))
      .addMember(node4, NodeRole.Learner)
    
    config.voters shouldBe Set(node1, node2, node3)
    
    val promoted = config.promoteLearner(node4)
    promoted.voters shouldBe Set(node1, node2, node3, node4)
    promoted.learners shouldBe empty
    promoted.majoritySize shouldBe 3  // now 4 voters
  }
  
  "Witness nodes" should "vote but have minimal storage" in {
    // Witnesses help with tie-breaking in even-sized clusters
    val config = ClusterConfig(Map(
      node1 -> NodeRole.Voter,
      node2 -> NodeRole.Voter,
      node3 -> NodeRole.Witness  // Tie-breaker
    ))
    
    // Witnesses count as voters for quorum
    config.voters shouldBe Set(node1, node2, node3)
    config.majoritySize shouldBe 2
    
    // Witness vote counts
    config.hasQuorum(Set(node1, node3)) shouldBe true  // voter + witness
  }

/**
 * Tests for snapshot installation during membership changes.
 */
class SnapshotMembershipSpec extends AnyFlatSpec with Matchers:
  
  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")
  val node4 = NodeId("node-4")
  
  "Snapshot" should "include cluster configuration" in {
    val config = ClusterConfig(Set(node1, node2, node3))
    val snapshot = Snapshot(
      lastIncludedIndex = 100,
      lastIncludedTerm = 5,
      data = "state machine data".getBytes,
      config = Some(config)
    )
    
    snapshot.config shouldBe defined
    snapshot.config.get.voters shouldBe Set(node1, node2, node3)
  }
  
  "New node" should "receive snapshot with current config" in {
    // Simulates: new node joining gets snapshot from leader
    val leaderConfig = ClusterConfig(Set(node1, node2, node3, node4))
    
    // Snapshot captures config at time of snapshot
    val snapshot = Snapshot(
      lastIncludedIndex = 500,
      lastIncludedTerm = 10,
      data = "compacted state".getBytes,
      config = Some(leaderConfig)
    )
    
    // New node installs snapshot and learns the cluster membership
    snapshot.config.get.allNodes should contain(node4)
    snapshot.config.get.voters.size shouldBe 4
  }
  
  "Pending snapshot" should "accumulate chunks and produce final snapshot" in {
    val pending = PendingSnapshot(
      lastIncludedIndex = 100,
      lastIncludedTerm = 5,
      offset = 0,
      chunks = Vector.empty
    )
    
    // Receive chunks
    val chunk1 = "first part".getBytes
    val chunk2 = " second part".getBytes
    
    val withChunks = pending
      .appendChunk(chunk1)
      .appendChunk(chunk2)
    
    withChunks.offset shouldBe (chunk1.length + chunk2.length)
    
    // Finalize snapshot
    val finalSnapshot = withChunks.toSnapshot
    new String(finalSnapshot.data) shouldBe "first part second part"
    finalSnapshot.lastIncludedIndex shouldBe 100
    finalSnapshot.lastIncludedTerm shouldBe 5
  }
