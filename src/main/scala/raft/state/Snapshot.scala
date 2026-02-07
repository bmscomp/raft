package raft.state

/** Snapshot metadata and state for log compaction.
  *
  * A snapshot captures the replicated state machine at a specific log index,
  * allowing all preceding log entries to be discarded. This bounds the storage
  * required for the replicated log and speeds up recovery for new or lagging
  * nodes.
  *
  * @param lastIncludedIndex
  *   the index of the last log entry included in this snapshot
  * @param lastIncludedTerm
  *   the term of the last log entry included in this snapshot
  * @param data
  *   serialized state machine data at the snapshot point
  * @param config
  *   optional cluster configuration at the time the snapshot was taken
  * @see
  *   [[PendingSnapshot]] for incremental snapshot installation
  * @see
  *   [[raft.message.RaftMessage.InstallSnapshotRequest]] for the RPC that
  *   transfers snapshots
  */
case class Snapshot(
    lastIncludedIndex: Long,
    lastIncludedTerm: Long,
    data: Array[Byte],
    config: Option[ClusterConfig] = None
)

/** Accumulator for a snapshot being installed incrementally via InstallSnapshot
  * RPC.
  *
  * The leader transfers snapshots in chunks. This class buffers received chunks
  * until all data has arrived, at which point [[toSnapshot]] produces the
  * complete [[Snapshot]].
  *
  * @param lastIncludedIndex
  *   the index of the last log entry in the snapshot
  * @param lastIncludedTerm
  *   the term of the last log entry in the snapshot
  * @param offset
  *   the byte offset for the next expected chunk
  * @param chunks
  *   the chunks received so far, in order
  * @see
  *   [[Snapshot]] for the finalized snapshot representation
  */
case class PendingSnapshot(
    lastIncludedIndex: Long,
    lastIncludedTerm: Long,
    offset: Long,
    chunks: Vector[Array[Byte]]
):
  /** Append a new data chunk and advance the offset.
    *
    * @param chunk
    *   the next chunk of snapshot data received from the leader
    * @return
    *   an updated [[PendingSnapshot]] with the chunk appended
    */
  def appendChunk(chunk: Array[Byte]): PendingSnapshot =
    copy(offset = offset + chunk.length, chunks = chunks :+ chunk)

  /** Assemble the buffered chunks into a complete [[Snapshot]].
    *
    * @return
    *   a finalized snapshot with all chunks concatenated into `data`
    */
  def toSnapshot: Snapshot =
    Snapshot(
      lastIncludedIndex = lastIncludedIndex,
      lastIncludedTerm = lastIncludedTerm,
      data = chunks.flatten.toArray
    )
