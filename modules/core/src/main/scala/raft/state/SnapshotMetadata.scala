package raft.state

/** Pure snapshot metadata used by the deterministic consensus core (§7).
  *
  * Unlike `Snapshot[F]` in `raft-runtime` (which streams raw state machine
  * bytes via `fs2.Stream`), `SnapshotMetadata` carries only the consensus
  * coordinates and cluster membership needed by [[raft.logic.RaftLogic]] and
  * [[raft.raw.RawNode]] — keeping `raft-core` completely free of I/O or
  * streaming dependencies.
  *
  * @param lastIncludedIndex
  *   the index of the last log entry covered by the snapshot
  * @param lastIncludedTerm
  *   the term of the last log entry covered by the snapshot
  * @param config
  *   optional cluster membership configuration at `lastIncludedIndex`
  */
final case class SnapshotMetadata(
    lastIncludedIndex: Long,
    lastIncludedTerm: Long,
    config: Option[ClusterConfig] = None
):
  require(
    lastIncludedIndex >= 0,
    s"lastIncludedIndex must be >= 0, got $lastIncludedIndex"
  )
  require(
    lastIncludedTerm >= 0,
    s"lastIncludedTerm must be >= 0, got $lastIncludedTerm"
  )

/** Pure in-memory chunk accumulator for incremental `InstallSnapshot` RPCs
  * (§7).
  *
  * Host systems with multi-GB state machines should stream snapshot chunks
  * directly to disk before notifying the consensus state machine with the
  * finalized [[SnapshotMetadata]].
  */
final case class PendingSnapshot(
    lastIncludedIndex: Long,
    lastIncludedTerm: Long,
    offset: Long,
    chunks: Vector[Array[Byte]],
    totalBytes: Long = 0L,
    config: Option[ClusterConfig] = None
):
  /** Append a received chunk and advance the byte offset. */
  def appendChunk(chunk: Array[Byte]): PendingSnapshot =
    copy(
      offset = offset + chunk.length,
      chunks = chunks :+ chunk,
      totalBytes = totalBytes + chunk.length
    )

  /** Extract the pure consensus metadata for this snapshot. */
  def metadata: SnapshotMetadata =
    SnapshotMetadata(lastIncludedIndex, lastIncludedTerm, config)

  /** Assemble all chunks into a contiguous byte array. */
  def toBytes: Array[Byte] =
    val out = new Array[Byte](totalBytes.toInt)
    var pos = 0
    chunks.foreach { c =>
      System.arraycopy(c, 0, out, pos, c.length)
      pos += c.length
    }
    out
