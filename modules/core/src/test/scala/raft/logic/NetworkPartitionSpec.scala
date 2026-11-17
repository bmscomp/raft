package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.Effect.*

/** Pure scenario tests for network partitions, healing, and leader failure. */
class NetworkPartitionSpec extends AnyFlatSpec with Matchers:

  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")
  val node4 = NodeId("node-4")
  val node5 = NodeId("node-5")

  val config1 = RaftConfig.default(node1).copy(preVoteEnabled = false)
  val config2 = RaftConfig.default(node2).copy(preVoteEnabled = false)
  val config3 = RaftConfig.default(node3)

  "Network partition" should "prevent isolated minority from electing leader" in {
    val state4 = Follower(term = 1, votedFor = None, leaderId = None)
    val config4 = RaftConfig.default(node4).copy(preVoteEnabled = false)

    val trans4 = RaftLogic.onMessage(state4, ElectionTimeout, config4, 0, 0, 5)
    trans4.state shouldBe a[Candidate]
    trans4.state.term shouldBe 2

    val candidate4 = trans4.state.asInstanceOf[Candidate]

    val voteResp5: RequestVoteResponse =
      RequestVoteResponse(term = 2, voteGranted = true, isPreVote = false)
    val afterVote =
      RaftLogic.onVoteResponse(candidate4, node5, voteResp5, config4, 5)

    afterVote.state shouldBe a[Candidate]
    afterVote.state.asInstanceOf[Candidate].votesReceived shouldBe Set(
      node4,
      node5
    )
    afterVote.effects should not contain BecomeLeader
  }

  it should "allow majority partition to elect new leader" in {
    var state1: NodeState = Follower(term = 5, votedFor = None, leaderId = None)

    val trans1 = RaftLogic.onMessage(state1, ElectionTimeout, config1, 0, 0, 5)
    state1 = trans1.state
    state1 shouldBe a[Candidate]
    state1.term shouldBe 6

    val candidate1 = state1.asInstanceOf[Candidate]

    val voteResp2: RequestVoteResponse =
      RequestVoteResponse(term = 6, voteGranted = true, isPreVote = false)
    val afterVote2 =
      RaftLogic.onVoteResponse(candidate1, node2, voteResp2, config1, 5)
    afterVote2.state shouldBe a[Candidate]

    val candidate1b = afterVote2.state.asInstanceOf[Candidate]
    val voteResp3: RequestVoteResponse =
      RequestVoteResponse(term = 6, voteGranted = true, isPreVote = false)
    val afterVote3 =
      RaftLogic.onVoteResponse(candidate1b, node3, voteResp3, config1, 5)

    afterVote3.state shouldBe a[Leader]
    afterVote3.effects should contain(BecomeLeader)
  }

  "Partition healing" should "make stale leader step down" in {
    val staleLeader =
      Leader(term = 5, nextIndex = Map.empty, matchIndex = Map.empty)

    val newLeaderHeartbeat = AppendEntriesRequest(
      term = 10,
      leaderId = node2,
      prevLogIndex = 100,
      prevLogTerm = 9,
      entries = Seq.empty,
      leaderCommit = 90
    )

    val transition =
      RaftLogic.onMessage(staleLeader, newLeaderHeartbeat, config1, 50, 5, 3)

    transition.state shouldBe a[Follower]
    transition.state.term shouldBe 10
    transition.state.asInstanceOf[Follower].leaderId shouldBe Some(node2)
  }

  it should "reject stale leader's requests" in {
    val follower =
      Follower(term = 10, votedFor = Some(node1), leaderId = Some(node1))

    val staleRequest: AppendEntriesRequest = AppendEntriesRequest(
      term = 5,
      leaderId = node2,
      prevLogIndex = 50,
      prevLogTerm = 5,
      entries = Seq.empty,
      leaderCommit = 40
    )

    val transition =
      RaftLogic.onMessage(follower, staleRequest, config1, 100, 10, 3)

    transition.state shouldBe a[Follower]
    transition.state.term shouldBe 10

    val response = transition.effects.collectFirst {
      case SendMessage(_, msg: AppendEntriesResponse) => msg
    }
    response shouldBe defined
    response.get.success shouldBe false
    response.get.term shouldBe 10
  }

/** Pure scenario tests for leader failure and automatic recovery. */
class LeaderFailureSpec extends AnyFlatSpec with Matchers:

  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")

  val config1 = RaftConfig.default(node1)
  val config2 = RaftConfig.default(node2)
  val config3 = RaftConfig.default(node3)

  "Follower after leader failure" should "start election on timeout" in {
    val follower2 =
      Follower(term = 5, votedFor = Some(node1), leaderId = Some(node1))

    val transition = RaftLogic.onMessage(
      follower2,
      ElectionTimeout,
      config2.copy(preVoteEnabled = false),
      10,
      5,
      3
    )

    transition.state shouldBe a[Candidate]
    transition.state.term shouldBe 6
    transition.state.asInstanceOf[Candidate].votesReceived should contain(node2)
  }

  "Multiple candidates" should "result in only one leader" in {
    val candidate1 = Candidate(term = 6, votesReceived = Set(node1))

    val voteReq1 = RequestVoteRequest(
      term = 6,
      candidateId = node1,
      lastLogIndex = 10,
      lastLogTerm = 5,
      isPreVote = false
    )

    val follower3 = Follower(term = 5, votedFor = None, leaderId = None)
    val trans3 = RaftLogic.onMessage(follower3, voteReq1, config3, 10, 5, 3)

    val response = trans3.effects.collectFirst {
      case SendMessage(_, msg: RequestVoteResponse) => msg
    }
    response.get.voteGranted shouldBe true

    val voteResp3: RequestVoteResponse =
      RequestVoteResponse(term = 6, voteGranted = true, isPreVote = false)
    val afterWin =
      RaftLogic.onVoteResponse(candidate1, node3, voteResp3, config1, 3)
    afterWin.state shouldBe a[Leader]

    val voteReq2 = RequestVoteRequest(
      term = 6,
      candidateId = node2,
      lastLogIndex = 10,
      lastLogTerm = 5,
      isPreVote = false
    )

    val trans3b = RaftLogic.onMessage(trans3.state, voteReq2, config3, 10, 5, 3)
    val response2 = trans3b.effects.collectFirst {
      case SendMessage(_, msg: RequestVoteResponse) => msg
    }
    response2.get.voteGranted shouldBe false
  }

  "Leader" should "step down if encountering higher term" in {
    val leader =
      Leader(term = 5, nextIndex = Map(node2 -> 100), matchIndex = Map.empty)

    val higherTermVote = RequestVoteRequest(
      term = 10,
      candidateId = node2,
      lastLogIndex = 150,
      lastLogTerm = 9,
      isPreVote = false
    )

    val transition =
      RaftLogic.onMessage(leader, higherTermVote, config1, 100, 5, 3)

    transition.state shouldBe a[Follower]
    transition.state.term shouldBe 10
  }
