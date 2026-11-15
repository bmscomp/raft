package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.Effect.*

/** Pure multi-node election and split-vote scenario tests for RaftLogic. */
class LeaderElectionSpec extends AnyFlatSpec with Matchers:

  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")

  val config1 = RaftConfig.default(node1).copy(preVoteEnabled = false)
  val config2 = RaftConfig.default(node2)
  val config3 = RaftConfig.default(node3)

  "3-node cluster election" should "elect leader when one node times out first" in {
    var state1: NodeState = Follower(term = 0)
    var state2: NodeState = Follower(term = 0)
    var state3: NodeState = Follower(term = 0)

    val trans1 = RaftLogic.onMessage(state1, ElectionTimeout, config1, 0, 0, 3)
    state1 = trans1.state

    state1 shouldBe a[Candidate]
    state1.term shouldBe 1
    state1.asInstanceOf[Candidate].votesReceived should contain(node1)

    val voteReq = trans1.effects.collectFirst {
      case Broadcast(msg: RequestVoteRequest) => msg
    }.get

    voteReq.term shouldBe 1
    voteReq.candidateId shouldBe node1

    val trans2 = RaftLogic.onMessage(state2, voteReq, config2, 0, 0, 3)
    val trans3 = RaftLogic.onMessage(state3, voteReq, config3, 0, 0, 3)

    state2 = trans2.state
    state3 = trans3.state

    val resp2 = trans2.effects.collectFirst {
      case SendMessage(_, msg: RequestVoteResponse) => msg
    }.get
    val resp3 = trans3.effects.collectFirst {
      case SendMessage(_, msg: RequestVoteResponse) => msg
    }.get

    resp2.voteGranted shouldBe true
    resp3.voteGranted shouldBe true
    state2.term shouldBe 1
    state3.term shouldBe 1

    val transVote1 = RaftLogic.onVoteResponse(
      state1.asInstanceOf[Candidate],
      node2,
      resp2,
      config1,
      3
    )
    state1 = transVote1.state

    state1 shouldBe a[Leader]
    state1.term shouldBe 1
    transVote1.effects should contain(BecomeLeader)

    val heartbeatTrans =
      RaftLogic.onMessage(state1, HeartbeatTimeout, config1, 0, 0, 3)

    val heartbeat = heartbeatTrans.effects.collectFirst {
      case Broadcast(msg: AppendEntriesRequest) => msg
    }.get

    heartbeat.term shouldBe 1
    heartbeat.leaderId shouldBe node1
    heartbeat.entries shouldBe empty

    val trans2hb = RaftLogic.onMessage(state2, heartbeat, config2, 0, 0, 3)
    val trans3hb = RaftLogic.onMessage(state3, heartbeat, config3, 0, 0, 3)

    state2 = trans2hb.state
    state3 = trans3hb.state

    state2.asInstanceOf[Follower].leaderId shouldBe Some(node1)
    state3.asInstanceOf[Follower].leaderId shouldBe Some(node1)

    state1 shouldBe a[Leader]
    state2 shouldBe a[Follower]
    state3 shouldBe a[Follower]
    state1.term shouldBe state2.term
    state2.term shouldBe state3.term
  }

  "Split vote" should "result in new election with higher term" in {
    var state1: NodeState = Follower(term = 0)
    var state2: NodeState = Follower(term = 0)
    var state3: NodeState = Follower(term = 0)

    val trans1 = RaftLogic.onMessage(state1, ElectionTimeout, config1, 0, 0, 3)
    val trans2 = RaftLogic.onMessage(
      state2,
      ElectionTimeout,
      config2.copy(preVoteEnabled = false),
      0,
      0,
      3
    )

    state1 = trans1.state
    state2 = trans2.state

    state1 shouldBe a[Candidate]
    state2 shouldBe a[Candidate]
    state1.term shouldBe 1
    state2.term shouldBe 1

    val voteReq1 = trans1.effects.collectFirst {
      case Broadcast(msg: RequestVoteRequest) => msg
    }.get

    val trans3 = RaftLogic.onMessage(state3, voteReq1, config3, 0, 0, 3)
    state3 = trans3.state
    state3.asInstanceOf[Follower].votedFor shouldBe Some(node1)

    val voteReq2 = trans2.effects.collectFirst {
      case Broadcast(msg: RequestVoteRequest) => msg
    }.get

    val trans3b = RaftLogic.onMessage(state3, voteReq2, config3, 0, 0, 3)
    val resp3b = trans3b.effects.collectFirst {
      case SendMessage(_, msg: RequestVoteResponse) => msg
    }.get

    resp3b.voteGranted shouldBe false

    val voteResp3 = trans3.effects.collectFirst {
      case SendMessage(_, msg: RequestVoteResponse) => msg
    }.get

    val transLead = RaftLogic.onVoteResponse(
      state1.asInstanceOf[Candidate],
      node3,
      voteResp3,
      config1,
      3
    )

    transLead.state shouldBe a[Leader]

    val transTimeout = RaftLogic.onMessage(
      state2,
      ElectionTimeout,
      config2.copy(preVoteEnabled = false),
      0,
      0,
      3
    )

    transTimeout.state shouldBe a[Candidate]
    transTimeout.state.term shouldBe 2
  }

  "Leader discovering higher term" should "step down immediately" in {
    var leaderState: NodeState = Leader(term = 5)

    val higherTermMsg = AppendEntriesRequest(
      term = 10,
      leaderId = node2,
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq.empty,
      leaderCommit = 0
    )

    val trans =
      RaftLogic.onMessage(leaderState, higherTermMsg, config1, 0, 0, 3)
    leaderState = trans.state

    leaderState shouldBe a[Follower]
    leaderState.term shouldBe 10
    leaderState.asInstanceOf[Follower].leaderId shouldBe Some(node2)
  }
