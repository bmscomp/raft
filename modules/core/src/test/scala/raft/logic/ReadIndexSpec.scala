package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.Effect.*

/** End-to-end pure protocol tests for Linearizable Reads (`ReadIndex`, §6.4).
  */
class ReadIndexSpec extends AnyFlatSpec with Matchers:

  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")

  val cluster = ClusterConfig(Set(node1, node2, node3))
  val config = RaftConfig(localId = node1, preVoteEnabled = false)

  "ReadIndex on multi-node Leader" should "broadcast heartbeat confirmation and emit ReadIndexReady upon quorum ack" in {
    val leader = Leader(
      term = 5,
      nextIndex = Map(node2 -> 101L, node3 -> 101L),
      matchIndex = Map(node2 -> 100L, node3 -> 100L),
      commitIndex = 100L
    )

    val step1 = RaftLogic.onMessage(
      leader,
      ReadIndexRequest("read-123"),
      config,
      lastLogIndex = 100L,
      lastLogTerm = 5L,
      clusterSize = 3,
      getTermAt = { case 100L => Some(5L); case _ => None },
      clusterOpt = Some(cluster)
    )

    step1.effects should contain(ConfirmLeadership(5L, "read-123"))
    step1.state.asInstanceOf[Leader].pendingReads should contain key "read-123"

    // ReadIndex must send per-peer heartbeats anchored at each peer's nextIndex - 1 (100L)
    val peerHeartbeats = step1.effects.collect {
      case SendMessage(peer, hb: AppendEntriesRequest) => peer -> hb
    }.toMap
    peerHeartbeats.keySet shouldBe Set(node2, node3)
    peerHeartbeats(node2).prevLogIndex shouldBe 100L
    peerHeartbeats(node2).prevLogTerm shouldBe 5L
    peerHeartbeats(node2).leaderCommit shouldBe 100L

    // Delivering that ReadIndex heartbeat to follower node-2 verifies prefix and advances follower commit!
    val followerStep = RaftLogic.onMessage(
      Follower(term = 5, votedFor = Some(node1), leaderId = Some(node1)),
      peerHeartbeats(node2),
      RaftConfig(localId = node2, preVoteEnabled = false),
      lastLogIndex = 100L,
      lastLogTerm = 5L,
      clusterSize = 3,
      getTermAt = { case 100L => Some(5L); case _ => None }
    )
    followerStep.effects should contain(
      CommitEntries(LogIndex.unsafeFrom(100L))
    )
    val followerAck = followerStep.effects.collectFirst {
      case SendMessage(_, resp: AppendEntriesResponse) => resp
    }.get
    followerAck shouldBe AppendEntriesResponse(
      term = 5,
      success = true,
      matchIndex = 100L,
      heartbeatRound = peerHeartbeats(node2).heartbeatRound
    )
    followerAck.heartbeatRound should be > 0L

    // A stale pre-request response (heartbeatRound = 0) MUST NOT confirm ReadIndex!
    val staleStep = RaftLogic.onMessage(
      step1.state,
      AppendEntriesResponse(
        term = 5,
        success = true,
        matchIndex = 100L,
        heartbeatRound = 0L
      ),
      config,
      lastLogIndex = 100L,
      lastLogTerm = 5L,
      clusterSize = 3,
      getTermAt = { case 100L => Some(5L); case _ => None },
      sender = Some(node2),
      clusterOpt = Some(cluster)
    )
    staleStep.effects.collect { case r: ReadIndexReady => r } shouldBe empty
    staleStep.state
      .asInstanceOf[Leader]
      .pendingReads should contain key "read-123"

    // Peer node-2 acknowledges the post-request heartbeat round -> quorum {node-1, node-2} reached!
    val step2 = RaftLogic.onMessage(
      staleStep.state,
      followerAck,
      config,
      lastLogIndex = 100L,
      lastLogTerm = 5L,
      clusterSize = 3,
      getTermAt = { case 100L => Some(5L); case _ => None },
      sender = Some(node2),
      clusterOpt = Some(cluster)
    )

    step2.effects should contain(ReadIndexReady("read-123", 100L))
    step2.state.asInstanceOf[Leader].pendingReads should not contain key(
      "read-123"
    )
  }

  "ReadIndex on freshly elected Leader with commitIndex == 0 and uncommitted NoOp" should "reject until NoOp commits" in {
    val newLeader = Leader(
      term = 2,
      nextIndex = Map(node2 -> 2L, node3 -> 2L),
      matchIndex = Map(node2 -> 0L, node3 -> 0L),
      commitIndex = 0L
    )

    val trans = RaftLogic.onMessage(
      newLeader,
      ReadIndexRequest("before-noop"),
      config,
      lastLogIndex = 1L,
      lastLogTerm = 2L,
      clusterSize = 3,
      getTermAt = { case 1L => Some(2L); case _ => None },
      clusterOpt = Some(cluster)
    )

    trans.effects.collectFirst { case r: ReadIndexRejected =>
      r.id
    } shouldBe Some("before-noop")
  }

  "ReadIndex on single-node Leader" should "return ReadIndexReady immediately without network round-trip" in {
    val singleCluster = ClusterConfig(Set(node1))
    val leader = Leader(term = 5, commitIndex = 42L)

    val trans = RaftLogic.onMessage(
      leader,
      ReadIndexRequest("single-read"),
      config,
      lastLogIndex = 42L,
      lastLogTerm = 5L,
      clusterSize = 1,
      getTermAt = _ => Some(5L),
      clusterOpt = Some(singleCluster)
    )

    trans.effects should contain(ReadIndexReady("single-read", 42L))
  }

  "ReadIndex on Leader before current-term commit (§5.4.2)" should "reject until current-term entry commits" in {
    val leader = Leader(term = 5, commitIndex = 10L)

    val trans = RaftLogic.onMessage(
      leader,
      ReadIndexRequest("early-read"),
      config,
      lastLogIndex = 10L,
      lastLogTerm = 4L,
      clusterSize = 3,
      getTermAt = { case 10L => Some(4L); case _ => None },
      clusterOpt = Some(cluster)
    )

    val rejected = trans.effects.collectFirst { case r: ReadIndexRejected =>
      r
    }
    rejected shouldBe defined
    rejected.get.id shouldBe "early-read"
  }

  "ReadIndex on Follower" should "reject with known leader hint" in {
    val follower = Follower(term = 5, votedFor = None, leaderId = Some(node2))

    val trans = RaftLogic.onMessage(
      follower,
      ReadIndexRequest("read-456"),
      config,
      lastLogIndex = 10L,
      lastLogTerm = 5L,
      clusterSize = 3
    )

    val rejected = trans.effects.collectFirst { case r: ReadIndexRejected =>
      r
    }
    rejected shouldBe defined
    rejected.get.id shouldBe "read-456"
    rejected.get.leaderHint shouldBe Some(node2)
  }

  "ReadIndex on Candidate" should "reject during election" in {
    val candidate = Candidate(term = 5, votesReceived = Set(node1))

    val trans = RaftLogic.onMessage(
      candidate,
      ReadIndexRequest("read-abc"),
      config,
      lastLogIndex = 10L,
      lastLogTerm = 5L,
      clusterSize = 3
    )

    val rejected = trans.effects.collectFirst { case r: ReadIndexRejected =>
      r
    }
    rejected shouldBe defined
    rejected.get.id shouldBe "read-abc"
    rejected.get.leaderHint shouldBe None
  }
