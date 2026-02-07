package raft.jepsen

import cats.effect.{Async, IO}
import cats.syntax.all.*
import raft.jepsen.checker.*
import raft.jepsen.cluster.SimulatedCluster
import raft.jepsen.nemesis.*
import raft.jepsen.workload.*
import scala.concurrent.duration.*

/** Statistics from a Jepsen test run. */
case class JepsenStats(
    totalOps: Int,
    successfulOps: Int,
    failedOps: Int,
    timedOutOps: Int,
    nemesisEvents: Int,
    durationMs: Long
)

/** Result of a complete Jepsen test run. */
case class JepsenResult(
    linearizable: Boolean,
    history: History,
    checkResult: CheckResult,
    nemesisLog: Vector[NemesisEvent],
    stats: JepsenStats
):
  /** Human-readable report. */
  def report: String =
    val sb = new StringBuilder
    sb.append("\n" + "═" * 60 + "\n")
    sb.append("  JEPSEN VERIFICATION REPORT\n")
    sb.append("═" * 60 + "\n\n")

    sb.append(s"  Result: ${
        if linearizable then "✅ PASS — History is linearizable"
        else "❌ FAIL — Linearizability violation detected"
      }\n\n")

    sb.append(
      s"  Operations:    ${stats.totalOps} total, ${stats.successfulOps} ok, ${stats.failedOps} failed, ${stats.timedOutOps} timed out\n"
    )
    sb.append(s"  Nemesis:       ${stats.nemesisEvents} events\n")
    sb.append(s"  Duration:      ${stats.durationMs}ms\n")
    sb.append(
      s"  Checker:       ${checkResult.stats.exploredStates} states explored in ${checkResult.stats.durationMs}ms\n"
    )
    sb.append(s"  Keys checked:  ${checkResult.stats.keysChecked}\n")

    if !linearizable then
      checkResult.violation.foreach { v =>
        sb.append(s"\n  Violation: ${v.description}\n")
        sb.append(s"  Involved ops: ${v.involvedOps.size}\n")
      }

    sb.append("\n" + "═" * 60 + "\n")
    sb.toString

/** Reusable Jepsen test orchestrator.
  *
  * Creates a simulated cluster, runs nemesis and client workloads concurrently,
  * collects the history, and verifies linearizability.
  */
case class JepsenTest(
    clusterSize: Int = 5,
    clients: Int = 5,
    duration: FiniteDuration = 10.seconds,
    nemesisSchedule: Option[NemesisSchedule] = None,
    workload: Workload = Workload.Register(1),
    model: SequentialModel = RegisterModel
):

  def run: IO[JepsenResult] =
    val startTime = System.currentTimeMillis()
    for
      // Create cluster
      cluster <- SimulatedCluster.create[IO](clusterSize)
      _ <- cluster.start

      // Let the cluster elect a leader
      _ <- IO.sleep(2.seconds)

      // Create workload components
      recorder = new HistoryRecorder[IO]
      pool = new ClientPool[IO](
        cluster,
        WorkloadConfig(clients, workload, 20.millis),
        recorder
      )

      // Run nemesis and clients concurrently
      nemesis = cluster.createNemesis
      nemesisEvents <- nemesisSchedule match
        case Some(schedule) =>
          for
            nemesisFiber <- nemesis.run(schedule, duration).start
            clientFiber <- pool.run(duration).start
            _ <- clientFiber.joinWithNever
            events <- nemesisFiber.joinWithNever
          yield events
        case None =>
          pool.run(duration).as(Vector.empty[NemesisEvent])

      // Collect history
      history <- recorder.snapshot

      // Check linearizability
      checkResult = LinearizabilityChecker.check(history, model)

      // Stop cluster
      _ <- cluster.stop

      elapsed = System.currentTimeMillis() - startTime

      // Build stats
      invocations = history.invocations.size
      oks = history.operations.count(_.opType == OpType.Ok)
      fails = history.operations.count(_.opType == OpType.Fail)
      infos = history.operations.count(_.opType == OpType.Info)

      result = JepsenResult(
        linearizable = checkResult.linearizable,
        history = history,
        checkResult = checkResult,
        nemesisLog = nemesisEvents,
        stats = JepsenStats(
          invocations,
          oks,
          fails,
          infos,
          nemesisEvents.size,
          elapsed
        )
      )

      _ <- IO.println(result.report)
    yield result
