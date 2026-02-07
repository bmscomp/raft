package raft.benchmark

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream

import raft.state.*
import raft.state.NodeState.*
import raft.message.RaftMessage
import raft.message.RaftMessage.*
import raft.logic.RaftLogic
import raft.effect.Effect.*
import raft.impl.{InMemLogStore, InMemStableStore, DefaultTimerService}
import raft.spi.StateMachine
import raft.multigroup.*

import scala.concurrent.duration.*

/** Advanced benchmarks for multi-Raft group operations and stress scenarios.
  *
  * Measures both pure-logic throughput and IO-based multi-group coordination:
  *   - Multi-group creation/shutdown scaling (10/100/1000)
  *   - Concurrent independent elections
  *   - Rolling group failure and recovery
  *   - Random message storm throughput
  *   - Term inflation under rapid elections
  *   - Group state queries at scale
  *   - Rapid group lifecycle churn
  */
class MultiRaftBenchmark extends AnyFlatSpec with Matchers:

  private val n1 = NodeId("node-1")
  private val n2 = NodeId("node-2")
  private val n3 = NodeId("node-3")
  private val voters = Set(n1, n2, n3)

  private val cfg = RaftConfig.default(n1).copy(preVoteEnabled = false)

  private def mkGroupConfig(name: String): IO[GroupConfig[IO]] =
    for
      logStore <- InMemLogStore[IO]()
      stableStore <- InMemStableStore[IO]()
      timerService <- DefaultTimerService[IO]
    yield GroupConfig[IO](
      raftConfig = RaftConfig.default(n1),
      cluster = ClusterConfig(voters.map(_ -> NodeRole.Voter).toMap),
      logStore = logStore,
      stableStore = stableStore,
      stateMachine = new StateMachine[IO, Unit]:
        def apply(log: Log): IO[Unit] = IO.unit
        def snapshot: IO[Stream[IO, Byte]] = IO.pure(Stream.empty)
        def restore(data: Stream[IO, Byte]): IO[Unit] = IO.unit
      ,
      timerService = timerService
    )

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

  "Multi-group creation" should "scale with group count" in {
    for count <- Seq(10, 50, 100) do
      val test = for
        transport <- MultiGroupTransport[IO](n1, _ => IO.unit)
        multi <- MultiRaftNode[IO](n1, transport)
        start <- IO.monotonic
        _ <- (1 to count).toList.traverse_ { i =>
          for
            cfg <- mkGroupConfig(s"group-$i")
            _ <- multi.createGroup(GroupId(s"group-$i"), cfg)
          yield ()
        }
        end <- IO.monotonic
        elapsed = (end - start).toMicros
        groups <- multi.listGroups
        _ <- multi.shutdown
      yield (groups.size, elapsed)

      val (size, elapsed) = test.unsafeRunSync()
      size shouldBe count

      info(
        f"Create $count groups: ${elapsed}µs  (${elapsed.toDouble / count}%.1f µs/group)"
      )
  }

  "Multi-group state query" should "scale with group count" in {
    val count = 50

    val test = for
      transport <- MultiGroupTransport[IO](n1, _ => IO.unit)
      multi <- MultiRaftNode[IO](n1, transport)
      _ <- (1 to count).toList.traverse_ { i =>
        mkGroupConfig(s"group-$i").flatMap(cfg =>
          multi.createGroup(GroupId(s"group-$i"), cfg)
        )
      }
      start <- IO.monotonic
      states <- (1 to count).toList.traverse(i =>
        multi.getGroupState(GroupId(s"group-$i"))
      )
      end <- IO.monotonic
      elapsed = (end - start).toMicros
      _ <- multi.shutdown
    yield (states.flatten.size, elapsed)

    val (queried, elapsed) = test.unsafeRunSync()
    queried shouldBe count

    info(
      f"Query $count group states: ${elapsed}µs  (${elapsed.toDouble / count}%.1f µs/group)"
    )
  }

  "Multi-group shutdown" should "cleanly shut down all groups" in {
    val count = 50

    val test = for
      transport <- MultiGroupTransport[IO](n1, _ => IO.unit)
      multi <- MultiRaftNode[IO](n1, transport)
      _ <- (1 to count).toList.traverse_ { i =>
        mkGroupConfig(s"group-$i").flatMap(cfg =>
          multi.createGroup(GroupId(s"group-$i"), cfg)
        )
      }
      start <- IO.monotonic
      _ <- multi.shutdown
      end <- IO.monotonic
      elapsed = (end - start).toMicros
      remaining <- multi.listGroups
    yield (remaining.size, elapsed)

    val (remaining, elapsed) = test.unsafeRunSync()
    remaining shouldBe 0

    info(
      f"Shutdown $count groups: ${elapsed}µs  (${elapsed.toDouble / count}%.1f µs/group)"
    )
  }

  "Rapid group lifecycle churn" should "handle create-remove cycles" in {
    val cycles = 20

    val test = for
      transport <- MultiGroupTransport[IO](n1, _ => IO.unit)
      multi <- MultiRaftNode[IO](n1, transport)
      start <- IO.monotonic
      _ <- (1 to cycles).toList.traverse_ { i =>
        val gid = GroupId(s"churn-$i")
        for
          cfg <- mkGroupConfig(s"churn-$i")
          _ <- multi.createGroup(gid, cfg)
          _ <- multi.removeGroup(gid)
        yield ()
      }
      end <- IO.monotonic
      elapsed = (end - start).toMicros
      remaining <- multi.listGroups
    yield (remaining.size, elapsed)

    val (remaining, elapsed) = test.unsafeRunSync()
    remaining shouldBe 0

    info(
      f"$cycles create-remove cycles: ${elapsed}µs  (${elapsed.toDouble / cycles}%.1f µs/cycle)"
    )
  }

  "Concurrent independent elections" should "produce independent leaders per group" in {
    val groupCount = 10

    def runConcurrentElections(): (Int, Int) =
      val configs = (1 to groupCount).map { g =>
        val ns = (1 to 3).map(i => NodeId(s"g${g}-n$i")).toVector
        val cfgs = ns
          .map(id => id -> RaftConfig.default(id).copy(preVoteEnabled = false))
          .toMap
        (ns, cfgs)
      }

      var totalTransitions = 0
      var leaders = 0

      for (ns, cfgs) <- configs do
        var states = ns.map(id => id -> (Follower(0L): NodeState)).toMap
        val initiator = ns.head

        val timeout = RaftLogic.onMessage(
          states(initiator),
          ElectionTimeout,
          cfgs(initiator),
          0,
          0,
          3
        )
        states = states.updated(initiator, timeout.state)
        totalTransitions += 1

        val voteReq = timeout.effects.collectFirst {
          case Broadcast(r: RequestVoteRequest) => r
        }.get

        val responses = ns.tail.map { voter =>
          val trans =
            RaftLogic.onMessage(states(voter), voteReq, cfgs(voter), 0, 0, 3)
          states = states.updated(voter, trans.state)
          totalTransitions += 1
          val resp = trans.effects.collectFirst {
            case SendMessage(_, msg: RequestVoteResponse) => msg
          }.get
          (voter, resp)
        }

        var candidate = timeout.state.asInstanceOf[Candidate]
        var leaderFound = false
        for (voter, resp) <- responses do
          val trans =
            RaftLogic.onVoteResponse(candidate, voter, resp, cfgs(initiator), 3)
          totalTransitions += 1
          trans.state match
            case l: Leader =>
              if !leaderFound then
                leaders += 1
                leaderFound = true
            case c: Candidate => candidate = c
            case _            => ()

      (leaders, totalTransitions)

    val (leaders, transitions) = runConcurrentElections()
    leaders shouldBe groupCount

    val (avgNs, p99Ns) = benchmarkNanos(50, 200) {
      runConcurrentElections()
    }

    info(f"$groupCount concurrent elections:")
    info(f"  Avg: ${avgNs / 1000}%.1f µs  |  P99: ${p99Ns / 1000}%.1f µs")
    info(
      f"  Leaders: $leaders  |  Transitions: $transitions  |  Ops/sec: ${1e9 / avgNs}%.0f"
    )
  }

  "Random message storm" should "sustain high throughput on single node" in {
    val iterations = 50_000
    val messages: Vector[RaftMessage] = Vector(
      HeartbeatTimeout,
      AppendEntriesRequest(
        term = 1,
        leaderId = n1,
        prevLogIndex = 0,
        prevLogTerm = 0,
        entries = Seq.empty,
        leaderCommit = 0
      ),
      RequestVoteRequest(
        term = 2,
        candidateId = n2,
        lastLogIndex = 0,
        lastLogTerm = 0,
        isPreVote = false
      ),
      AppendEntriesResponse(term = 1, success = true, matchIndex = 0),
      RequestVoteResponse(term = 1, voteGranted = false)
    )

    val rng = new scala.util.Random(42)

    // Warm up
    for _ <- 1 to 1000 do
      val msg = messages(rng.nextInt(messages.size))
      RaftLogic.onMessage(Follower(1L), msg, cfg, 0, 0, 3)

    var state: NodeState = Follower(1L)
    val start = System.nanoTime()
    for _ <- 1 to iterations do
      val msg = messages(rng.nextInt(messages.size))
      val trans = RaftLogic.onMessage(state, msg, cfg, 0, 0, 3)
      state = trans.state
    val elapsed = System.nanoTime() - start

    val opsPerSec = iterations.toDouble / (elapsed / 1e9)
    val avgNs = elapsed.toDouble / iterations

    info(f"Random message storm ($iterations msgs, 5 types):")
    info(f"  Avg: ${avgNs / 1000}%.1f µs/msg  |  ${opsPerSec}%.0f msgs/sec")
    info(f"  Final state: ${state.getClass.getSimpleName} term=${state.term}")

    opsPerSec should be > 100_000.0
  }

  "Term inflation stress" should "handle 1000 consecutive elections" in {
    val rounds = 1000

    val start = System.nanoTime()
    var state: NodeState = Follower(0L)

    for _ <- 1 to rounds do
      val trans = RaftLogic.onMessage(state, ElectionTimeout, cfg, 0, 0, 3)
      // Each ElectionTimeout increments term
      state = trans.state

    val elapsed = System.nanoTime() - start
    val avgNs = elapsed.toDouble / rounds

    state.term should be >= rounds.toLong

    info(f"$rounds consecutive elections:")
    info(f"  Final term: ${state.term}")
    info(
      f"  Avg: ${avgNs / 1000}%.1f µs/election  |  Total: ${elapsed / 1_000_000}%.0f ms"
    )
    info(f"  Ops/sec: ${1e9 / avgNs}%.0f")
  }

  "Rolling group failure" should "measure sequential fail-recover cost" in {
    val groupCount = 5

    def runRollingFailure(): (Int, Int) =
      val allGroups = (1 to groupCount).map { g =>
        val ns = (1 to 3).map(i => NodeId(s"rg${g}-n$i")).toVector
        val cfgs = ns
          .map(id => id -> RaftConfig.default(id).copy(preVoteEnabled = false))
          .toMap
        (ns, cfgs)
      }

      var stableLeaders = 0
      var totalTransitions = 0

      for (ns, cfgs) <- allGroups do
        // Elect leader
        var states = ns.map(id => id -> (Follower(0L): NodeState)).toMap
        val t1 = RaftLogic.onMessage(
          states(ns.head),
          ElectionTimeout,
          cfgs(ns.head),
          0,
          0,
          3
        )
        states = states.updated(ns.head, t1.state)
        totalTransitions += 1

        val voteReq = t1.effects.collectFirst {
          case Broadcast(r: RequestVoteRequest) => r
        }.get
        val t2 =
          RaftLogic.onMessage(states(ns(1)), voteReq, cfgs(ns(1)), 0, 0, 3)
        states = states.updated(ns(1), t2.state)
        totalTransitions += 1

        val resp = t2.effects.collectFirst {
          case SendMessage(_, r: RequestVoteResponse) => r
        }.get
        val lead = RaftLogic.onVoteResponse(
          t1.state.asInstanceOf[Candidate],
          ns(1),
          resp,
          cfgs(ns.head),
          3
        )
        totalTransitions += 1

        if lead.state.isInstanceOf[Leader] then
          // Simulate failure: leader is now dead, node-2 re-elects
          val timeout2 = RaftLogic.onMessage(
            states(ns(1)),
            ElectionTimeout,
            cfgs(ns(1)),
            0,
            0,
            3
          )
          states = states.updated(ns(1), timeout2.state)
          totalTransitions += 1

          val vr2 = timeout2.effects.collectFirst {
            case Broadcast(r: RequestVoteRequest) => r
          }.get
          val t3 = RaftLogic.onMessage(states(ns(2)), vr2, cfgs(ns(2)), 0, 0, 3)
          totalTransitions += 1
          val resp3 = t3.effects.collectFirst {
            case SendMessage(_, r: RequestVoteResponse) => r
          }.get

          val lead2 = RaftLogic.onVoteResponse(
            timeout2.state.asInstanceOf[Candidate],
            ns(2),
            resp3,
            cfgs(ns(1)),
            3
          )
          totalTransitions += 1
          if lead2.state.isInstanceOf[Leader] then stableLeaders += 1

      (stableLeaders, totalTransitions)

    val (stableLeaders, transitions) = runRollingFailure()
    stableLeaders shouldBe groupCount

    val (avgNs, p99Ns) = benchmarkNanos(50, 200) {
      runRollingFailure()
    }

    info(f"Rolling failure ($groupCount groups, fail+recover each):")
    info(f"  Avg: ${avgNs / 1000}%.1f µs  |  P99: ${p99Ns / 1000}%.1f µs")
    info(
      f"  Stable re-elections: $stableLeaders/$groupCount  |  Transitions: $transitions"
    )
  }
