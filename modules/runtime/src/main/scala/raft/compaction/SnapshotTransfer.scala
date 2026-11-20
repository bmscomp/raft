package raft.compaction

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

import cats.effect.Async
import cats.syntax.all.*
import fs2.{Chunk, Pull, Stream}
import org.typelevel.log4cats.Logger

import raft.message.RaftMessage.InstallSnapshotRequest
import raft.spi.RaftTransport
import raft.state.{Bytes, NodeId, Snapshot}

/** Streams chunked `InstallSnapshotRequest` RPCs from leader to lagging
  * followers without materializing full snapshots in JVM heap (`0` `var`s).
  */
class SnapshotTransfer[F[_]: Async](
    chunkSize: Int,
    transport: RaftTransport[F],
    logger: Logger[F],
    chunkTimeout: FiniteDuration = 30.seconds,
    maxInFlight: Int = 4
):
  private val F = Async[F]
  private val effectiveChunkSize = math.max(1, chunkSize)
  private val effectivePrefetch = math.max(1, maxInFlight)

  /** Stream `snapshot` to `target` sequentially in `effectiveChunkSize` chunks.
    */
  def sendSnapshot(
      leaderId: NodeId,
      target: NodeId,
      snapshot: Snapshot[F],
      leaderTerm: Long = -1L
  ): F[Int] =
    streamOrderedChunks(
      leaderId = leaderId,
      target = target,
      snapshot = snapshot,
      leaderTerm = leaderTerm,
      chunkStream = snapshot.data.chunkN(effectiveChunkSize)
    )

  /** Stream `snapshot` to `target` with bounded read-ahead prefetching
    * (`maxInFlight` chunks buffered asynchronously from disk while preserving
    * strict monotonic `offset` delivery order on the wire).
    */
  def sendSnapshotPipelined(
      leaderId: NodeId,
      target: NodeId,
      snapshot: Snapshot[F],
      leaderTerm: Long = -1L
  ): F[Int] =
    streamOrderedChunks(
      leaderId = leaderId,
      target = target,
      snapshot = snapshot,
      leaderTerm = leaderTerm,
      chunkStream =
        snapshot.data.chunkN(effectiveChunkSize).prefetchN(effectivePrefetch)
    )

  private def streamOrderedChunks(
      leaderId: NodeId,
      target: NodeId,
      snapshot: Snapshot[F],
      leaderTerm: Long,
      chunkStream: Stream[F, Chunk[Byte]]
  ): F[Int] =
    val effectiveTerm =
      if leaderTerm >= 0L then leaderTerm else snapshot.lastIncludedTerm

    def go(
        s: Stream[F, Chunk[Byte]],
        idx: Int,
        offset: Long
    ): Pull[F, Int, Unit] =
      s.pull.uncons1.flatMap {
        case Some((chunk, tail)) =>
          tail.pull.peek1.flatMap { nextOpt =>
            val isLast = nextOpt.isEmpty
            val payload = Bytes.unsafeWrap(chunk.toArray)
            val req = new InstallSnapshotRequest(
              term = effectiveTerm,
              leaderId = leaderId,
              lastIncludedIndex = snapshot.lastIncludedIndex,
              lastIncludedTerm = snapshot.lastIncludedTerm,
              offset = offset,
              payload = payload,
              done = isLast
            )
            val sendEff =
              logger.debug(
                s"Sending snapshot chunk ${idx + 1} to ${target.value} (offset=$offset, done=$isLast)"
              ) *>
                F.timeoutTo(
                  transport.send(target, req),
                  chunkTimeout,
                  F.raiseError(
                    new TimeoutException(
                      s"Snapshot chunk ${idx + 1} send to ${target.value} timed out after $chunkTimeout"
                    )
                  )
                )

            Pull.eval(sendEff) >> {
              nextOpt match
                case None                  => Pull.output1(idx + 1)
                case Some((_, peekedTail)) =>
                  go(peekedTail, idx + 1, offset + chunk.size.toLong)
            }
          }

        case None =>
          if idx == 0 then
            val req = new InstallSnapshotRequest(
              term = effectiveTerm,
              leaderId = leaderId,
              lastIncludedIndex = snapshot.lastIncludedIndex,
              lastIncludedTerm = snapshot.lastIncludedTerm,
              offset = 0L,
              payload = Bytes.empty,
              done = true
            )
            Pull
              .eval(
                logger.debug(s"Sending empty snapshot to ${target.value}") *>
                  transport.send(target, req).as(1)
              )
              .flatMap(Pull.output1)
          else Pull.done
      }

    go(chunkStream, 0, 0L).stream.compile.lastOrError
