package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.*

/** Pure tests for cluster membership changes and Joint Consensus quorum rules.
  */
class MembershipChangeSpec extends AnyFlatSpec with Matchers:

  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")
  val node4 = NodeId("node-4")
  val node5 = NodeId("node-5")

  "Adding single node" should "work without joint consensus for small change" in {
    val config = ClusterConfig(Set(node1, node2, node3))
    config.voters.size shouldBe 3
    config.majoritySize shouldBe 2

    val expanded = config.addMember(node4, NodeRole.Voter)
    expanded.voters.size shouldBe 4
    expanded.majoritySize shouldBe 3
  }

  "Adding multiple nodes" should "use joint consensus" in {
    val oldConfig = ClusterConfig(Set(node1, node2, node3))
    val newMembers = Map(node4 -> NodeRole.Voter, node5 -> NodeRole.Voter)
    val joint = oldConfig.beginJointConsensus(newMembers)

    joint.isInJointConsensus shouldBe true
    joint.voters.size shouldBe 5
  }

  "Joint consensus quorum" should "require majority in both configs" in {
    val oldConfig = ClusterConfig(Set(node1, node2, node3))
    val newMembers = oldConfig.members ++ Map(
      node4 -> NodeRole.Voter,
      node5 -> NodeRole.Voter
    )
    val joint = oldConfig.beginJointConsensus(newMembers)

    joint.hasQuorum(Set(node1, node2)) shouldBe false
    joint.hasQuorum(Set(node1, node4, node5)) shouldBe false
    joint.hasQuorum(Set(node1, node2, node4)) shouldBe true
    joint.hasQuorum(Set(node1, node2, node3, node4, node5)) shouldBe true
  }

  "Completing joint consensus" should "switch to new config only" in {
    val oldConfig = ClusterConfig(Set(node1, node2, node3))
    val newMembers = Map(
      node1 -> NodeRole.Voter,
      node2 -> NodeRole.Voter,
      node3 -> NodeRole.Voter,
      node4 -> NodeRole.Voter,
      node5 -> NodeRole.Voter
    )
    val joint = oldConfig.beginJointConsensus(newMembers)
    val completed = joint.completeJointConsensus

    completed.isInJointConsensus shouldBe false
    completed.voters.size shouldBe 5
    completed.hasQuorum(Set(node1, node2, node3)) shouldBe true
    completed.hasQuorum(Set(node1, node2)) shouldBe false
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

    config.voters.size shouldBe 3
    config.learners.size shouldBe 2
    config.allNodes.size shouldBe 5
    config.majoritySize shouldBe 2

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
    promoted.majoritySize shouldBe 3
  }

  "Witness nodes" should "vote but have minimal storage" in {
    val config = ClusterConfig(
      Map(
        node1 -> NodeRole.Voter,
        node2 -> NodeRole.Voter,
        node3 -> NodeRole.Witness
      )
    )

    config.voters shouldBe Set(node1, node2, node3)
    config.majoritySize shouldBe 2
    config.hasQuorum(Set(node1, node3)) shouldBe true
  }
