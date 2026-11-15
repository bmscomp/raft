package raft.benchmark

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.*
import raft.state.NodeState.*
import raft.message.RaftMessage
import raft.message.RaftMessage.*
import raft.logic.RaftLogic
import raft.effect.Effect.*

/** Advanced benchmarks for node recovery, log replication, and cluster healing.
  *
  * Measures pure `RaftLogic.onMessage` throughput for:
  *   - Leader heartbeat generation
  *   - AppendEntries processing (batch sizes 100/1000/10K)
  *   - Follower catch-up from behind
  *   - Partition healing (stale leader reconnect)
  *   - Commit index advancement
  *   - Leader step-down and re-acquisition
  *   - Snapshot install path
  */
class RaftRecoveryBenchmark extends AnyFlatSpec with Matchers:

  private val WarmupRuns = 50
  private val BenchmarkRuns = 500

  private val n1 = NodeId("node-1")
  private val n2 = NodeId("node-2")
  private val n3 = NodeId("node-3")

  private val cfg1 = RaftConfig.default(n1).copy(preVoteEnabled = false)
  private val cfg2 = RaftConfig.default(n2).copy(preVoteEnabled = false)
  private val cfg3 = RaftConfig.default(n3).copy(preVoteEnabled = false)

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

  "Leader heartbeat generation" should "measure throughput for 3 and 5-node clusters" in {
    for clusterSize <- Seq(3, 5) do
      val leader: NodeState = Leader(term = 1)
      val iterations = 10_000

      // Warm up
      for _ <- 1 to 100 do
        RaftLogic.onMessage(leader, HeartbeatTimeout, cfg1, 50, 1, clusterSize)

      val start = System.nanoTime()
      for _ <- 1 to iterations do
        val trans = RaftLogic.onMessage(
          leader,
          HeartbeatTimeout,
          cfg1,
          50,
          1,
          clusterSize
        )
        trans.effects.nonEmpty shouldBe true
      val elapsed = System.nanoTime() - start

      val opsPerSec = iterations.toDouble / (elapsed / 1e9)
      val avgNs = elapsed.toDouble / iterations

      info(
        f"Heartbeat generation ($clusterSize-node): ${avgNs / 1000}%.1f µs/op  ${opsPerSec}%.0f ops/sec"
      )
  }

  "AppendEntries processing" should "handle batches of varying size" in {
    for batchSize <- Seq(1, 10, 100, 1000) do
      val entries =
        (1 to batchSize).map(i => Log.command(i.toLong, 1, s"cmd-$i".getBytes))
      val appendReq = AppendEntriesRequest(
        term = 1,
        leaderId = n1,
        prevLogIndex = 0,
        prevLogTerm = 0,
        entries = entries,
        leaderCommit = 0
      )
      val follower: NodeState =
        Follower(term = 1, votedFor = Some(n1), leaderId = Some(n1))

      val (avgNs, p99Ns) = benchmarkNanos(WarmupRuns, BenchmarkRuns) {
        val trans = RaftLogic.onMessage(follower, appendReq, cfg2, 0, 0, 3)
        trans.state.term shouldBe 1
      }

      info(
        f"AppendEntries batch=$batchSize: avg=${avgNs / 1000}%.1f µs  p99=${p99Ns / 1000}%.1f µs  ops=${1e9 / avgNs}%.0f/s"
      )
  }

  "Follower catch-up" should "process sequential AppendEntries to close gap" in {
    val gapSizes = Seq(10, 100, 500)

    for gap <- gapSizes do
      val (avgNs, p99Ns) = benchmarkNanos(WarmupRuns, BenchmarkRuns) {
        var followerState: NodeState =
          Follower(term = 1, votedFor = Some(n1), leaderId = Some(n1))
        var currentIndex = 0L
        var transitions = 0

        // Leader sends batches of 10 to close the gap
        for batchStart <- 1L to gap by 10 do
          val batchEnd = math.min(batchStart + 9, gap.toLong)
          val entries = (batchStart to batchEnd).map(i =>
            Log.command(i, 1, s"cmd-$i".getBytes)
          )

          val appendReq = AppendEntriesRequest(
            term = 1,
            leaderId = n1,
            prevLogIndex = currentIndex,
            prevLogTerm = if currentIndex == 0 then 0 else 1,
            entries = entries,
            leaderCommit = batchEnd
          )

          val trans = RaftLogic.onMessage(
            followerState,
            appendReq,
            cfg2,
            currentIndex,
            1,
            3
          )
          followerState = trans.state
          currentIndex = batchEnd
          transitions += 1

        transitions should be > 0
      }

      info(
        f"Follower catch-up (gap=$gap entries): avg=${avgNs / 1000}%.1f µs  p99=${p99Ns / 1000}%.1f µs"
      )
  }

  "Partition healing" should "measure stale leader step-down latency" in {
    val termJumps = Seq(1L, 5L, 100L, 1000L)

    for jump <- termJumps do
      val staleLeader: NodeState = Leader(term = 1)
      val healingMsg = AppendEntriesRequest(
        term = 1 + jump,
        leaderId = n2,
        prevLogIndex = 0,
        prevLogTerm = 0,
        entries = Seq.empty,
        leaderCommit = 0
      )

      val (avgNs, p99Ns) = benchmarkNanos(WarmupRuns, BenchmarkRuns) {
        val trans = RaftLogic.onMessage(staleLeader, healingMsg, cfg1, 50, 1, 3)
        trans.state shouldBe a[Follower]
        trans.state.term shouldBe (1 + jump)
      }

      info(
        f"Partition healing (term jump=$jump): avg=${avgNs / 1000}%.1f µs  p99=${p99Ns / 1000}%.1f µs"
      )
  }

  "Commit advancement" should "measure full replication round-trip" in {
    def runCommitRound(): Int =
      var leaderState: NodeState = Leader(term = 1)
      var follower2State: NodeState =
        Follower(term = 1, votedFor = Some(n1), leaderId = Some(n1))
      var follower3State: NodeState =
        Follower(term = 1, votedFor = Some(n1), leaderId = Some(n1))
      var transitions = 0

      // Leader creates AppendEntries with new entry
      val entries = Seq(Log.command(1, 1, "cmd".getBytes))
      val appendReq = AppendEntriesRequest(
        term = 1,
        leaderId = n1,
        prevLogIndex = 0,
        prevLogTerm = 0,
        entries = entries,
        leaderCommit = 0
      )

      // Follower-2 receives and responds
      val t2 = RaftLogic.onMessage(follower2State, appendReq, cfg2, 0, 0, 3)
      follower2State = t2.state
      transitions += 1

      val resp2 = t2.effects.collectFirst {
        case SendMessage(_, r: AppendEntriesResponse) => r
      }.get

      // Follower-3 receives and responds
      val t3 = RaftLogic.onMessage(follower3State, appendReq, cfg3, 0, 0, 3)
      follower3State = t3.state
      transitions += 1

      val resp3 = t3.effects.collectFirst {
        case SendMessage(_, r: AppendEntriesResponse) => r
      }.get

      // Leader processes responses
      val lt2 = RaftLogic.onMessage(leaderState, resp2, cfg1, 1, 1, 3)
      leaderState = lt2.state
      transitions += 1

      val lt3 = RaftLogic.onMessage(leaderState, resp3, cfg1, 1, 1, 3)
      leaderState = lt3.state
      transitions += 1

      transitions

    val transitions = runCommitRound()
    transitions shouldBe 4

    val (avgNs, p99Ns) = benchmarkNanos(WarmupRuns, BenchmarkRuns) {
      runCommitRound()
    }

    info(f"Full commit round-trip (3-node, 1 entry):")
    info(f"  Avg:  ${avgNs / 1000}%.1f µs  |  P99: ${p99Ns / 1000}%.1f µs")
    info(f"  Transitions: $transitions  |  Ops/sec: ${1e9 / avgNs}%.0f")
  }

  "Leader step-down and re-acquire" should "measure cycle time" in {
    def runStepDownCycle(): (NodeState, Int) =
      var transitions = 0

      // Leader at term 5
      var state: NodeState = Leader(term = 5)

      // Receives higher term → steps down
      val higherTermMsg = AppendEntriesRequest(
        term = 10,
        leaderId = n2,
        prevLogIndex = 0,
        prevLogTerm = 0,
        entries = Seq.empty,
        leaderCommit = 0
      )
      val stepDown = RaftLogic.onMessage(state, higherTermMsg, cfg1, 50, 5, 3)
      state = stepDown.state
      transitions += 1

      // Election timeout → becomes candidate
      val timeout = RaftLogic.onMessage(state, ElectionTimeout, cfg1, 50, 5, 3)
      state = timeout.state
      transitions += 1

      // Receives majority vote
      val voteReq = timeout.effects.collectFirst {
        case Broadcast(r: RequestVoteRequest) => r
      }.get

      val voterResp = RaftLogic.onMessage(
        Follower(10L),
        voteReq,
        cfg2,
        0,
        0,
        3
      )
      transitions += 1
      val resp = voterResp.effects.collectFirst {
        case SendMessage(_, r: RequestVoteResponse) => r
      }.get

      val lead = RaftLogic.onVoteResponse(
        state.asInstanceOf[Candidate],
        n2,
        resp,
        cfg1,
        3
      )
      state = lead.state
      transitions += 1

      (state, transitions)

    val (finalState, transitions) = runStepDownCycle()
    finalState shouldBe a[Leader]

    val (avgNs, p99Ns) = benchmarkNanos(WarmupRuns, BenchmarkRuns) {
      runStepDownCycle()
    }

    info(f"Step-down → re-acquire cycle:")
    info(f"  Avg:  ${avgNs / 1000}%.1f µs  |  P99: ${p99Ns / 1000}%.1f µs")
    info(f"  Transitions: $transitions  |  Ops/sec: ${1e9 / avgNs}%.0f")
  }

  "AppendEntries throughput" should "sustain high-volume processing" in {
    val iterations = 100_000
    val follower: NodeState =
      Follower(term = 1, votedFor = Some(n1), leaderId = Some(n1))
    val heartbeat = AppendEntriesRequest(
      term = 1,
      leaderId = n1,
      prevLogIndex = 10,
      prevLogTerm = 1,
      entries = Seq.empty,
      leaderCommit = 10
    )

    // Warm up
    for _ <- 1 to 1000 do
      RaftLogic.onMessage(follower, heartbeat, cfg2, 10, 1, 3)

    val start = System.nanoTime()
    for _ <- 1 to iterations do
      RaftLogic.onMessage(follower, heartbeat, cfg2, 10, 1, 3)
    val elapsed = System.nanoTime() - start

    val opsPerSec = iterations.toDouble / (elapsed / 1e9)
    val avgNs = elapsed.toDouble / iterations

    info(f"AppendEntries throughput (100K heartbeats):")
    info(f"  Avg: ${avgNs / 1000}%.1f µs/op  |  ${opsPerSec}%.0f ops/sec")
    info(f"  Total: ${elapsed / 1_000_000}%.0f ms")

    opsPerSec should be > 100_000.0
  }
