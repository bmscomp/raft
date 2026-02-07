package raft.state

/** Snapshot metadata and state for log compaction (§7).
  *
  * In a long-running Raft cluster the replicated log grows without bound. Log
  * compaction (§7) solves this by periodically capturing a snapshot of the
  * state machine and discarding all log entries up to that point.
  *
  * The `lastIncludedIndex` and `lastIncludedTerm` fields serve as a bookmark:
  * they record the point at which the snapshot was taken so that the node can
  * resume log replication from the correct position. When a follower is so far
  * behind that the leader no longer has the required log entries, the leader
  * sends an `InstallSnapshot` RPC instead of `AppendEntries`.
  *
  * The optional `config` captures the cluster membership at snapshot time,
  * ensuring that membership changes (§6) are not lost when log entries are
  * compacted away.
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

/** Accumulator for a snapshot being installed incrementally via
  * `InstallSnapshot` RPC (§7).
  *
  * State machine snapshots can be large (gigabytes for a database). The Raft
  * paper specifies chunk-based transfer so that snapshot installation does not
  * block the leader's event loop for an extended period. Each chunk is appended
  * to this accumulator; once all chunks arrive, [[toSnapshot]] assembles the
  * final [[Snapshot]].
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
