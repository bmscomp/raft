package raft.compaction

import cats.effect.Async
import cats.effect.syntax.concurrent.*
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import raft.message.RaftMessage
import raft.message.RaftMessage.InstallSnapshotRequest
import raft.state.{NodeId, Snapshot}
import raft.spi.RaftTransport
import fs2.{Chunk, Pull, Stream}

import scala.concurrent.duration.*

/** Handles chunked snapshot transfer from leader to lagging followers.
  *
  * When a follower's log is too far behind (its next index is before the
  * leader's earliest log entry), the leader sends its latest snapshot using the
  * `InstallSnapshot` RPC. This class splits the snapshot data into fixed-size
  * chunks and sends them as a sequence of [[InstallSnapshotRequest]] messages.
  *
  * Back-pressure is controlled by the `maxInFlight` parameter: at most
  * `maxInFlight` chunk sends are in progress concurrently. This prevents
  * overwhelming slow followers while still allowing pipelined transfers.
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @param chunkSize
  *   maximum number of bytes per chunk
  * @param transport
  *   the transport layer for sending messages
  * @param logger
  *   structured logger
  * @param chunkTimeout
  *   maximum time to wait for each chunk send before failing
  * @param maxInFlight
  *   maximum number of concurrent in-flight chunk sends (default: 4)
  */
class SnapshotTransfer[F[_]: Async](
    chunkSize: Int,
    transport: RaftTransport[F],
    logger: Logger[F],
    chunkTimeout: FiniteDuration = 30.seconds,
    maxInFlight: Int = 4
):

  /** Send a complete snapshot to a follower in chunks.
    *
    * Each chunk is sent as a separate `InstallSnapshotRequest`. The final chunk
    * is marked with `done = true`. At most [[maxInFlight]] chunks are in
    * transit concurrently.
    *
    * @param leaderId
    *   the sending leader's node id
    * @param target
    *   the receiving follower's node id
    * @param snapshot
    *   the snapshot to transfer
    * @return
    *   the total number of chunks sent
    */
  def sendSnapshot(
      leaderId: NodeId,
      target: NodeId,
      snapshot: Snapshot[F]
  ): F[Int] =
    val chunks = snapshot.data.chunkN(chunkSize)

    def go(
        s: Stream[F, Chunk[Byte]],
        idx: Int,
        offset: Long
    ): Pull[F, Int, Unit] =
      s.pull.uncons1.flatMap {
        case Some((chunk, tail)) =>
          tail.pull.peek1.flatMap { nextOpt =>
            val isLast = nextOpt.isEmpty
            val req = InstallSnapshotRequest(
              term = snapshot.lastIncludedTerm,
              leaderId = leaderId,
              lastIncludedIndex = snapshot.lastIncludedIndex,
              lastIncludedTerm = snapshot.lastIncludedTerm,
              offset = offset,
              data = chunk.toArray,
              done = isLast
            )

            val logAction = logger.debug(
              s"Sending snapshot chunk ${idx + 1} to ${target.value} (offset=$offset, done=$isLast)"
            )

            Pull.eval(
              logAction >> Async[F].timeoutTo(
                transport.send(target, req),
                chunkTimeout,
                Async[F].raiseError(
                  new java.util.concurrent.TimeoutException(
                    s"Snapshot chunk ${idx + 1} send to ${target.value} timed out after $chunkTimeout"
                  )
                )
              )
            ) >> {
              if isLast then Pull.output1(idx + 1)
              else go(tail, idx + 1, offset + chunk.size)
            }
          }
        case None =>
          if idx == 0 then
            // Empty snapshot: send one empty chunk with done=true
            val req = InstallSnapshotRequest(
              term = snapshot.lastIncludedTerm,
              leaderId = leaderId,
              lastIncludedIndex = snapshot.lastIncludedIndex,
              lastIncludedTerm = snapshot.lastIncludedTerm,
              offset = 0,
              data = Array.emptyByteArray,
              done = true
            )
            Pull
              .eval(
                logger.debug(s"Sending empty snapshot to ${target.value}") >>
                  transport.send(target, req).as(1)
              )
              .flatMap(count => Pull.output1(count))
          else Pull.done
      }

    go(chunks, 0, 0L).stream.compile.lastOrError

  /** Send a snapshot using pipelined chunk sends with bounded concurrency.
    *
    * Unlike [[sendSnapshot]], this method pre-segments the entire snapshot
    * stream into chunks and sends up to [[maxInFlight]] in parallel. This is
    * faster for large snapshots over high-latency networks but requires the
    * receiver to handle out-of-order delivery (offset-based reassembly).
    *
    * @param leaderId
    *   the sending leader's node id
    * @param target
    *   the receiving follower's node id
    * @param snapshot
    *   the snapshot to transfer
    * @return
    *   the total number of chunks sent
    */
  def sendSnapshotPipelined(
      leaderId: NodeId,
      target: NodeId,
      snapshot: Snapshot[F]
  ): F[Int] =
    snapshot.data
      .chunkN(chunkSize)
      .compile
      .toVector
      .flatMap { allChunks =>
        if allChunks.isEmpty then
          val req = InstallSnapshotRequest(
            term = snapshot.lastIncludedTerm,
            leaderId = leaderId,
            lastIncludedIndex = snapshot.lastIncludedIndex,
            lastIncludedTerm = snapshot.lastIncludedTerm,
            offset = 0,
            data = Array.emptyByteArray,
            done = true
          )
          logger.debug(s"Sending empty snapshot to ${target.value}") >>
            transport.send(target, req).as(1)
        else
          var currentOffset = 0L
          val requests = allChunks.zipWithIndex.map { case (chunk, idx) =>
            val off = currentOffset
            val isDone = idx == allChunks.size - 1
            val req = InstallSnapshotRequest(
              term = snapshot.lastIncludedTerm,
              leaderId = leaderId,
              lastIncludedIndex = snapshot.lastIncludedIndex,
              lastIncludedTerm = snapshot.lastIncludedTerm,
              offset = off,
              data = chunk.toArray,
              done = isDone
            )
            currentOffset += chunk.size
            (req: RaftMessage, off, isDone)
          }

          requests
            .parTraverseN(maxInFlight) { case (req, off, isDone) =>
              logger.debug(
                s"Pipelined chunk to ${target.value} (offset=$off, done=$isDone)"
              ) >> Async[F]
                .timeoutTo(
                  transport.send(target, req),
                  chunkTimeout,
                  Async[F].raiseError(
                    new java.util.concurrent.TimeoutException(
                      s"Pipelined snapshot chunk to ${target.value} timed out after $chunkTimeout"
                    )
                  )
                )
                .as(1)
            }
            .map(_.size)
      }
