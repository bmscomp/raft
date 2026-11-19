package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.Effect.*

/** Pure log replication scenario tests for RaftLogic. */
class LogReplicationSpec extends AnyFlatSpec with Matchers:

  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")

  val config1 = RaftConfig.default(node1)
  val config2 = RaftConfig.default(node2)

  "Leader" should "include new entries in AppendEntries" in {
    val leaderState = Leader(
      term = 3,
      nextIndex = Map(node2 -> 1, node3 -> 1),
      matchIndex = Map(node2 -> 0, node3 -> 0)
    )

    val transition = RaftLogic.onMessage(
      leaderState,
      HeartbeatTimeout,
      config1,
      lastLogIndex = 5,
      lastLogTerm = 3,
      clusterSize = 3
    )

    val sentReqs = transition.effects.collect {
      case SendMessage(peer, msg: AppendEntriesRequest) => peer -> msg
    }.toMap

    sentReqs.keySet shouldBe Set(node2, node3)
    sentReqs(node2).term shouldBe 3
    sentReqs(node2).leaderId shouldBe node1
    sentReqs(node2).prevLogIndex shouldBe 0
    sentReqs(node3).term shouldBe 3
    sentReqs(node3).leaderId shouldBe node1
    sentReqs(node3).prevLogIndex shouldBe 0
  }

  "Follower" should "accept entries from valid leader" in {
    val followerState =
      Follower(term = 3, votedFor = None, leaderId = Some(node1))

    val appendReq = AppendEntriesRequest(
      term = 3,
      leaderId = node1,
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq(
        Log.command(1, 3, "cmd1".getBytes),
        Log.command(2, 3, "cmd2".getBytes)
      ),
      leaderCommit = 0
    )

    val transition = RaftLogic.onMessage(
      followerState,
      appendReq,
      config2,
      lastLogIndex = 0,
      lastLogTerm = 0,
      clusterSize = 3
    )

    val response = transition.effects.collectFirst {
      case SendMessage(_, msg: AppendEntriesResponse) => msg
    }

    response shouldBe defined
    response.get.success shouldBe true
    response.get.term shouldBe 3
    transition.effects should contain(ResetElectionTimer)
    transition.state.asInstanceOf[Follower].leaderId shouldBe Some(node1)
  }

  it should "update leader ID when receiving AppendEntries" in {
    val followerState = Follower(term = 3, votedFor = None, leaderId = None)

    val appendReq = AppendEntriesRequest(
      term = 3,
      leaderId = node1,
      prevLogIndex = 10,
      prevLogTerm = 2,
      entries = Seq.empty,
      leaderCommit = 5
    )

    val transition = RaftLogic.onMessage(
      followerState,
      appendReq,
      config2,
      lastLogIndex = 0,
      lastLogTerm = 0,
      clusterSize = 3
    )

    transition.state.asInstanceOf[Follower].leaderId shouldBe Some(node1)
  }

  "Follower receiving heartbeat" should "reset election timer" in {
    val followerState =
      Follower(term = 5, votedFor = None, leaderId = Some(node1))

    val heartbeat = AppendEntriesRequest(
      term = 5,
      leaderId = node1,
      prevLogIndex = 10,
      prevLogTerm = 5,
      entries = Seq.empty,
      leaderCommit = 8
    )

    val transition = RaftLogic.onMessage(
      followerState,
      heartbeat,
      config2,
      lastLogIndex = 10,
      lastLogTerm = 5,
      clusterSize = 3
    )

    transition.effects should contain(ResetElectionTimer)
  }

  "Follower" should "apply entries when leaderCommit advances" in {
    val followerState =
      Follower(term = 5, votedFor = None, leaderId = Some(node1))

    val appendReq = AppendEntriesRequest(
      term = 5,
      leaderId = node1,
      prevLogIndex = 10,
      prevLogTerm = 5,
      entries = Seq.empty,
      leaderCommit = 10
    )

    val transition = RaftLogic.onMessage(
      followerState,
      appendReq,
      config2,
      lastLogIndex = 10,
      lastLogTerm = 5,
      clusterSize = 3
    )

    transition.state shouldBe a[Follower]
    transition.effects should contain(ResetElectionTimer)
  }

  "Follower" should "step up to higher term from AppendEntries" in {
    val followerState = Follower(term = 1, votedFor = None, leaderId = None)

    val appendReq = AppendEntriesRequest(
      term = 3,
      leaderId = node1,
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq.empty,
      leaderCommit = 0
    )

    val transition = RaftLogic.onMessage(
      followerState,
      appendReq,
      config2,
      lastLogIndex = 0,
      lastLogTerm = 0,
      clusterSize = 3
    )

    transition.state.term shouldBe 3
    transition.state.asInstanceOf[Follower].leaderId shouldBe Some(node1)
  }
