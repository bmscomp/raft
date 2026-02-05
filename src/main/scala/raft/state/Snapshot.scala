package raft.state

/**
 * Snapshot metadata and state for log compaction.
 * 
 * @param lastIncludedIndex last log index included in snapshot
 * @param lastIncludedTerm term of last included log entry
 * @param data serialized state machine data
 * @param config cluster configuration at snapshot time
 */
case class Snapshot(
  lastIncludedIndex: Long,
  lastIncludedTerm: Long,
  data: Array[Byte],
  config: Option[ClusterConfig] = None
)

/**
 * Pending snapshot installation state (during InstallSnapshot RPC).
 */
case class PendingSnapshot(
  lastIncludedIndex: Long,
  lastIncludedTerm: Long,
  offset: Long,
  chunks: Vector[Array[Byte]]
):
  def appendChunk(chunk: Array[Byte]): PendingSnapshot =
    copy(offset = offset + chunk.length, chunks = chunks :+ chunk)
  
  def toSnapshot: Snapshot =
    Snapshot(
      lastIncludedIndex = lastIncludedIndex,
      lastIncludedTerm = lastIncludedTerm,
      data = chunks.flatten.toArray
    )
