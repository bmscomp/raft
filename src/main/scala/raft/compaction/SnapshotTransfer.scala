package raft.compaction

import cats.effect.Async
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
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
  */
class SnapshotTransfer[F[_]: Async](
    chunkSize: Int,
    transport: RaftTransport[F],
    logger: Logger[F],
    chunkTimeout: FiniteDuration = 30.seconds
):

  /** Send a complete snapshot to a follower in chunks.
    *
    * Each chunk is sent as a separate `InstallSnapshotRequest`. The final chunk
    * is marked with `done = true`.
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
