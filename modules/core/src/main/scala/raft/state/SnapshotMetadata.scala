package raft.state

import java.nio.ByteBuffer

/** Pure, immutable snapshot metadata and optional payload used by the
  * deterministic consensus core (§7).
  *
  * Backed by [[Bytes]] so structural equality (`==`), hashing, and immutability
  * hold automatically without manual `Array` equality overrides.
  */
final case class SnapshotMetadata(
    lastIncludedIndex: Long,
    lastIncludedTerm: Long,
    config: Option[ClusterConfig] = None,
    payload: Bytes = Bytes.empty
):
  require(
    lastIncludedIndex >= 0,
    s"lastIncludedIndex must be >= 0, got $lastIncludedIndex"
  )
  require(
    lastIncludedTerm >= 0,
    s"lastIncludedTerm must be >= 0, got $lastIncludedTerm"
  )

  /** Defensive `Array[Byte]` copy of [[payload]]. */
  def data: Array[Byte] = payload.toArray

  /** Typed [[LogIndex]] view of [[lastIncludedIndex]]. */
  def typedIndex: LogIndex = LogIndex.unsafeFrom(lastIncludedIndex)

  /** Typed [[Term]] view of [[lastIncludedTerm]]. */
  def typedTerm: Term = Term.unsafeFrom(lastIncludedTerm)

object SnapshotMetadata:
  private val EnvelopeMagic: Int = 0x52534e50 // "RSNP"
  private val EnvelopeVersion: Byte = 1

  /** Construct [[SnapshotMetadata]] from an `Array[Byte]` (defensively copied).
    */
  def apply(
      lastIncludedIndex: Long,
      lastIncludedTerm: Long,
      config: Option[ClusterConfig],
      data: Array[Byte]
  ): SnapshotMetadata =
    new SnapshotMetadata(
      lastIncludedIndex,
      lastIncludedTerm,
      config,
      Bytes.copyFrom(data)
    )

  /** Validate coordinates purely without throwing exceptions. */
  def validate(
      lastIncludedIndex: Long,
      lastIncludedTerm: Long,
      config: Option[ClusterConfig] = None,
      payload: Bytes = Bytes.empty
  ): Validated[ConfigError, SnapshotMetadata] =
    Validated
      .mapN(
        Validated.check(
          lastIncludedIndex >= 0L,
          ConfigError.NegativeLogIndex(lastIncludedIndex)
        ),
        Validated.check(
          lastIncludedTerm >= 0L,
          ConfigError.NegativeTerm(lastIncludedTerm)
        )
      )((_, _) =>
        new SnapshotMetadata(
          lastIncludedIndex,
          lastIncludedTerm,
          config,
          payload
        )
      )

  /** Encode cluster membership and state-machine snapshot [[Bytes]] into a
    * self-describing `"RSNP"` v1 wire payload.
    */
  def encodePayloadBytes(
      config: Option[ClusterConfig],
      payload: Bytes
  ): Bytes =
    val cfgBytes = config.map(ClusterConfig.encodeBytes).getOrElse(Bytes.empty)
    val buf =
      ByteBuffer.allocate(4 + 1 + 1 + 4 + cfgBytes.length + 4 + payload.length)
    val _ = buf.putInt(EnvelopeMagic)
    val _ = buf.put(EnvelopeVersion)
    val _ = buf.put(if config.isDefined then 1.toByte else 0.toByte)
    val _ = buf.putInt(cfgBytes.length)
    cfgBytes.putInto(buf)
    val _ = buf.putInt(payload.length)
    payload.putInto(buf)
    Bytes.unsafeWrap(buf.array())

  /** Encode cluster membership and state-machine snapshot bytes into a single
    * self-describing wire payload for `InstallSnapshotRequest`.
    */
  def encodePayload(
      config: Option[ClusterConfig],
      data: Array[Byte]
  ): Array[Byte] =
    encodePayloadBytes(config, Bytes.copyFrom(data)).toArray

  /** Decode a wire [[Bytes]] payload produced by [[encodePayloadBytes]],
    * falling back gracefully to legacy raw `ClusterConfig` or raw state-machine
    * bytes.
    */
  def decodePayloadBytes(wire: Bytes): (Option[ClusterConfig], Bytes) =
    if wire.isEmpty then (None, Bytes.empty)
    else if wire.length >= 14 then
      val buf = wire.asReadOnlyByteBuffer
      val magic = buf.getInt()
      val version = buf.get()
      if magic == EnvelopeMagic && version == EnvelopeVersion then
        val hasCfg = buf.get() != 0
        val cfgLen = buf.getInt()
        if cfgLen >= 0 && cfgLen <= buf.remaining() - 4 then
          val cfgSlice = wire.slice(buf.position(), buf.position() + cfgLen)
          val _ = buf.position(buf.position() + cfgLen)
          val dataLen = buf.getInt()
          if dataLen >= 0 && dataLen <= buf.remaining() then
            val dataSlice = wire.slice(buf.position(), buf.position() + dataLen)
            val cfgOpt =
              if hasCfg then ClusterConfig.decodeBytes(cfgSlice).toOption
              else None
            (cfgOpt, dataSlice)
          else fallbackDecodeBytes(wire)
        else fallbackDecodeBytes(wire)
      else fallbackDecodeBytes(wire)
    else fallbackDecodeBytes(wire)

  /** Decode an `Array[Byte]` wire payload produced by [[encodePayload]]. */
  def decodePayload(wire: Array[Byte]): (Option[ClusterConfig], Array[Byte]) =
    val (cfgOpt, payload) = decodePayloadBytes(Bytes.copyFrom(wire))
    (cfgOpt, payload.toArray)

  private def fallbackDecodeBytes(wire: Bytes): (Option[ClusterConfig], Bytes) =
    ClusterConfig.decodeBytes(wire) match
      case Right(cfg) => (Some(cfg), Bytes.empty)
      case Left(_)    => (None, wire)

/** Pure, immutable in-memory chunk accumulator for incremental
  * `InstallSnapshot` RPCs (§7).
  */
final case class PendingSnapshot(
    lastIncludedIndex: Long,
    lastIncludedTerm: Long,
    offset: Long,
    chunks: Vector[Bytes] = Vector.empty,
    totalBytes: Long = 0L,
    config: Option[ClusterConfig] = None
):
  /** Append a received [[Bytes]] chunk and advance the byte offset. */
  def appendChunkBytes(chunk: Bytes): PendingSnapshot =
    copy(
      offset = offset + chunk.length.toLong,
      chunks = chunks :+ chunk,
      totalBytes = totalBytes + chunk.length.toLong
    )

  /** Append a received `Array[Byte]` chunk (defensively copied). */
  def appendChunk(chunk: Array[Byte]): PendingSnapshot =
    appendChunkBytes(Bytes.copyFrom(chunk))

  /** Extract the pure consensus metadata for this snapshot. */
  def metadata: SnapshotMetadata =
    SnapshotMetadata(lastIncludedIndex, lastIncludedTerm, config)

  /** Assemble all accumulated chunks into a single immutable [[Bytes]] value.
    */
  def toBytesValue: Bytes =
    Bytes.concat(chunks)

  /** Assemble all chunks into a fresh `Array[Byte]`. */
  def toBytes: Array[Byte] =
    toBytesValue.toArray
