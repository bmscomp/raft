package raft.jepsen.nemesis

import cats.effect.{Async, Ref}
import cats.effect.std.Random
import cats.syntax.all.*
import fs2.Stream
import raft.state.NodeId
import raft.jepsen.cluster.PartitionState
import scala.concurrent.duration.*

/** Fault injection actions for Jepsen-style nemesis testing. */
enum NemesisAction:
  case Partition(groups: List[Set[NodeId]])
  case HealAll
  case IsolateNode(node: NodeId)
  case KillNode(node: NodeId)
  case RestartNode(node: NodeId)
  case PauseNode(node: NodeId, duration: FiniteDuration)
  case SlowNetwork(maxDelayMs: Long)
  case DropMessages(probability: Double)
  case ResetNetwork

/** A recorded nemesis event with timestamp. */
case class NemesisEvent(
    timestamp: Long,
    action: NemesisAction,
    description: String
)

/** Predefined nemesis schedules for common test scenarios. */
enum NemesisSchedule:
  case RandomPartitions(mtbf: FiniteDuration)
  case RollingRestart(interval: FiniteDuration)
  case SplitBrain(flipInterval: FiniteDuration)
  case Combined(mtbf: FiniteDuration)

/** Nemesis engine that applies fault injection actions to the cluster.
  *
  * Runs as a concurrent fiber alongside client workloads, injecting faults
  * according to a schedule and recording all events.
  */
class NemesisScheduler[F[_]: Async](
    stateRef: Ref[F, PartitionState],
    allNodes: List[NodeId],
    onNodeKill: NodeId => F[Unit],
    onNodeRestart: NodeId => F[Unit]
):

  private val log = Ref.unsafe[F, Vector[NemesisEvent]](Vector.empty)

  def events: F[Vector[NemesisEvent]] = log.get

  /** Apply a single nemesis action. */
  def apply(action: NemesisAction): F[Unit] =
    val ts = System.nanoTime()
    val effect = action match
      case NemesisAction.Partition(groups) =>
        stateRef.update(_.copy(partitions = groups.toSet)) *>
          record(
            ts,
            action,
            s"Partitioned into ${groups.size} groups: ${groups.map(_.map(_.value).mkString("{", ",", "}")).mkString(" | ")}"
          )

      case NemesisAction.HealAll =>
        stateRef.update(
          _.copy(
            partitions = Set.empty,
            isolated = Set.empty,
            dropProbability = 0.0,
            messageDelayMs = None
          )
        ) *> record(ts, action, "Healed all partitions")

      case NemesisAction.IsolateNode(node) =>
        stateRef.update(s => s.copy(isolated = s.isolated + node)) *>
          record(ts, action, s"Isolated ${node.value}")

      case NemesisAction.KillNode(node) =>
        stateRef.update(s => s.copy(killed = s.killed + node)) *>
          onNodeKill(node) *>
          record(ts, action, s"Killed ${node.value}")

      case NemesisAction.RestartNode(node) =>
        stateRef.update(s => s.copy(killed = s.killed - node)) *>
          onNodeRestart(node) *>
          record(ts, action, s"Restarted ${node.value}")

      case NemesisAction.PauseNode(node, duration) =>
        stateRef.update(s => s.copy(isolated = s.isolated + node)) *>
          record(
            ts,
            action,
            s"Paused ${node.value} for ${duration.toMillis}ms"
          ) *>
          Async[F].sleep(duration) *>
          stateRef.update(s => s.copy(isolated = s.isolated - node)) *>
          record(
            System.nanoTime(),
            NemesisAction.ResetNetwork,
            s"Resumed ${node.value}"
          )

      case NemesisAction.SlowNetwork(maxDelayMs) =>
        stateRef.update(_.copy(messageDelayMs = Some(maxDelayMs))) *>
          record(ts, action, s"Slowed network (max ${maxDelayMs}ms)")

      case NemesisAction.DropMessages(probability) =>
        stateRef.update(_.copy(dropProbability = probability)) *>
          record(ts, action, f"Dropping messages with p=$probability%.2f")

      case NemesisAction.ResetNetwork =>
        stateRef.update(
          _.copy(
            messageDelayMs = None,
            dropProbability = 0.0
          )
        ) *> record(ts, action, "Reset network conditions")

    effect

  /** Run a nemesis schedule for the given duration. */
  def run(
      schedule: NemesisSchedule,
      duration: FiniteDuration
  ): F[Vector[NemesisEvent]] =
    for
      random <- Random.scalaUtilRandom[F]
      _ <- runSchedule(schedule, duration, random)
      _ <- apply(NemesisAction.HealAll)
      result <- log.get
    yield result

  private def runSchedule(
      schedule: NemesisSchedule,
      remaining: FiniteDuration,
      random: Random[F]
  ): F[Unit] =
    if remaining <= Duration.Zero then Async[F].unit
    else
      schedule match
        case NemesisSchedule.RandomPartitions(mtbf) =>
          for
            _ <- randomPartition(random)
            _ <- Async[F].sleep(mtbf)
            _ <- apply(NemesisAction.HealAll)
            _ <- Async[F].sleep(1.second)
            _ <- runSchedule(schedule, remaining - mtbf - 1.second, random)
          yield ()

        case NemesisSchedule.RollingRestart(interval) =>
          allNodes
            .foldLeftM(remaining) { (rem, node) =>
              if rem <= Duration.Zero then Async[F].pure(rem)
              else
                for
                  _ <- apply(NemesisAction.KillNode(node))
                  _ <- Async[F].sleep(interval / 2)
                  _ <- apply(NemesisAction.RestartNode(node))
                  _ <- Async[F].sleep(interval / 2)
                yield rem - interval
            }
            .void

        case NemesisSchedule.SplitBrain(flipInterval) =>
          val half = allNodes.size / 2
          val (groupA, groupB) = allNodes.splitAt(half)
          for
            _ <- apply(
              NemesisAction.Partition(List(groupA.toSet, groupB.toSet))
            )
            _ <- Async[F].sleep(flipInterval)
            _ <- apply(NemesisAction.HealAll)
            _ <- Async[F].sleep(1.second)
            _ <- runSchedule(
              schedule,
              remaining - flipInterval - 1.second,
              random
            )
          yield ()

        case NemesisSchedule.Combined(mtbf) =>
          for
            choice <- random.nextIntBounded(3)
            _ <- choice match
              case 0 => randomPartition(random)
              case 1 =>
                random.nextIntBounded(allNodes.size).flatMap { idx =>
                  apply(NemesisAction.IsolateNode(allNodes(idx)))
                }
              case _ =>
                apply(NemesisAction.DropMessages(0.1))
            _ <- Async[F].sleep(mtbf)
            _ <- apply(NemesisAction.HealAll)
            _ <- Async[F].sleep(1.second)
            _ <- runSchedule(schedule, remaining - mtbf - 1.second, random)
          yield ()

  private def randomPartition(random: Random[F]): F[Unit] =
    for
      shuffled <- random.shuffleList(allNodes)
      splitPoint <- random.nextIntBounded(allNodes.size - 1).map(_ + 1)
      (groupA, groupB) = shuffled.splitAt(splitPoint)
      _ <- apply(NemesisAction.Partition(List(groupA.toSet, groupB.toSet)))
    yield ()

  private def record(ts: Long, action: NemesisAction, desc: String): F[Unit] =
    log.update(_ :+ NemesisEvent(ts, action, desc))
