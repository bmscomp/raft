package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.Effect.*

/** Pure tests for Witness Node quorum and tie-breaker behavior. */
class WitnessNodeSpec extends AnyFlatSpec with Matchers:

  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val witness1 = NodeId("witness-1")

  val clusterConfig = ClusterConfig(
    Map(
      node1 -> NodeRole.Voter,
      node2 -> NodeRole.Voter,
      witness1 -> NodeRole.Witness
    )
  )

  "ClusterConfig with witness" should "count witness in voters set" in {
    clusterConfig.voters shouldBe Set(node1, node2, witness1)
    clusterConfig.learners shouldBe Set.empty
    clusterConfig.allNodes shouldBe Set(node1, node2, witness1)
  }

  it should "include witness in majority calculation" in {
    clusterConfig.majoritySize shouldBe 2
  }

  it should "count witness vote toward quorum" in {
    clusterConfig.hasQuorum(Set(node1, witness1)) shouldBe true
    clusterConfig.hasQuorum(Set(witness1)) shouldBe false
    clusterConfig.hasQuorum(Set(node1, node2, witness1)) shouldBe true
  }

  "Witness as tie-breaker" should "enable odd quorum in even data nodes" in {
    val twoNodeCluster = ClusterConfig(Set(node1, node2))
    twoNodeCluster.majoritySize shouldBe 2
    twoNodeCluster.hasQuorum(Set(node1)) shouldBe false

    val withWitness = twoNodeCluster.addMember(witness1, NodeRole.Witness)
    withWitness.majoritySize shouldBe 2
    withWitness.hasQuorum(Set(node1, witness1)) shouldBe true
    withWitness.hasQuorum(Set(node2, witness1)) shouldBe true
  }

  "Witness in 4+1 cluster" should "provide odd quorum" in {
    val node3 = NodeId("node-3")
    val node4 = NodeId("node-4")

    val fiveNodeCluster = ClusterConfig(
      Map(
        node1 -> NodeRole.Voter,
        node2 -> NodeRole.Voter,
        node3 -> NodeRole.Voter,
        node4 -> NodeRole.Voter,
        witness1 -> NodeRole.Witness
      )
    )

    fiveNodeCluster.voters.size shouldBe 5
    fiveNodeCluster.majoritySize shouldBe 3

    fiveNodeCluster.hasQuorum(Set(node1, node2, witness1)) shouldBe true
    fiveNodeCluster.hasQuorum(Set(node3, node4, witness1)) shouldBe true
    fiveNodeCluster.hasQuorum(Set(node1, node2)) shouldBe false
  }

  "Mixed cluster" should "correctly handle voters, learners, and witnesses" in {
    val learner1 = NodeId("learner-1")

    val mixedCluster = ClusterConfig(
      Map(
        node1 -> NodeRole.Voter,
        node2 -> NodeRole.Voter,
        witness1 -> NodeRole.Witness,
        learner1 -> NodeRole.Learner
      )
    )

    mixedCluster.voters shouldBe Set(node1, node2, witness1)
    mixedCluster.learners shouldBe Set(learner1)
    mixedCluster.allNodes shouldBe Set(node1, node2, witness1, learner1)
    mixedCluster.majoritySize shouldBe 2
    mixedCluster.hasQuorum(Set(node1, learner1)) shouldBe false
    mixedCluster.hasQuorum(Set(node1, witness1)) shouldBe true
  }

  "Witness role" should "be addable to cluster" in {
    val simple = ClusterConfig(Set(node1, node2))
    val withWitness = simple.addMember(witness1, NodeRole.Witness)

    withWitness.voters should contain(witness1)
    withWitness.allNodes.size shouldBe 3
  }

  it should "be removable from cluster" in {
    val withoutWitness = clusterConfig.removeMember(witness1)

    withoutWitness.voters shouldBe Set(node1, node2)
    withoutWitness.majoritySize shouldBe 2
  }

  "Witness in election" should "be able to grant vote" in {
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

    val response = transition.effects.collectFirst {
      case SendMessage(_, resp: RequestVoteResponse) => resp
    }
    response shouldBe defined
    response.get.voteGranted shouldBe true
  }
