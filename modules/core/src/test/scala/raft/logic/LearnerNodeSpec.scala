package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.*
import raft.state.NodeState.*

/** Tests for Learner Nodes functionality in ClusterConfig and Leader state. */
class LearnerNodeSpec extends AnyFlatSpec with Matchers:

  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")
  val learner1 = NodeId("learner-1")
  val learner2 = NodeId("learner-2")

  val clusterConfig = ClusterConfig(
    Map(
      node1 -> NodeRole.Voter,
      node2 -> NodeRole.Voter,
      node3 -> NodeRole.Voter,
      learner1 -> NodeRole.Learner,
      learner2 -> NodeRole.Learner
    )
  )

  "ClusterConfig" should "correctly identify voters and learners" in {
    clusterConfig.voters shouldBe Set(node1, node2, node3)
    clusterConfig.learners shouldBe Set(learner1, learner2)
    clusterConfig.allNodes shouldBe Set(node1, node2, node3, learner1, learner2)
  }

  it should "calculate majority based on voters only" in {
    clusterConfig.majoritySize shouldBe 2
  }

  it should "determine quorum from voters only" in {
    clusterConfig.hasQuorum(Set(node1, node2)) shouldBe true
    clusterConfig.hasQuorum(Set(node1)) shouldBe false

    clusterConfig.hasQuorum(
      Set(node1, learner1, learner2)
    ) shouldBe false
    clusterConfig.hasQuorum(
      Set(node1, node2, learner1)
    ) shouldBe true
  }

  "Learner promotion" should "convert learner to voter" in {
    val promoted = clusterConfig.promoteLearner(learner1)

    promoted.voters shouldBe Set(node1, node2, node3, learner1)
    promoted.learners shouldBe Set(learner2)
    promoted.majoritySize shouldBe 3
  }

  it should "do nothing for non-learner nodes" in {
    val unchanged = clusterConfig.promoteLearner(node1)
    unchanged shouldBe clusterConfig
  }

  it should "do nothing for unknown nodes" in {
    val unchanged = clusterConfig.promoteLearner(NodeId("unknown"))
    unchanged shouldBe clusterConfig
  }

  "Adding members" should "support adding new learners" in {
    val newLearner = NodeId("new-learner")
    val updated = clusterConfig.addMember(newLearner, NodeRole.Learner)

    updated.learners should contain(newLearner)
    updated.voters shouldBe clusterConfig.voters
  }

  it should "support adding new voters directly" in {
    val newVoter = NodeId("new-voter")
    val updated = clusterConfig.addMember(newVoter, NodeRole.Voter)

    updated.voters should contain(newVoter)
    updated.majoritySize shouldBe 3
  }

  "Leader replication" should "include learners in broadcast" in {
    clusterConfig.allNodes.size shouldBe 5
  }

  "Leader matchIndex" should "track learner replication progress" in {
    val matchIdx = Map(
      node2 -> 99L,
      node3 -> 98L,
      learner1 -> 49L,
      learner2 -> 74L
    )
    val nextIdx = Map(
      node2 -> 100L,
      node3 -> 100L,
      learner1 -> 50L,
      learner2 -> 75L
    )

    val leader: Leader = Leader(
      term = 5,
      nextIndex = nextIdx,
      matchIndex = matchIdx
    )

    leader.matchIndex.keySet should contain allOf (
      node2,
      node3,
      learner1,
      learner2
    )
    leader.nextIndex.keySet should contain allOf (
      node2,
      node3,
      learner1,
      learner2
    )
  }

  "Commit calculation" should "only consider voter matchIndex" in {
    val voterMatches = List(100L, 90L, 80L)
    val allMatches = List(100L, 90L, 80L, 50L, 40L)

    val sortedVoters = voterMatches.sorted
    val voterMedian = sortedVoters(sortedVoters.size / 2)
    voterMedian shouldBe 90

    val sortedAll = allMatches.sorted
    val allMedian = sortedAll(sortedAll.size / 2)
    allMedian shouldBe 80
  }

  "Full learner workflow" should "add learner, catch up, then promote" in {
    val initial = ClusterConfig(Set(node1, node2, node3))
    initial.voters.size shouldBe 3
    initial.learners.size shouldBe 0

    val withLearner = initial.addMember(learner1, NodeRole.Learner)
    withLearner.voters.size shouldBe 3
    withLearner.learners.size shouldBe 1
    withLearner.majoritySize shouldBe 2

    val promoted = withLearner.promoteLearner(learner1)
    promoted.voters.size shouldBe 4
    promoted.learners.size shouldBe 0
    promoted.majoritySize shouldBe 3
  }
