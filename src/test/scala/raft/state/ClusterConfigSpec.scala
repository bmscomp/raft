package raft.state

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ClusterConfigSpec extends AnyFlatSpec with Matchers:
  
  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")
  val node4 = NodeId("node-4")
  val node5 = NodeId("node-5")
  
  "ClusterConfig" should "create single-node cluster" in {
    val config = ClusterConfig.single(node1)
    config.voters shouldBe Set(node1)
    config.allNodes shouldBe Set(node1)
    config.majoritySize shouldBe 1
  }
  
  it should "create multi-node cluster" in {
    val config = ClusterConfig(Set(node1, node2, node3))
    config.voters shouldBe Set(node1, node2, node3)
    config.majoritySize shouldBe 2  // 3/2 + 1 = 2
  }
  
  it should "add member" in {
    val config = ClusterConfig.single(node1)
    val updated = config.addMember(node2, NodeRole.Voter)
    updated.voters shouldBe Set(node1, node2)
  }
  
  it should "add learner (non-voting)" in {
    val config = ClusterConfig.single(node1)
    val updated = config.addMember(node2, NodeRole.Learner)
    updated.voters shouldBe Set(node1)  // learner doesn't vote
    updated.learners shouldBe Set(node2)
    updated.allNodes shouldBe Set(node1, node2)
  }
  
  it should "promote learner to voter" in {
    val config = ClusterConfig.single(node1).addMember(node2, NodeRole.Learner)
    val promoted = config.promoteLearner(node2)
    promoted.voters shouldBe Set(node1, node2)
    promoted.learners shouldBe empty
  }
  
  it should "remove member" in {
    val config = ClusterConfig(Set(node1, node2, node3))
    val updated = config.removeMember(node3)
    updated.voters shouldBe Set(node1, node2)
  }
  
  "Quorum" should "require majority of voters" in {
    val config = ClusterConfig(Set(node1, node2, node3))
    config.hasQuorum(Set(node1)) shouldBe false  // 1/3
    config.hasQuorum(Set(node1, node2)) shouldBe true  // 2/3
    config.hasQuorum(Set(node1, node2, node3)) shouldBe true  // 3/3
  }
  
  it should "ignore non-voters in quorum calculation" in {
    val config = ClusterConfig(Set(node1, node2, node3))
      .addMember(node4, NodeRole.Learner)
    
    // Learner doesn't count toward quorum
    config.hasQuorum(Set(node1, node4)) shouldBe false
    config.hasQuorum(Set(node1, node2)) shouldBe true
  }
  
  "Joint consensus" should "begin with both configs" in {
    val oldConfig = ClusterConfig(Set(node1, node2, node3))
    val newMembers = Map(node4 -> NodeRole.Voter)
    
    val joint = oldConfig.beginJointConsensus(newMembers)
    joint.isInJointConsensus shouldBe true
    joint.allNodes shouldBe Set(node1, node2, node3, node4)
  }
  
  it should "require majority in both configs" in {
    val oldConfig = ClusterConfig(Set(node1, node2, node3))
    val newMembers = Map(node4 -> NodeRole.Voter, node5 -> NodeRole.Voter)
    val joint = oldConfig.beginJointConsensus(newMembers)
    
    // Need majority in old (2/3) AND new (3/5)
    joint.hasQuorum(Set(node1, node2)) shouldBe false  // old majority but not new
    joint.hasQuorum(Set(node1, node2, node4, node5)) shouldBe true  // both majorities
  }
  
  it should "complete and use only new config" in {
    val oldConfig = ClusterConfig(Set(node1, node2, node3))
    val newMembers = Map(node4 -> NodeRole.Voter, node5 -> NodeRole.Voter)
    val joint = oldConfig.beginJointConsensus(newMembers)
    val completed = joint.completeJointConsensus
    
    completed.isInJointConsensus shouldBe false
    // After completion, only new config applies
    completed.allNodes shouldBe Set(node1, node2, node3, node4, node5)
  }
