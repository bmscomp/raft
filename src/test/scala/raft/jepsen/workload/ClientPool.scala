package raft.jepsen.workload

import cats.effect.{Async, Ref}
import cats.effect.implicits.*
import cats.effect.std.Random
import cats.syntax.all.*
import raft.state.NodeId
import raft.jepsen.checker.{OpFunction, History}
import raft.jepsen.cluster.{SimulatedCluster, SubmitResult, ReadResult}
import scala.concurrent.duration.*

/** Workload profile for generating client operations. */
enum Workload:
  case Register(numKeys: Int)
  case KVStore(numKeys: Int)
  case WriteOnly(numKeys: Int)

/** Configuration for client workload generation. */
case class WorkloadConfig(
    numClients: Int = 5,
    workload: Workload = Workload.Register(1),
    thinkTime: FiniteDuration = 10.millis
)

/** Concurrent client pool that generates operations and records history.
  *
  * Each client runs as a fiber, picking random operations and recording them in
  * the shared HistoryRecorder. All operations are real atomic primitives — no
  * fake CAS (which would be a non-atomic read-then-write).
  */
class ClientPool[F[_]: Async](
    cluster: SimulatedCluster[F],
    config: WorkloadConfig,
    recorder: HistoryRecorder[F]
):

  private val writeCounter = java.util.concurrent.atomic.AtomicLong(0L)

  /** Run the workload for the given duration, returning the recorded history.
    */
  def run(duration: FiniteDuration): F[History] =
    for
      random <- Random.scalaUtilRandom[F]
      leaderRef <- Ref.of[F, Option[NodeId]](None)
      fibers <- (0 until config.numClients).toList.traverse { clientId =>
        Async[F].start(runClient(clientId, duration, random, leaderRef))
      }
      _ <- fibers.traverse_(_.joinWithNever)
      history <- recorder.snapshot
    yield history

  /** Pick the best node: cached leader if known, otherwise discover or random.
    */
  private def pickNode(
      leaderRef: Ref[F, Option[NodeId]],
      random: Random[F]
  ): F[NodeId] =
    leaderRef.get.flatMap {
      case Some(leader) => Async[F].pure(leader)
      case None         =>
        cluster.getLeader.flatMap {
          case Some(leader) =>
            leaderRef.set(Some(leader)).as(leader)
          case None =>
            random.nextIntBounded(cluster.nodeIds.size).map(cluster.nodeIds(_))
        }
    }

  /** Invalidate cached leader on NotLeader/Timeout responses. */
  private def onNotLeader(leaderRef: Ref[F, Option[NodeId]]): F[Unit] =
    leaderRef.set(None)

  private def runClient(
      clientId: Int,
      duration: FiniteDuration,
      random: Random[F],
      leaderRef: Ref[F, Option[NodeId]]
  ): F[Unit] =
    val deadline = System.currentTimeMillis() + duration.toMillis

    def loop: F[Unit] =
      if System.currentTimeMillis() >= deadline then Async[F].unit
      else
        for
          node <- pickNode(leaderRef, random)

          _ <- config.workload match
            case Workload.Register(numKeys) =>
              randomRegisterOp(clientId, node, numKeys, random, leaderRef)
            case Workload.KVStore(numKeys) =>
              randomKVOp(clientId, node, numKeys, random, leaderRef)
            case Workload.WriteOnly(numKeys) =>
              randomWriteOp(clientId, node, numKeys, random, leaderRef)

          _ <- Async[F].sleep(config.thinkTime)
          _ <- loop
        yield ()

    loop.handleError(_ => ())

  /** Register workload: 50/50 read/write on a set of keys. */
  private def randomRegisterOp(
      clientId: Int,
      node: NodeId,
      numKeys: Int,
      random: Random[F],
      leaderRef: Ref[F, Option[NodeId]]
  ): F[Unit] =
    for
      keyIdx <- random.nextIntBounded(numKeys)
      key = s"key-$keyIdx"
      isRead <- random.nextBoolean
      _ <-
        if isRead then doRead(clientId, node, key, leaderRef)
        else doWrite(clientId, node, key, random, leaderRef)
    yield ()

  /** KV workload: reads and writes (no CAS — we lack an atomic CAS primitive).
    */
  private def randomKVOp(
      clientId: Int,
      node: NodeId,
      numKeys: Int,
      random: Random[F],
      leaderRef: Ref[F, Option[NodeId]]
  ): F[Unit] =
    for
      keyIdx <- random.nextIntBounded(numKeys)
      key = s"key-$keyIdx"
      isRead <- random.nextBoolean
      _ <-
        if isRead then doRead(clientId, node, key, leaderRef)
        else doWrite(clientId, node, key, random, leaderRef)
    yield ()

  /** Write-only workload: only writes. */
  private def randomWriteOp(
      clientId: Int,
      node: NodeId,
      numKeys: Int,
      random: Random[F],
      leaderRef: Ref[F, Option[NodeId]]
  ): F[Unit] =
    for
      keyIdx <- random.nextIntBounded(numKeys)
      key = s"key-$keyIdx"
      _ <- doWrite(clientId, node, key, random, leaderRef)
    yield ()

  private def doRead(
      clientId: Int,
      node: NodeId,
      key: String,
      leaderRef: Ref[F, Option[NodeId]]
  ): F[Unit] =
    for
      idx <- recorder.invoke(clientId, OpFunction.Read, key, None)
      result <- cluster.submitGet(node, key)
      _ <- result match
        case ReadResult.Found(v) =>
          recorder.ok(idx, clientId, OpFunction.Read, key, Some(v))
        case ReadResult.Timeout =>
          onNotLeader(leaderRef) *>
            recorder.info(idx, clientId, OpFunction.Read, key, "timeout")
        case ReadResult.NotLeader(_) =>
          onNotLeader(leaderRef) *>
            recorder.info(idx, clientId, OpFunction.Read, key, "not-leader")
        case ReadResult.Error(msg) =>
          recorder.fail(idx, clientId, OpFunction.Read, key, msg)
    yield ()

  private def doWrite(
      clientId: Int,
      node: NodeId,
      key: String,
      random: Random[F],
      leaderRef: Ref[F, Option[NodeId]]
  ): F[Unit] =
    for
      seq <- Async[F].delay(writeCounter.getAndIncrement())
      value = s"c${clientId}_$seq"
      idx <- recorder.invoke(clientId, OpFunction.Write, key, Some(value))
      result <- cluster.submitPut(node, key, value)
      _ <- result match
        case SubmitResult.Success(_) =>
          recorder.ok(idx, clientId, OpFunction.Write, key, Some("ok"))
        case SubmitResult.Timeout =>
          onNotLeader(leaderRef) *>
            recorder.info(idx, clientId, OpFunction.Write, key, "timeout")
        case SubmitResult.NotLeader(_) =>
          onNotLeader(leaderRef) *>
            recorder.info(idx, clientId, OpFunction.Write, key, "not-leader")
        case SubmitResult.Error(msg) =>
          recorder.info(idx, clientId, OpFunction.Write, key, msg)
    yield ()
