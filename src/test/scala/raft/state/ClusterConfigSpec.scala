package raft.state

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ClusterConfigSpec extends AnyFlatSpec with Matchers:

  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")
  val node4 = NodeId("node-4")
  val node5 = NodeId("node-5")

  // === CREATION & BASICS ===

  "ClusterConfig" should "create single-node cluster" in {
    val config = ClusterConfig.single(node1)
    config.voters shouldBe Set(node1)
    config.allNodes shouldBe Set(node1)
    config.majoritySize shouldBe 1
  }

  it should "create multi-node cluster" in {
    val config = ClusterConfig(Set(node1, node2, node3))
    config.voters shouldBe Set(node1, node2, node3)
    config.majoritySize shouldBe 2 // 3/2 + 1 = 2
  }

  it should "calculate majority correctly for even-sized clusters" in {
    val config = ClusterConfig(Set(node1, node2))
    config.majoritySize shouldBe 2 // 2/2 + 1 = 2
  }

  // === MEMBERSHIP CHANGES (SINGLE CONFIG) ===

  it should "add member" in {
    val config = ClusterConfig.single(node1)
    val updated = config.addMember(node2, NodeRole.Voter)
    updated.voters shouldBe Set(node1, node2)
  }

  it should "add learner (non-voting)" in {
    val config = ClusterConfig.single(node1)
    val updated = config.addMember(node2, NodeRole.Learner)
    updated.voters shouldBe Set(node1) // learner doesn't vote
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

  it should "prevent creating empty configuration via joint consensus" in {
    val config = ClusterConfig.single(node1)

    // Empty config
    assertThrows[IllegalArgumentException] {
      config.beginJointConsensus(Map.empty)
    }

    // No voters (only learners)
    assertThrows[IllegalArgumentException] {
      config.beginJointConsensus(Map(node2 -> NodeRole.Learner))
    }
  }

  it should "prevent creating invalid cluster instances" in {
    // Constructor requires at least one voter
    assertThrows[IllegalArgumentException] {
      ClusterConfig(Map.empty)
    }

    // Only learners is not allowed
    assertThrows[IllegalArgumentException] {
      ClusterConfig(Map(node1 -> NodeRole.Learner))
    }
  }

  // === HELPER METHODS ===

  "Helper methods" should "correctly identify roles" in {
    val config = ClusterConfig
      .single(node1)
      .addMember(node2, NodeRole.Learner)
      .addMember(node3, NodeRole.Witness)

    config.isVoter(node1) shouldBe true
    config.isLearner(node1) shouldBe false

    config.isVoter(node2) shouldBe false
    config.isLearner(node2) shouldBe true

    config.isVoter(node3) shouldBe true
    config.isLearner(node3) shouldBe false

    config.contains(node1) shouldBe true
    config.contains(node4) shouldBe false
  }

  // === QUORUM LOGIC ===

  "Quorum" should "require majority of voters" in {
    val config = ClusterConfig(Set(node1, node2, node3))
    config.hasQuorum(Set(node1)) shouldBe false // 1/3
    config.hasQuorum(Set(node1, node2)) shouldBe true // 2/3
    config.hasQuorum(Set(node1, node2, node3)) shouldBe true // 3/3
  }

  it should "ignore non-voters in quorum calculation" in {
    val config = ClusterConfig(Set(node1, node2, node3))
      .addMember(node4, NodeRole.Learner)

    // Learner doesn't count toward quorum
    config.hasQuorum(Set(node1, node4)) shouldBe false
    config.hasQuorum(Set(node1, node2)) shouldBe true
  }

  // === JOINT CONSENSUS ===

  "Joint consensus" should "transition to new configuration" in {
    val oldConfig = ClusterConfig(Set(node1, node2, node3))
    // New config: {1, 2, 3, 4} (Adding node 4)
    val newMembers = oldConfig.members + (node4 -> NodeRole.Voter)

    val joint = oldConfig.beginJointConsensus(newMembers)
    joint.isInJointConsensus shouldBe true

    // In joint consensus, 'allNodes' should reflect the new config
    // (though strictly speaking it depends on how we define it - usually union of both)
    // In our implementation, `members` is the new config, and `jointConfig` is the old.
    // The `allNodes` method uses `members.keySet`.
    joint.allNodes shouldBe Set(node1, node2, node3, node4)
  }

  it should "require majority in both configs during transition" in {
    val oldConfig = ClusterConfig(Set(node1, node2, node3))
    // Transition to {1, 2, 4, 5} (Removing 3, Adding 4, 5)
    val newMembers = Map(
      node1 -> NodeRole.Voter,
      node2 -> NodeRole.Voter,
      node4 -> NodeRole.Voter,
      node5 -> NodeRole.Voter
    )

    val joint = oldConfig.beginJointConsensus(newMembers)

    // Old Config (1,2,3): Majority is 2
    // New Config (1,2,4,5): Majority is 3

    // Scenario 1: {1, 2}
    // Old: 2/3 (Pass)
    // New: 2/4 (Fail - needs 3)
    joint.hasQuorum(Set(node1, node2)) shouldBe false

    // Scenario 2: {4, 5, 1}
    // Old: {1} -> 1/3 (Fail)
    // New: 3/4 (Pass)
    joint.hasQuorum(Set(node1, node4, node5)) shouldBe false

    // Scenario 3: {1, 2, 4}
    // Old: {1, 2} -> 2/3 (Pass)
    // New: {1, 2, 4} -> 3/4 (Pass)
    joint.hasQuorum(Set(node1, node2, node4)) shouldBe true
  }

  it should "allow node removal via joint consensus" in {
    // Initial: {1, 2, 3}
    val oldConfig = ClusterConfig(Set(node1, node2, node3))

    // New: {1, 2} (Removing 3)
    val newMembers = Map(
      node1 -> NodeRole.Voter,
      node2 -> NodeRole.Voter
    )

    val joint = oldConfig.beginJointConsensus(newMembers)

    // Verify joint state
    joint.isInJointConsensus shouldBe true
    joint.members shouldBe newMembers

    // Complete transition
    val completed = joint.completeJointConsensus

    completed.isInJointConsensus shouldBe false
    completed.allNodes shouldBe Set(node1, node2)
    completed.voters shouldBe Set(node1, node2)

    // Node 3 is gone
    completed.members.contains(node3) shouldBe false
  }

  it should "complete and use only new config" in {
    val oldConfig = ClusterConfig(Set(node1, node2, node3))
    // New: {4, 5} (Replacing entire cluster - distinct sets)
    val newMembers = Map(node4 -> NodeRole.Voter, node5 -> NodeRole.Voter)

    val joint = oldConfig.beginJointConsensus(newMembers)
    val completed = joint.completeJointConsensus

    completed.isInJointConsensus shouldBe false
    // After completion, only new config applies
    completed.allNodes shouldBe Set(node4, node5)
  }

  it should "allow aborting joint consensus" in {
    val oldConfig = ClusterConfig(Set(node1, node2))
    val newMembers = Map(
      node1 -> NodeRole.Voter,
      node2 -> NodeRole.Voter,
      node3 -> NodeRole.Voter
    )

    val joint = oldConfig.beginJointConsensus(newMembers)
    joint.isInJointConsensus shouldBe true

    val aborted = joint.abortJointConsensus
    aborted.isInJointConsensus shouldBe false
    aborted.members shouldBe oldConfig.members
    aborted.allNodes shouldBe Set(node1, node2)
  }
