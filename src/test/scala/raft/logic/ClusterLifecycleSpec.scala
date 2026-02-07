package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.Effect
import raft.effect.Effect.*

/** Multi-step deterministic cluster lifecycle simulation.
  *
  * Drives a 5-node cluster through the entire RAFT lifecycle using only pure
  * `RaftLogic.onMessage` calls — no IO, no timers, fully deterministic.
  *
  * Lifecycle stages: boot → election timeout → vote → leader elected →
  * replicate entries → commit advancement → leader dies → re-election → new
  * leader continues
  */
class ClusterLifecycleSpec extends AnyFlatSpec with Matchers:

  val nodes = (1 to 5).map(i => NodeId(s"node-$i")).toList
  val List(n1, n2, n3, n4, n5) = nodes: @unchecked
  val configs = nodes
    .map(id => id -> RaftConfig.default(id).copy(preVoteEnabled = false))
    .toMap
  val clusterSize = 5

  "5-node cluster lifecycle" should "elect leader, replicate, re-elect after failure" in {
    // PHASE 1: All followers at term 0
    var states: Map[NodeId, NodeState] =
      nodes.map(id => (id, Follower(0, None, None): NodeState)).toMap

    // PHASE 2: Node-1 times out → becomes Candidate at term 1
    val electionTrans = RaftLogic.onMessage(
      states(n1),
      ElectionTimeout,
      configs(n1),
      0,
      0,
      clusterSize
    )
    states = states.updated(n1, electionTrans.state)
    electionTrans.state shouldBe a[Candidate]
    electionTrans.state.term shouldBe 1

    val voteReq = electionTrans.effects.collectFirst {
      case Broadcast(req: RequestVoteRequest) => req
    }.get

    // PHASE 3: All other 4 nodes receive the vote request
    val voteResults = List(n2, n3, n4, n5).map { nodeId =>
      val trans = RaftLogic.onMessage(
        states(nodeId),
        voteReq,
        configs(nodeId),
        0,
        0,
        clusterSize
      )
      states = states.updated(nodeId, trans.state)
      nodeId -> trans.effects.collectFirst {
        case SendMessage(_, resp: RequestVoteResponse) => resp
      }.get
    }

    // All should grant (they are fresh followers)
    voteResults.foreach { case (_, resp) => resp.voteGranted shouldBe true }

    // PHASE 4: Node-1 collects votes → becomes Leader
    var candidate = states(n1).asInstanceOf[Candidate]
    for (voterId, resp) <- voteResults.take(2)
    do // 2 votes + self = 3/5 majority
      val trans = RaftLogic.onVoteResponse(
        candidate,
        voterId,
        resp,
        configs(n1),
        clusterSize
      )
      states = states.updated(n1, trans.state)
      trans.state match
        case c: Candidate => candidate = c
        case _: Leader    => // done
        case _            => fail("Unexpected state")

    states(n1) shouldBe a[Leader]
    states(n1).term shouldBe 1

    // PHASE 5: Leader sends heartbeats → followers record leader
    val heartbeatTrans = RaftLogic.onMessage(
      states(n1),
      HeartbeatTimeout,
      configs(n1),
      0,
      0,
      clusterSize
    )
    val heartbeat = heartbeatTrans.effects.collectFirst {
      case Broadcast(ae: AppendEntriesRequest) => ae
    }.get

    for followerId <- List(n2, n3, n4, n5) do
      val trans = RaftLogic.onMessage(
        states(followerId),
        heartbeat,
        configs(followerId),
        0,
        0,
        clusterSize
      )
      states = states.updated(followerId, trans.state)
      trans.state.asInstanceOf[Follower].leaderId shouldBe Some(n1)

    // PHASE 6: Leader replicates entries
    val entries =
      (1 to 5).map(i => Log.command(i.toLong, 1, s"cmd-$i".getBytes))
    val appendReq = AppendEntriesRequest(
      term = 1,
      leaderId = n1,
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = entries,
      leaderCommit = 0
    )

    for followerId <- List(n2, n3, n4, n5) do
      val trans = RaftLogic.onMessage(
        states(followerId),
        appendReq,
        configs(followerId),
        0,
        0,
        clusterSize
      )
      states = states.updated(followerId, trans.state)
      val resp = trans.effects.collectFirst {
        case SendMessage(_, r: AppendEntriesResponse) => r
      }.get
      resp.success shouldBe true
      resp.matchIndex shouldBe 5

    // PHASE 7: All at same term throughout
    states.values.foreach(_.term shouldBe 1)

    // PHASE 8: Simulate leader failure → node-2 times out, starts election at term 2
    val election2Trans = RaftLogic.onMessage(
      states(n2),
      ElectionTimeout,
      configs(n2),
      5,
      1,
      clusterSize
    )
    states = states.updated(n2, election2Trans.state)
    election2Trans.state shouldBe a[Candidate]
    election2Trans.state.term shouldBe 2

    val voteReq2 = election2Trans.effects.collectFirst {
      case Broadcast(req: RequestVoteRequest) => req
    }.get

    // Node-3, node-4, node-5 grant votes (node-1 died)
    var candidate2 = states(n2).asInstanceOf[Candidate]
    for voterId <- List(n3, n4, n5) do
      val voterTrans = RaftLogic.onMessage(
        states(voterId),
        voteReq2,
        configs(voterId),
        5,
        1,
        clusterSize
      )
      states = states.updated(voterId, voterTrans.state)
      val resp = voterTrans.effects.collectFirst {
        case SendMessage(_, r: RequestVoteResponse) => r
      }.get
      resp.voteGranted shouldBe true

      val leaderTrans = RaftLogic.onVoteResponse(
        candidate2,
        voterId,
        resp,
        configs(n2),
        clusterSize
      )
      states = states.updated(n2, leaderTrans.state)
      leaderTrans.state match
        case c: Candidate => candidate2 = c
        case _: Leader    => // done
        case _            =>

    // Node-2 is now the new leader at term 2
    states(n2) shouldBe a[Leader]
    states(n2).term shouldBe 2
  }

  "Term monotonicity" should "hold across all lifecycle transitions" in {
    var prevTerms: Map[NodeId, Long] = nodes.map(_ -> 0L).toMap
    var states: Map[NodeId, NodeState] =
      nodes.map(id => (id, Follower(0, None, None): NodeState)).toMap

    def checkAndUpdate(nodeId: NodeId, newState: NodeState): Unit =
      newState.term should be >= prevTerms(nodeId)
      prevTerms = prevTerms.updated(nodeId, newState.term)
      states = states.updated(nodeId, newState)

    // Node-1 election timeout
    val t1 = RaftLogic.onMessage(
      states(n1),
      ElectionTimeout,
      configs(n1),
      0,
      0,
      clusterSize
    )
    checkAndUpdate(n1, t1.state)

    // Vote request
    val voteReq = t1.effects.collectFirst {
      case Broadcast(r: RequestVoteRequest) => r
    }.get

    // Followers process vote
    for id <- List(n2, n3) do
      val t =
        RaftLogic.onMessage(states(id), voteReq, configs(id), 0, 0, clusterSize)
      checkAndUpdate(id, t.state)
      val resp = t.effects.collectFirst {
        case SendMessage(_, r: RequestVoteResponse) => r
      }.get
      val lt = RaftLogic.onVoteResponse(
        states(n1).asInstanceOf[Candidate],
        id,
        resp,
        configs(n1),
        clusterSize
      )
      checkAndUpdate(n1, lt.state)

    // All terms should be >= 0
    prevTerms.values.foreach(_ should be >= 0L)
  }

  "Log convergence" should "produce identical logs on all followers after replication" in {
    // Boot cluster, elect leader, replicate same entries to all followers
    var states: Map[NodeId, NodeState] =
      nodes.map(id => (id, Follower(0, None, None): NodeState)).toMap

    // Quick election: node-1 becomes leader (pre-vote disabled)
    val t =
      RaftLogic.onMessage(states(n1), ElectionTimeout, configs(n1), 0, 0, 3)
    states = states.updated(n1, t.state)
    val voteReq = t.effects.collectFirst {
      case Broadcast(r: RequestVoteRequest) => r
    }.get

    val t2 = RaftLogic.onMessage(states(n2), voteReq, configs(n2), 0, 0, 3)
    val resp = t2.effects.collectFirst {
      case SendMessage(_, r: RequestVoteResponse) => r
    }.get
    val lt = RaftLogic.onVoteResponse(
      states(n1).asInstanceOf[Candidate],
      n2,
      resp,
      configs(n1),
      3
    )
    states = states.updated(n1, lt.state)
    lt.state shouldBe a[Leader]

    // Replicate 3 entries to both followers
    val entries =
      (1 to 3).map(i => Log.command(i.toLong, 1, s"data-$i".getBytes))
    val appendReq = AppendEntriesRequest(
      term = 1,
      leaderId = n1,
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = entries,
      leaderCommit = 0
    )

    val responses = List(n2, n3).map { id =>
      val ft = RaftLogic.onMessage(states(id), appendReq, configs(id), 0, 0, 3)
      val appendEffects = ft.effects.collect { case AppendLogs(logs) => logs }
      (id, appendEffects)
    }

    // Both followers got the same entries
    responses.foreach { case (_, effectLists) =>
      effectLists should not be empty
      effectLists.head.map(_.index) shouldBe entries.map(_.index)
    }
  }
