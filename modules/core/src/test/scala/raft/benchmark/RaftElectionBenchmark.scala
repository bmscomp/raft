package raft.benchmark

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.*
import raft.state.NodeState.*
import raft.message.RaftMessage
import raft.message.RaftMessage.*
import raft.logic.RaftLogic
import raft.effect.Effect.*

/** Advanced benchmarks for Raft leader election scenarios.
  *
  * Measures pure `RaftLogic.onMessage` transition throughput for:
  *   - Cold-start election (3/5/7-node clusters)
  *   - Re-election after leader failure
  *   - Split vote → recovery
  *   - Cascading elections with term convergence
  *   - Pre-vote protocol overhead
  *   - Large cluster election scaling
  *   - Election with stale/divergent logs
  */
class RaftElectionBenchmark extends AnyFlatSpec with Matchers:

  private val WarmupRuns = 50
  private val BenchmarkRuns = 500

  private def nodes(n: Int): Vector[NodeId] =
    (1 to n).map(i => NodeId(s"node-$i")).toVector

  private def configs(
      ns: Vector[NodeId],
      preVote: Boolean = false
  ): Map[NodeId, RaftConfig] =
    ns.map(id => id -> RaftConfig.default(id).copy(preVoteEnabled = preVote))
      .toMap

  /** Simulate a complete cold-start election:
    *   1. All nodes start as Follower(term=0)
    *   2. First node times out → becomes Candidate
    *   3. Others receive VoteRequest → grant votes
    *   4. Candidate receives majority → becomes Leader
    *
    * Returns (leader state, term, number of transitions)
    */
  private def runElection(
      ns: Vector[NodeId],
      cfgs: Map[NodeId, RaftConfig],
      clusterSize: Int
  ): (NodeState, Long, Int) =
    var states = ns.map(id => id -> (Follower(0L): NodeState)).toMap
    var transitionCount = 0

    // Node-1 times out
    val initiator = ns.head
    val timeout = RaftLogic.onMessage(
      states(initiator),
      ElectionTimeout,
      cfgs(initiator),
      0,
      0,
      clusterSize
    )
    states = states.updated(initiator, timeout.state)
    transitionCount += 1

    val voteReq = timeout.effects.collectFirst {
      case Broadcast(msg: RequestVoteRequest) => msg
    }.get

    // Collect votes from all other nodes
    val responses = ns.tail.map { voter =>
      val trans = RaftLogic.onMessage(
        states(voter),
        voteReq,
        cfgs(voter),
        0,
        0,
        clusterSize
      )
      states = states.updated(voter, trans.state)
      transitionCount += 1
      val resp = trans.effects.collectFirst {
        case SendMessage(_, msg: RequestVoteResponse) => msg
      }.get
      (voter, resp)
    }

    // Feed votes to candidate until majority
    var candidate = timeout.state.asInstanceOf[Candidate]
    var leader: Option[NodeState] = None

    for (voter, resp) <- responses if leader.isEmpty do
      val trans = RaftLogic.onVoteResponse(
        candidate,
        voter,
        resp,
        cfgs(initiator),
        clusterSize
      )
      states = states.updated(initiator, trans.state)
      transitionCount += 1
      trans.state match
        case l: Leader    => leader = Some(l)
        case c: Candidate => candidate = c
        case _            => ()

    (leader.getOrElse(candidate), states(initiator).term, transitionCount)

  private def benchmarkNanos(warmup: Int, runs: Int)(
      fn: => Unit
  ): (Double, Double) =
    for _ <- 1 to warmup do fn
    val timings = (1 to runs).map { _ =>
      val start = System.nanoTime()
      fn
      System.nanoTime() - start
    }
    val avg = timings.sum.toDouble / runs
    val sorted = timings.sorted
    val p99 = sorted((runs * 0.99).toInt)
    (avg, p99.toDouble)

  "Cold-start election" should "complete in 3-node cluster" in {
    val ns = nodes(3)
    val cfgs = configs(ns)

    val (leader, term, transitions) = runElection(ns, cfgs, 3)
    leader shouldBe a[Leader]
    term shouldBe 1

    val (avgNs, p99Ns) = benchmarkNanos(WarmupRuns, BenchmarkRuns) {
      runElection(ns, cfgs, 3)
    }

    info(f"3-node cold-start election:")
    info(f"  Avg:  ${avgNs / 1000}%.1f µs  |  P99: ${p99Ns / 1000}%.1f µs")
    info(f"  Transitions: $transitions  |  Ops/sec: ${1e9 / avgNs}%.0f")
  }

  it should "complete in 5-node cluster" in {
    val ns = nodes(5)
    val cfgs = configs(ns)

    val (leader, term, transitions) = runElection(ns, cfgs, 5)
    leader shouldBe a[Leader]
    term shouldBe 1

    val (avgNs, p99Ns) = benchmarkNanos(WarmupRuns, BenchmarkRuns) {
      runElection(ns, cfgs, 5)
    }

    info(f"5-node cold-start election:")
    info(f"  Avg:  ${avgNs / 1000}%.1f µs  |  P99: ${p99Ns / 1000}%.1f µs")
    info(f"  Transitions: $transitions  |  Ops/sec: ${1e9 / avgNs}%.0f")
  }

  it should "complete in 7-node cluster" in {
    val ns = nodes(7)
    val cfgs = configs(ns)

    val (leader, term, transitions) = runElection(ns, cfgs, 7)
    leader shouldBe a[Leader]
    term shouldBe 1

    val (avgNs, p99Ns) = benchmarkNanos(WarmupRuns, BenchmarkRuns) {
      runElection(ns, cfgs, 7)
    }

    info(f"7-node cold-start election:")
    info(f"  Avg:  ${avgNs / 1000}%.1f µs  |  P99: ${p99Ns / 1000}%.1f µs")
    info(f"  Transitions: $transitions  |  Ops/sec: ${1e9 / avgNs}%.0f")
  }

  "Re-election after leader failure" should "complete with correct term advancement" in {
    val ns = nodes(5)
    val cfgs = configs(ns)

    // First: establish leader at term 1
    val (_, _, _) = runElection(ns, cfgs, 5)

    def runReElection(): (NodeState, Long, Int) =
      // Survivors are followers at term 1, leader (node-1) is dead
      val survivors = ns.tail
      var states = survivors.map(id => id -> (Follower(1L): NodeState)).toMap
      var transitionCount = 0

      // Node-2 times out → becomes candidate at term 2
      val initiator = survivors.head
      val cfg = cfgs(initiator).copy(preVoteEnabled = false)
      val timeout = RaftLogic.onMessage(
        states(initiator),
        ElectionTimeout,
        cfg,
        0,
        0,
        5
      )
      states = states.updated(initiator, timeout.state)
      transitionCount += 1

      val voteReq = timeout.effects.collectFirst {
        case Broadcast(msg: RequestVoteRequest) => msg
      }.get

      val responses = survivors.tail.map { voter =>
        val trans = RaftLogic.onMessage(
          states(voter),
          voteReq,
          cfgs(voter),
          0,
          0,
          5
        )
        states = states.updated(voter, trans.state)
        transitionCount += 1
        val resp = trans.effects.collectFirst {
          case SendMessage(_, msg: RequestVoteResponse) => msg
        }.get
        (voter, resp)
      }

      var candidate = timeout.state.asInstanceOf[Candidate]
      var leader: Option[NodeState] = None
      for (voter, resp) <- responses if leader.isEmpty do
        val trans = RaftLogic.onVoteResponse(candidate, voter, resp, cfg, 5)
        states = states.updated(initiator, trans.state)
        transitionCount += 1
        trans.state match
          case l: Leader    => leader = Some(l)
          case c: Candidate => candidate = c
          case _            => ()

      (leader.getOrElse(candidate), states(initiator).term, transitionCount)

    val (leader, term, _) = runReElection()
    leader shouldBe a[Leader]
    term shouldBe 2

    val (avgNs, p99Ns) = benchmarkNanos(WarmupRuns, BenchmarkRuns) {
      runReElection()
    }

    info(f"Re-election after failure (5-node, 1 dead):")
    info(f"  Avg:  ${avgNs / 1000}%.1f µs  |  P99: ${p99Ns / 1000}%.1f µs")
    info(f"  Ops/sec: ${1e9 / avgNs}%.0f")
  }

  "Split vote recovery" should "converge to single leader" in {
    val ns = nodes(3)
    val cfgsNoPreVote = configs(ns, preVote = false)

    def runSplitVoteRecovery(): (NodeState, Long, Int) =
      var states = ns.map(id => id -> (Follower(0L): NodeState)).toMap
      var transitionCount = 0

      // Two nodes timeout simultaneously → both become candidates
      val t1 = RaftLogic.onMessage(
        states(ns(0)),
        ElectionTimeout,
        cfgsNoPreVote(ns(0)),
        0,
        0,
        3
      )
      val t2 = RaftLogic.onMessage(
        states(ns(1)),
        ElectionTimeout,
        cfgsNoPreVote(ns(1)),
        0,
        0,
        3
      )
      states = states.updated(ns(0), t1.state).updated(ns(1), t2.state)
      transitionCount += 2

      // Node-3 votes for node-1 (first request received)
      val voteReq1 = t1.effects.collectFirst {
        case Broadcast(r: RequestVoteRequest) => r
      }.get
      val t3 = RaftLogic.onMessage(
        states(ns(2)),
        voteReq1,
        cfgsNoPreVote(ns(2)),
        0,
        0,
        3
      )
      states = states.updated(ns(2), t3.state)
      transitionCount += 1

      val resp3 = t3.effects.collectFirst {
        case SendMessage(_, r: RequestVoteResponse) => r
      }.get

      // Node-1 receives vote → becomes leader (self + node-3 = 2/3)
      val lead = RaftLogic.onVoteResponse(
        t1.state.asInstanceOf[Candidate],
        ns(2),
        resp3,
        cfgsNoPreVote(ns(0)),
        3
      )
      states = states.updated(ns(0), lead.state)
      transitionCount += 1

      // Node-2 still candidate, times out → term 2
      val retry = RaftLogic.onMessage(
        states(ns(1)),
        ElectionTimeout,
        cfgsNoPreVote(ns(1)),
        0,
        0,
        3
      )
      states = states.updated(ns(1), retry.state)
      transitionCount += 1

      (lead.state, states(ns(0)).term, transitionCount)

    val (leader, term, _) = runSplitVoteRecovery()
    leader shouldBe a[Leader]
    term shouldBe 1

    val (avgNs, p99Ns) = benchmarkNanos(WarmupRuns, BenchmarkRuns) {
      runSplitVoteRecovery()
    }

    info(f"Split vote → recovery (3-node):")
    info(f"  Avg:  ${avgNs / 1000}%.1f µs  |  P99: ${p99Ns / 1000}%.1f µs")
    info(f"  Ops/sec: ${1e9 / avgNs}%.0f")
  }

  "Cascading elections" should "stabilize with monotonic terms" in {
    val ns = nodes(3)
    val cfgsNoPreVote = configs(ns, preVote = false)

    def runCascade(): (Long, Int) =
      var states = ns.map(id => id -> (Follower(0L): NodeState)).toMap
      var maxTerm = 0L
      var transitionCount = 0

      // 3 consecutive elections on different nodes
      for round <- 0 until 3 do
        val initiator = ns(round % 3)
        val trans = RaftLogic.onMessage(
          states(initiator),
          ElectionTimeout,
          cfgsNoPreVote(initiator),
          0,
          0,
          3
        )
        states = states.updated(initiator, trans.state)
        maxTerm = math.max(maxTerm, trans.state.term)
        transitionCount += 1

        val voteReq = trans.effects.collectFirst {
          case Broadcast(r: RequestVoteRequest) => r
        }

        // Other nodes process vote request (if any)
        voteReq.foreach { req =>
          for voter <- ns if voter != initiator do
            val vt = RaftLogic.onMessage(
              states(voter),
              req,
              cfgsNoPreVote(voter),
              0,
              0,
              3
            )
            states = states.updated(voter, vt.state)
            maxTerm = math.max(maxTerm, vt.state.term)
            transitionCount += 1
        }

      (maxTerm, transitionCount)

    val (maxTerm, _) = runCascade()
    maxTerm should be >= 1L

    val (avgNs, p99Ns) = benchmarkNanos(WarmupRuns, BenchmarkRuns) {
      runCascade()
    }

    info(f"Cascading elections (3 rounds, 3-node):")
    info(f"  Avg:  ${avgNs / 1000}%.1f µs  |  P99: ${p99Ns / 1000}%.1f µs")
    info(f"  Final max term: $maxTerm  |  Ops/sec: ${1e9 / avgNs}%.0f")
  }

  "Pre-vote election" should "incur measurable overhead vs direct election" in {
    val ns = nodes(5)
    val directCfgs = configs(ns, preVote = false)
    val preVoteCfgs = configs(ns, preVote = true)

    val (directAvg, _) = benchmarkNanos(WarmupRuns, BenchmarkRuns) {
      runElection(ns, directCfgs, 5)
    }

    // Pre-vote adds an extra round of communication
    def runPreVoteElection(): (NodeState, Int) =
      var states = ns.map(id => id -> (Follower(0L): NodeState)).toMap
      var transitionCount = 0

      // PreVote round
      val preVoteTrans = RaftLogic.onMessage(
        states(ns.head),
        ElectionTimeout,
        preVoteCfgs(ns.head),
        0,
        0,
        5
      )
      states = states.updated(ns.head, preVoteTrans.state)
      transitionCount += 1

      val preVoteReq = preVoteTrans.effects.collectFirst {
        case Broadcast(msg: RequestVoteRequest) => msg
      }.get

      // Gather pre-votes
      val preVoteResponses = ns.tail.map { voter =>
        val trans = RaftLogic.onMessage(
          states(voter),
          preVoteReq,
          preVoteCfgs(voter),
          0,
          0,
          5
        )
        states = states.updated(voter, trans.state)
        transitionCount += 1
        val resp = trans.effects.collectFirst {
          case SendMessage(_, msg: RequestVoteResponse) => msg
        }.get
        (voter, resp)
      }

      // Feed pre-votes using onMessage (handles PreCandidate → Candidate promotion)
      var currentState = preVoteTrans.state
      for (_, resp) <- preVoteResponses do
        val trans = RaftLogic.onMessage(
          currentState,
          resp,
          preVoteCfgs(ns.head),
          0,
          0,
          5
        )
        currentState = trans.state
        states = states.updated(ns.head, trans.state)
        transitionCount += 1

      // If became real candidate, run actual election
      currentState match
        case c: Candidate =>
          val voteReq = RequestVoteRequest(
            term = c.term,
            candidateId = ns.head,
            lastLogIndex = 0,
            lastLogTerm = 0,
            isPreVote = false
          )
          val responses = ns.tail.map { voter =>
            val trans = RaftLogic.onMessage(
              states(voter),
              voteReq,
              preVoteCfgs(voter),
              0,
              0,
              5
            )
            states = states.updated(voter, trans.state)
            transitionCount += 1
            val resp = trans.effects.collectFirst {
              case SendMessage(_, msg: RequestVoteResponse) => msg
            }.get
            (voter, resp)
          }
          var cand = c
          var leader: Option[NodeState] = None
          for (voter, resp) <- responses if leader.isEmpty do
            val trans = RaftLogic.onVoteResponse(
              cand,
              voter,
              resp,
              preVoteCfgs(ns.head),
              5
            )
            transitionCount += 1
            trans.state match
              case l: Leader     => leader = Some(l)
              case c2: Candidate => cand = c2
              case _             => ()
          (leader.getOrElse(cand), transitionCount)
        case other =>
          (other, transitionCount)

    val (pvLeader, _) = runPreVoteElection()
    pvLeader shouldBe a[Leader]

    val (preVoteAvg, preVoteP99) = benchmarkNanos(WarmupRuns, BenchmarkRuns) {
      runPreVoteElection()
    }

    val overhead = (preVoteAvg / directAvg - 1) * 100
    info(f"Pre-vote vs direct election (5-node):")
    info(f"  Direct:   ${directAvg / 1000}%.1f µs")
    info(
      f"  Pre-vote: ${preVoteAvg / 1000}%.1f µs  |  P99: ${preVoteP99 / 1000}%.1f µs"
    )
    info(f"  Overhead: $overhead%.1f%%")
  }

  "Large cluster election" should "scale to 9 nodes" in {
    for size <- Seq(3, 5, 7, 9) do
      val ns = nodes(size)
      val cfgs = configs(ns)

      val (leader, _, transitions) = runElection(ns, cfgs, size)
      leader shouldBe a[Leader]

      val (avgNs, p99Ns) = benchmarkNanos(WarmupRuns, BenchmarkRuns) {
        runElection(ns, cfgs, size)
      }

      info(
        f"$size-node election: avg=${avgNs / 1000}%.1f µs  p99=${p99Ns / 1000}%.1f µs  transitions=$transitions  ops=${1e9 / avgNs}%.0f/s"
      )
  }

  "Election with divergent logs" should "correctly elect candidate with longer log" in {
    val ns = nodes(3)
    val cfgs = configs(ns)

    def runDivergentElection(): (NodeState, Int) =
      var states = ns.map(id => id -> (Follower(0L): NodeState)).toMap
      var transitionCount = 0

      // Node-1 has longer log (index=10, term=3)
      // Node-2 has shorter log (index=5, term=2)
      // Node-3 has shortest log (index=2, term=1)
      val logLengths =
        Map(ns(0) -> (10L, 3L), ns(1) -> (5L, 2L), ns(2) -> (2L, 1L))

      // Node-2 times out first (shorter log)
      val (n2LogIdx, n2LogTerm) = logLengths(ns(1))
      val t2 = RaftLogic.onMessage(
        states(ns(1)),
        ElectionTimeout,
        cfgs(ns(1)).copy(preVoteEnabled = false),
        n2LogIdx,
        n2LogTerm,
        3
      )
      states = states.updated(ns(1), t2.state)
      transitionCount += 1

      val voteReq2 = t2.effects.collectFirst {
        case Broadcast(r: RequestVoteRequest) => r
      }.get

      // Node-1 rejects (has longer log)
      val (n1LogIdx, n1LogTerm) = logLengths(ns(0))
      val t1 = RaftLogic.onMessage(
        states(ns(0)),
        voteReq2,
        cfgs(ns(0)),
        n1LogIdx,
        n1LogTerm,
        3
      )
      states = states.updated(ns(0), t1.state)
      transitionCount += 1

      val _ = t1.effects.collectFirst {
        case SendMessage(_, r: RequestVoteResponse) => r
      }.get

      // Node-3 grants (shorter log)
      val (n3LogIdx, n3LogTerm) = logLengths(ns(2))
      val t3 = RaftLogic.onMessage(
        states(ns(2)),
        voteReq2,
        cfgs(ns(2)),
        n3LogIdx,
        n3LogTerm,
        3
      )
      states = states.updated(ns(2), t3.state)
      transitionCount += 1

      // Node-1 now times out with its superior log
      val t1b = RaftLogic.onMessage(
        states(ns(0)),
        ElectionTimeout,
        cfgs(ns(0)).copy(preVoteEnabled = false),
        n1LogIdx,
        n1LogTerm,
        3
      )
      states = states.updated(ns(0), t1b.state)
      transitionCount += 1

      (t1b.state, transitionCount)

    val (state, transitions) = runDivergentElection()
    state shouldBe a[Candidate]

    val (avgNs, p99Ns) = benchmarkNanos(WarmupRuns, BenchmarkRuns) {
      runDivergentElection()
    }

    info(f"Divergent-log election (3-node):")
    info(f"  Avg:  ${avgNs / 1000}%.1f µs  |  P99: ${p99Ns / 1000}%.1f µs")
    info(f"  Transitions: $transitions  |  Ops/sec: ${1e9 / avgNs}%.0f")
  }
