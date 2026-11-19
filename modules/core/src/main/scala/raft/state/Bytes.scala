package raft.state

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.{Arrays, Base64}

/** Immutable, referentially transparent byte sequence with structural value
  * equality (`==`), SIMD-intrinsified hashing, and read-only `ByteBuffer`
  * views.
  *
  * Replaces raw mutable `Array[Byte]` fields across consensus ADTs (`Log`,
  * `SnapshotMetadata`, `PendingSnapshot`, `InstallSnapshotRequest`) so mutating
  * a caller's input or output array can never corrupt in-memory logs,
  * snapshots, or protocol messages.
  */
final class Bytes private (private val raw: Array[Byte]) extends Serializable:

  /** Number of bytes in this sequence. */
  def length: Int = raw.length

  /** Alias for [[length]]. */
  def size: Int = raw.length

  /** True iff `length == 0`. */
  def isEmpty: Boolean = raw.length == 0

  /** True iff `length > 0`. */
  def nonEmpty: Boolean = raw.length > 0

  /** Read the byte at 0-based index `idx`. */
  def apply(idx: Int): Byte = raw(idx)

  /** Return a fresh defensive `Array[Byte]` copy. */
  def toArray: Array[Byte] =
    if raw.length == 0 then Array.emptyByteArray
    else Arrays.copyOf(raw, raw.length)

  /** Return an immutable `IArray[Byte]` copy. */
  def toIArray: IArray[Byte] =
    IArray.unsafeFromArray(toArray)

  /** Zero-copy read-only `ByteBuffer` view over the underlying bytes. */
  def asReadOnlyByteBuffer: ByteBuffer =
    ByteBuffer.wrap(raw).asReadOnlyBuffer()

  /** Zero-copy slice `[from, until)` clamped to `[0, length]`. */
  def slice(from: Int, until: Int): Bytes =
    val lo = math.max(0, math.min(from, raw.length))
    val hi = math.max(lo, math.min(until, raw.length))
    if lo == 0 && hi == raw.length then this
    else if lo == hi then Bytes.empty
    else new Bytes(Arrays.copyOfRange(raw, lo, hi))

  /** Concatenate `this` followed by `that`. */
  def ++(that: Bytes): Bytes =
    if this.isEmpty then that
    else if that.isEmpty then this
    else
      val out = new Array[Byte](this.raw.length + that.raw.length)
      System.arraycopy(this.raw, 0, out, 0, this.raw.length)
      System.arraycopy(that.raw, 0, out, this.raw.length, that.raw.length)
      new Bytes(out)

  /** Decode as UTF-8 text. */
  def utf8String: String =
    if raw.length == 0 then ""
    else new String(raw, StandardCharsets.UTF_8)

  /** Encode as standard RFC 4648 Base64 without copying the backing array. */
  def toBase64: String =
    if raw.length == 0 then ""
    else Base64.getEncoder.encodeToString(raw)

  /** Copy bytes into `dest` starting at `destPos` without intermediate array
    * allocation.
    */
  private[raft] def copyToArray(dest: Array[Byte], destPos: Int): Unit =
    if raw.length > 0 then System.arraycopy(raw, 0, dest, destPos, raw.length)

  /** Copy bytes into `buf` without intermediate array allocation. */
  private[raft] def putInto(buf: ByteBuffer): Unit =
    if raw.length > 0 then
      val _ = buf.put(raw)

  override def equals(other: Any): Boolean = other match
    case that: Bytes => Arrays.equals(this.raw, that.raw)
    case _           => false

  override lazy val hashCode: Int = Arrays.hashCode(raw)

  override def toString: String =
    s"Bytes(len=${raw.length})"

object Bytes:
  /** Canonical zero-length [[Bytes]] singleton. */
  val empty: Bytes = new Bytes(Array.emptyByteArray)

  /** Defensively copy `arr` into an immutable [[Bytes]] value. */
  def copyFrom(arr: Array[Byte]): Bytes =
    if arr.length == 0 then empty
    else new Bytes(Arrays.copyOf(arr, arr.length))

  /** Construct [[Bytes]] from literal byte arguments. */
  def of(bytes: Byte*): Bytes =
    if bytes.isEmpty then empty
    else new Bytes(bytes.toArray)

  /** Construct [[Bytes]] from an immutable `IArray[Byte]`. */
  def fromIArray(arr: IArray[Byte]): Bytes =
    if arr.isEmpty then empty
    else
      val copy = new Array[Byte](arr.length)
      val _ = arr.copyToArray(copy)
      new Bytes(copy)

  /** Encode `s` as UTF-8 [[Bytes]]. */
  def fromUtf8(s: String): Bytes =
    if s.isEmpty then empty
    else new Bytes(s.getBytes(StandardCharsets.UTF_8))

  /** Decode RFC 4648 Base64 text into [[Bytes]] without throwing exceptions. */
  def fromBase64(b64: String): Either[String, Bytes] =
    if b64.isEmpty then Right(empty)
    else
      try Right(new Bytes(Base64.getDecoder.decode(b64)))
      catch
        case e: IllegalArgumentException =>
          Left(Option(e.getMessage).getOrElse("Malformed Base64 payload"))

  /** Concatenate a sequence of [[Bytes]] chunks in a single allocation. */
  def concat(chunks: Seq[Bytes]): Bytes =
    val total = chunks.foldLeft(0L)((acc, c) => acc + c.length.toLong)
    if total == 0L then empty
    else
      val out = new Array[Byte](math.toIntExact(total))
      val _ = chunks.foldLeft(0) { (offset, chunk) =>
        chunk.copyToArray(out, offset)
        offset + chunk.length
      }
      new Bytes(out)

  /** Package-private zero-copy wrapper for arrays freshly allocated inside
    * `raft` and never mutated afterwards.
    */
  private[raft] def unsafeWrap(freshArray: Array[Byte]): Bytes =
    if freshArray.length == 0 then empty
    else new Bytes(freshArray)
