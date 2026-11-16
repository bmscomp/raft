package raft.state

import java.nio.ByteBuffer
import java.util.Arrays

/** Pure snapshot metadata and optional in-memory payload used by the
  * deterministic consensus core (§7).
  *
  * Unlike `Snapshot[F]` in `raft-runtime` (which streams arbitrarily large
  * state machine snapshots via `fs2.Stream`), `SnapshotMetadata` carries the
  * consensus coordinates, cluster membership, and optional contiguous snapshot
  * bytes needed by [[raft.logic.RaftLogic]] and [[raft.raw.RawNode]] — keeping
  * `raft-core` completely free of I/O or streaming dependencies.
  *
  * @param lastIncludedIndex
  *   the index of the last log entry covered by the snapshot
  * @param lastIncludedTerm
  *   the term of the last log entry covered by the snapshot
  * @param config
  *   optional cluster membership configuration at `lastIncludedIndex`
  * @param data
  *   optional serialized state-machine snapshot payload (empty when streamed
  *   out-of-band)
  */
final case class SnapshotMetadata(
    lastIncludedIndex: Long,
    lastIncludedTerm: Long,
    config: Option[ClusterConfig] = None,
    data: Array[Byte] = Array.emptyByteArray
):
  require(
    lastIncludedIndex >= 0,
    s"lastIncludedIndex must be >= 0, got $lastIncludedIndex"
  )
  require(
    lastIncludedTerm >= 0,
    s"lastIncludedTerm must be >= 0, got $lastIncludedTerm"
  )

  override def equals(other: Any): Boolean = other match
    case that: SnapshotMetadata =>
      this.lastIncludedIndex == that.lastIncludedIndex &&
      this.lastIncludedTerm == that.lastIncludedTerm &&
      this.config == that.config &&
      Arrays.equals(this.data, that.data)
    case _ => false

  override def hashCode(): Int =
    31 * (31 * (31 * lastIncludedIndex.hashCode + lastIncludedTerm.hashCode) + config.hashCode) +
      Arrays.hashCode(data)

object SnapshotMetadata:
  private val EnvelopeMagic: Int = 0x52534e50 // "RSNP"
  private val EnvelopeVersion: Byte = 1

  /** Encode cluster membership and state-machine snapshot bytes into a single
    * self-describing wire payload for `InstallSnapshotRequest.data`.
    */
  def encodePayload(
      config: Option[ClusterConfig],
      data: Array[Byte]
  ): Array[Byte] =
    val cfgBytes =
      config.map(ClusterConfig.encode).getOrElse(Array.emptyByteArray)
    val buf =
      ByteBuffer.allocate(4 + 1 + 1 + 4 + cfgBytes.length + 4 + data.length)
    val _ = buf.putInt(EnvelopeMagic)
    val _ = buf.put(EnvelopeVersion)
    val _ = buf.put(if config.isDefined then 1.toByte else 0.toByte)
    val _ = buf.putInt(cfgBytes.length)
    val _ = buf.put(cfgBytes)
    val _ = buf.putInt(data.length)
    val _ = buf.put(data)
    buf.array()

  /** Decode a wire payload produced by [[encodePayload]], falling back
    * gracefully to legacy raw `ClusterConfig` or raw state-machine bytes.
    */
  def decodePayload(
      wire: Array[Byte]
  ): (Option[ClusterConfig], Array[Byte]) =
    if wire.isEmpty then (None, Array.emptyByteArray)
    else if wire.length >= 14 then
      val buf = ByteBuffer.wrap(wire)
      val magic = buf.getInt()
      val version = buf.get()
      if magic == EnvelopeMagic && version == EnvelopeVersion then
        val hasCfg = buf.get() != 0
        val cfgLen = buf.getInt()
        if cfgLen >= 0 && cfgLen <= buf.remaining() - 4 then
          val cfgBytes = new Array[Byte](cfgLen)
          val _ = buf.get(cfgBytes)
          val dataLen = buf.getInt()
          if dataLen >= 0 && dataLen <= buf.remaining() then
            val payload = new Array[Byte](dataLen)
            val _ = buf.get(payload)
            val cfgOpt =
              if hasCfg then ClusterConfig.decode(cfgBytes).toOption else None
            (cfgOpt, payload)
          else fallbackDecode(wire)
        else fallbackDecode(wire)
      else fallbackDecode(wire)
    else fallbackDecode(wire)

  private def fallbackDecode(
      wire: Array[Byte]
  ): (Option[ClusterConfig], Array[Byte]) =
    ClusterConfig.decode(wire) match
      case Right(cfg) => (Some(cfg), Array.emptyByteArray)
      case Left(_)    => (None, wire)

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
