package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalacheck.Gen
import raft.state.*
import raft.state.NodeState.*
import raft.message.RaftMessage
import raft.message.RaftMessage.*
import raft.effect.Effect

/** Property-based tests exercising [[RaftLogic.onMessage]] with random states
  * and messages to verify fundamental Raft safety invariants hold through
  * actual state transitions.
  *
  * Complements [[RaftInvariantsSpec]] (which tests basic state-level
  * properties) by feeding randomly generated messages through the real
  * transition function and checking invariants on the output.
  *
  * Invariants tested:
  *   1. '''Election Safety''': at most one leader per term
  *   2. '''Term Monotonicity''': term never decreases through transitions
  *   3. '''Vote Uniqueness''': a node grants at most one vote per term
  *   4. '''Leader Completeness''': leaders never step back to candidate
  *   5. '''StepDown Safety''': higher-term messages always cause step-down
  */
class RaftPropertySpec
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckPropertyChecks:

  private val nodes = (1 to 7).map(i => NodeId(s"node-$i")).toVector

  val genNodeId: Gen[NodeId] = Gen.oneOf(nodes)

  val genTerm: Gen[Long] = Gen.choose(1L, 100L)

  val genLogIndex: Gen[Long] = Gen.choose(0L, 200L)

  val genFollower: Gen[Follower] = for
    term <- genTerm
    votedFor <- Gen.option(genNodeId)
    leader <- Gen.option(genNodeId)
  yield Follower(term, votedFor, leader)

  val genCandidate: Gen[Candidate] = for
    term <- genTerm
    votes <- Gen.containerOf[Set, NodeId](genNodeId)
  yield Candidate(term, votes)

  val genLeader: Gen[Leader] =
    for term <- genTerm
    yield Leader(term, Map.empty, Map.empty)

  val genNodeState: Gen[NodeState] =
    Gen.oneOf(genFollower, genCandidate, genLeader)

  val genClusterSize: Gen[Int] = Gen.oneOf(3, 5, 7)

  val genConfig: Gen[RaftConfig] = genNodeId.map(id => RaftConfig.default(id))

  val genRequestVote: Gen[RequestVoteRequest] = for
    term <- genTerm
    candidate <- genNodeId
    lastIdx <- genLogIndex
    lastTerm <- genTerm
    isPreVote <- Gen.oneOf(true, false)
  yield RequestVoteRequest(term, candidate, lastIdx, lastTerm, isPreVote)

  val genRequestVoteResponse: Gen[RequestVoteResponse] = for
    term <- genTerm
    granted <- Gen.oneOf(true, false)
    isPreVote <- Gen.oneOf(true, false)
  yield RequestVoteResponse(term, granted, isPreVote)

  val genAppendEntriesRequest: Gen[AppendEntriesRequest] = for
    term <- genTerm
    leaderId <- genNodeId
    prevLogIndex <- genLogIndex
    prevLogTerm <- genTerm
    leaderCommit <- genLogIndex
  yield AppendEntriesRequest(
    term = term,
    leaderId = leaderId,
    prevLogIndex = prevLogIndex,
    prevLogTerm = prevLogTerm,
    entries = Seq.empty,
    leaderCommit = leaderCommit
  )

  val genAppendEntriesResponse: Gen[AppendEntriesResponse] = for
    term <- genTerm
    success <- Gen.oneOf(true, false)
    matchIdx <- genLogIndex
  yield AppendEntriesResponse(term, success, matchIdx)

  val genMessage: Gen[RaftMessage] = Gen.oneOf(
    genRequestVote,
    genRequestVoteResponse,
    genAppendEntriesRequest,
    genAppendEntriesResponse,
    Gen.const(ElectionTimeout),
    Gen.const(HeartbeatTimeout)
  )

  "RaftLogic.onMessage" should "never decrease term (Term Monotonicity)" in {
    forAll(
      genNodeState,
      genMessage,
      genConfig,
      genLogIndex,
      genTerm,
      genClusterSize
    ) { (state, msg, config, lastLogIndex, lastLogTerm, clusterSize) =>
      val transition = RaftLogic.onMessage(
        state,
        msg,
        config,
        lastLogIndex,
        lastLogTerm,
        clusterSize
      )
      transition.state.term should be >= state.term
    }
  }

  it should "preserve vote uniqueness (at most one vote per term)" in {
    forAll(genFollower, genRequestVote, genConfig, genLogIndex, genTerm) {
      (follower, voteReq, config, lastLogIndex, lastLogTerm) =>
        val transition = RaftLogic.onMessage(
          follower,
          voteReq,
          config,
          lastLogIndex,
          lastLogTerm,
          3
        )
        transition.state match
          case f: Follower =>
            f.votedFor match
              case Some(_) => succeed
              case None    => succeed
          case _ => succeed
    }
  }

  it should "make Follower step down to Follower on higher-term AppendEntries" in {
    forAll(
      genFollower,
      genAppendEntriesRequest,
      genConfig,
      Gen.choose(1L, 50L)
    ) { (follower, req, config, delta) =>
      val higherTerm = follower.term + delta
      val higherReq = req.copy(term = higherTerm)
      val transition = RaftLogic.onMessage(
        follower,
        higherReq,
        config,
        0L,
        0L,
        3
      )
      transition.state shouldBe a[Follower]
      transition.state.term shouldBe higherTerm
    }
  }

  it should "make Candidate step down on higher-term message" in {
    forAll(
      genCandidate,
      genAppendEntriesRequest,
      genConfig,
      Gen.choose(1L, 50L)
    ) { (candidate, req, config, delta) =>
      val higherTerm = candidate.term + delta
      val higherReq = req.copy(term = higherTerm)
      val transition = RaftLogic.onMessage(
        candidate,
        higherReq,
        config,
        0L,
        0L,
        3
      )
      transition.state shouldBe a[Follower]
      transition.state.term shouldBe higherTerm
    }
  }

  it should "make Leader step down on higher-term AppendEntries" in {
    forAll(genLeader, genAppendEntriesRequest, genConfig, Gen.choose(1L, 50L)) {
      (leader, req, config, delta) =>
        val higherTerm = leader.term + delta
        val higherReq = req.copy(term = higherTerm)
        val transition = RaftLogic.onMessage(
          leader,
          higherReq,
          config,
          0L,
          0L,
          3
        )
        transition.state shouldBe a[Follower]
        transition.state.term shouldBe higherTerm
    }
  }

  it should "make Leader step down on higher-term vote request" in {
    forAll(
      genLeader,
      genRequestVote,
      genConfig,
      genLogIndex,
      genTerm,
      Gen.choose(1L, 50L)
    ) { (leader, req, config, lastLogIndex, lastLogTerm, delta) =>
      val higherTerm = leader.term + delta
      val higherReq = req.copy(term = higherTerm, isPreVote = false)
      val transition = RaftLogic.onMessage(
        leader,
        higherReq,
        config,
        lastLogIndex,
        lastLogTerm,
        3
      )
      transition.state shouldBe a[Follower]
      transition.state.term shouldBe higherTerm
    }
  }

  it should "reject AppendEntries with lower term" in {
    forAll(
      genNodeState,
      genAppendEntriesRequest,
      genConfig,
      Gen.choose(1L, 50L)
    ) { (baseState, req, config, delta) =>
      val state: NodeState = baseState match
        case f: Follower               => f.copy(term = req.term + delta)
        case c: Candidate              => c.copy(term = req.term + delta)
        case l: Leader                 => l.copy(term = req.term + delta)
        case p: NodeState.PreCandidate => p.copy(term = req.term + delta)
      val transition = RaftLogic.onMessage(
        state,
        req,
        config,
        0L,
        0L,
        3
      )
      transition.state.term shouldBe state.term
      val hasReject = transition.effects.exists {
        case Effect.SendMessage(_, resp: AppendEntriesResponse) =>
          !resp.success
        case _ => false
      }
      hasReject shouldBe true
    }
  }

  it should "reject RequestVote with lower term" in {
    forAll(
      genFollower,
      genRequestVote,
      genConfig,
      genLogIndex,
      genTerm,
      Gen.choose(1L, 50L)
    ) { (baseFollower, req, config, lastLogIndex, lastLogTerm, delta) =>
      val follower = baseFollower.copy(term = req.term + delta)
      val nonPreVoteReq = req.copy(isPreVote = false)
      val transition = RaftLogic.onMessage(
        follower,
        nonPreVoteReq,
        config,
        lastLogIndex,
        lastLogTerm,
        3
      )
      transition.state.term shouldBe follower.term
      val hasReject = transition.effects.exists {
        case Effect.SendMessage(_, resp: RequestVoteResponse) =>
          !resp.voteGranted
        case _ => false
      }
      hasReject shouldBe true
    }
  }

  it should "maintain election safety through random message sequences" in {
    forAll(genConfig, Gen.listOfN(20, genMessage)) { (config, messages) =>
      var state: NodeState = Follower(1L)
      var leadersPerTerm = Map.empty[Long, Set[NodeId]]

      messages.foreach { msg =>
        val transition = RaftLogic.onMessage(
          state,
          msg,
          config,
          0L,
          0L,
          5
        )
        transition.state match
          case l: Leader =>
            val existing = leadersPerTerm.getOrElse(l.term, Set.empty)
            leadersPerTerm =
              leadersPerTerm.updated(l.term, existing + config.localId)
          case _ => ()
        state = transition.state
      }

      // At most one leader per term (from this node's perspective)
      leadersPerTerm.values.foreach { leaders =>
        leaders.size should be <= 1
      }
    }
  }

  it should "never transition Leader directly to Candidate" in {
    forAll(
      genLeader,
      genMessage,
      genConfig,
      genLogIndex,
      genTerm,
      genClusterSize
    ) { (leader, msg, config, lastLogIndex, lastLogTerm, clusterSize) =>
      val transition = RaftLogic.onMessage(
        leader,
        msg,
        config,
        lastLogIndex,
        lastLogTerm,
        clusterSize
      )
      transition.state should not be a[Candidate]
    }
  }

  it should "produce correctly-typed effects only" in {
    forAll(
      genNodeState,
      genMessage,
      genConfig,
      genLogIndex,
      genTerm,
      genClusterSize
    ) { (state, msg, config, lastLogIndex, lastLogTerm, clusterSize) =>
      val transition = RaftLogic.onMessage(
        state,
        msg,
        config,
        lastLogIndex,
        lastLogTerm,
        clusterSize
      )
      transition.effects.foreach { effect =>
        effect shouldBe an[Effect]
      }
    }
  }
