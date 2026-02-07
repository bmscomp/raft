package raft.state

import fs2.Stream

/** Snapshot metadata and state for log compaction (§7).
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @param lastIncludedIndex
  *   the index of the last log entry included in this snapshot
  * @param lastIncludedTerm
  *   the term of the last log entry included in this snapshot
  * @param data
  *   stream of serialized state machine data at the snapshot point
  * @param config
  *   optional cluster configuration at the time the snapshot was taken
  */
case class Snapshot[F[_]](
    lastIncludedIndex: Long,
    lastIncludedTerm: Long,
    data: Stream[F, Byte],
    config: Option[ClusterConfig] = None
)

/** Accumulator for a snapshot being installed incrementally via
  * `InstallSnapshot` RPC (§7).
  *
  * @see
  *   [[Snapshot]] for the finalized snapshot representation
  */
case class PendingSnapshot(
    lastIncludedIndex: Long,
    lastIncludedTerm: Long,
    offset: Long,
    chunks: Vector[Array[Byte]]
):
  /** Append a new data chunk and advance the offset. */
  def appendChunk(chunk: Array[Byte]): PendingSnapshot =
    copy(offset = offset + chunk.length, chunks = chunks :+ chunk)

  /** Assemble the buffered chunks into a complete [[Snapshot]]. */
  def toSnapshot[F[_]]: Snapshot[F] =
    Snapshot(
      lastIncludedIndex = lastIncludedIndex,
      lastIncludedTerm = lastIncludedTerm,
      data = Stream
        .emits(chunks)
        .flatMap(chunk => Stream.chunk(fs2.Chunk.array(chunk)))
    )
