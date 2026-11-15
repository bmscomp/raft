package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.Effect.*

/** End-to-end pure protocol tests for Leadership Transfer (§3.10). */
class LeadershipTransferSpec extends AnyFlatSpec with Matchers:

  val node1 = NodeId("node-1") // Current leader
  val node2 = NodeId("node-2") // Transfer target
  val node3 = NodeId("node-3")

  val cluster = ClusterConfig(Set(node1, node2, node3))
  val config1 = RaftConfig(
    localId = node1,
    preVoteEnabled = true,
    leaderStickinessEnabled = true
  )
  val config2 = RaftConfig(
    localId = node2,
    preVoteEnabled = true,
    leaderStickinessEnabled = true
  )
  val config3 = RaftConfig(
    localId = node3,
    preVoteEnabled = true,
    leaderStickinessEnabled = true
  )

  "Leadership transfer when target is caught up" should "immediately send TimeoutNowRequest" in {
    val leader = Leader(
      term = 5,
      nextIndex = Map(node2 -> 101L, node3 -> 101L),
      matchIndex = Map(node2 -> 100L, node3 -> 100L),
      commitIndex = 100L
    )

    val trans = RaftLogic.onMessage(
      leader,
      TransferLeadershipRequest(term = 5, target = node2),
      config1,
      lastLogIndex = 100L,
      lastLogTerm = 5L,
      clusterSize = 3,
      clusterOpt = Some(cluster)
    )

    trans.state.asInstanceOf[Leader].transferTarget shouldBe Some(node2)
    trans.effects should contain(TransferLeadership(node2))
    trans.effects should contain(TimeoutNow(node2))
    trans.effects should contain(
      SendMessage(node2, TimeoutNowRequest(term = 5, leaderId = node1))
    )
  }

  "Leadership transfer when target lags" should "probe target first and send TimeoutNow upon catch-up" in {
    val leader = Leader(
      term = 5,
      nextIndex = Map(node2 -> 96L, node3 -> 101L),
      matchIndex = Map(node2 -> 95L, node3 -> 100L),
      commitIndex = 100L
    )

    val step1 = RaftLogic.onMessage(
      leader,
      TransferLeadershipRequest(term = 5, target = node2),
      config1,
      lastLogIndex = 100L,
      lastLogTerm = 5L,
      clusterSize = 3,
      getTermAt = _ => Some(5L),
      clusterOpt = Some(cluster)
    )

    step1.state.asInstanceOf[Leader].transferTarget shouldBe Some(node2)
    step1.effects should not contain TimeoutNow(node2)

    val step2 = RaftLogic.onMessage(
      step1.state,
      AppendEntriesResponse(term = 5, success = true, matchIndex = 100L),
      config1,
      lastLogIndex = 100L,
      lastLogTerm = 5L,
      clusterSize = 3,
      getTermAt = _ => Some(5L),
      sender = Some(node2),
      clusterOpt = Some(cluster)
    )

    step2.state.asInstanceOf[Leader].transferTarget shouldBe None
    step2.effects should contain(TimeoutNow(node2))
    step2.effects should contain(
      SendMessage(node2, TimeoutNowRequest(term = 5, leaderId = node1))
    )
  }

  "TimeoutNowRequest on transferee" should "immediately start real election with isTransfer=true (bypassing Pre-Vote)" in {
    val targetFollower =
      Follower(term = 5, votedFor = None, leaderId = Some(node1))

    val trans = RaftLogic.onMessage(
      targetFollower,
      TimeoutNowRequest(term = 5, leaderId = node1),
      config2,
      lastLogIndex = 100L,
      lastLogTerm = 5L,
      clusterSize = 3,
      clusterOpt = Some(cluster)
    )

    trans.state shouldBe Candidate(term = 6, votesReceived = Set(node2))
    val broadcastVote = trans.effects.collectFirst {
      case Broadcast(rv: RequestVoteRequest) => rv
    }
    broadcastVote shouldBe defined
    broadcastVote.get.isPreVote shouldBe false
    broadcastVote.get.isTransfer shouldBe true
    broadcastVote.get.term shouldBe 6L
  }

  "Leadership transfer on Follower, self, or non-voter" should "be ignored safely" in {
    val follower = Follower(term = 5, votedFor = None, leaderId = Some(node1))
    val fTrans = RaftLogic.onMessage(
      follower,
      TransferLeadershipRequest(5, node2),
      config2,
      10L,
      5L,
      3,
      clusterOpt = Some(cluster)
    )
    fTrans.effects shouldBe empty

    val leader = Leader(term = 5)
    val selfTrans = RaftLogic.onMessage(
      leader,
      TransferLeadershipRequest(5, node1),
      config1,
      10L,
      5L,
      3,
      clusterOpt = Some(cluster)
    )
    selfTrans.effects shouldBe empty

    val unknownTrans = RaftLogic.onMessage(
      leader,
      TransferLeadershipRequest(5, NodeId("unknown")),
      config1,
      10L,
      5L,
      3,
      clusterOpt = Some(cluster)
    )
    unknownTrans.effects shouldBe empty
  }
