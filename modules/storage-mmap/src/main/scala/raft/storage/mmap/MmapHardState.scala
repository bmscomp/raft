package raft.storage.mmap

import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, StandardOpenOption}
import java.util.Arrays

import raft.state.{NodeId, RaftError, Term}
import raft.storage.HardState

/** Memory-mapped hard state file for persisting `currentTerm` and `votedFor`.
  *
  * The file is exactly 280 bytes — a tiny mmap that stays resident in the page
  * cache. Every write zero-fills unused `votedFor` bytes, computes a CRC32
  * checksum over `[0..268)`, and calls `force()` (`msync`) for durability
  * before returning. Every read verifies the CRC32 checksum and field bounds,
  * raising [[raft.state.RaftError.StorageCorruption]] on torn writes or bit
  * rot.
  *
  * '''Binary layout''':
  * {{{
  *   Offset  Size   Field
  *   0       8      currentTerm (big-endian Long, >= 0)
  *   8       4      votedForLen (UTF-8 byte length in [0..256], 0 = no vote)
  *   12      256    votedFor (UTF-8, zero-padded)
  *   268     4      crc32 (over bytes 0..267)
  *   272     8      reserved (zero)
  * }}}
  */
class MmapHardState private (
    private val mapped: MemorySegment,
    private val arena: Arena
):
  import MmapHardState.*

  /** Read and checksum-verify the persisted hard state from the memory map.
    *
    * @throws raft.state.RaftError.StorageCorruption
    *   if the CRC32 checksum mismatches or field bounds are invalid
    */
  def read: HardState =
    val dataBytes = new Array[Byte](PayloadBytes)
    MemorySegment.copy(
      mapped,
      ValueLayout.JAVA_BYTE,
      0,
      dataBytes,
      0,
      PayloadBytes
    )
    val storedCrc = mapped.get(ValueLayout.JAVA_INT, CrcOffset)

    // Fresh all-zero region before first write
    if storedCrc == 0 && isAllZero(dataBytes) then HardState.Empty
    else
      val crc = new java.util.zip.CRC32()
      crc.update(dataBytes)
      val computedCrc = crc.getValue.toInt
      if computedCrc != storedCrc then
        throw RaftError.StorageCorruption(
          f"MmapHardState CRC32 mismatch: stored=0x$storedCrc%08x, computed=0x$computedCrc%08x"
        )

      val term = mapped.get(ValueLayout.JAVA_LONG, 0)
      if term < 0L then
        throw RaftError.StorageCorruption(
          s"MmapHardState negative term: $term"
        )

      val votedForLen = mapped.get(ValueLayout.JAVA_INT, 8)
      if votedForLen < 0 || votedForLen > MaxVotedForBytes then
        throw RaftError.StorageCorruption(
          s"MmapHardState invalid votedForLen: $votedForLen (must be in 0..$MaxVotedForBytes)"
        )

      val votedFor =
        if votedForLen == 0 then None
        else
          val utf8 = Arrays.copyOfRange(dataBytes, 12, 12 + votedForLen)
          Some(NodeId(new String(utf8, StandardCharsets.UTF_8)))

      HardState(Term.unsafeFrom(term), votedFor)

  /** Atomically write `state`, zero-fill stale bytes, update CRC32, and `msync`
    * to durable storage.
    */
  def write(state: HardState): Unit =
    val votedBytes = state.votedFor match
      case Some(nodeId) =>
        val raw = nodeId.value.getBytes(StandardCharsets.UTF_8)
        require(
          raw.length <= MaxVotedForBytes,
          s"votedFor NodeId exceeds maximum $MaxVotedForBytes UTF-8 bytes (${raw.length})"
        )
        raw
      case None => Array.emptyByteArray

    val zeroPad = new Array[Byte](MaxVotedForBytes)
    if votedBytes.nonEmpty then
      System.arraycopy(votedBytes, 0, zeroPad, 0, votedBytes.length)

    mapped.set(ValueLayout.JAVA_LONG, 0, state.currentTerm.value)
    mapped.set(ValueLayout.JAVA_INT, 8, votedBytes.length)
    MemorySegment.copy(
      zeroPad,
      0,
      mapped,
      ValueLayout.JAVA_BYTE,
      12,
      MaxVotedForBytes
    )

    val dataBytes = new Array[Byte](PayloadBytes)
    MemorySegment.copy(
      mapped,
      ValueLayout.JAVA_BYTE,
      0,
      dataBytes,
      0,
      PayloadBytes
    )
    val crc = new java.util.zip.CRC32()
    crc.update(dataBytes)
    mapped.set(ValueLayout.JAVA_INT, CrcOffset, crc.getValue.toInt)
    mapped.set(ValueLayout.JAVA_LONG, 272, 0L)

    mapped.force()

  /** Close the memory mapping. */
  def close(): Unit =
    arena.close()

object MmapHardState:
  private val FileBytes: Long = 280L
  private val PayloadBytes: Int = 268
  private val MaxVotedForBytes: Int = 256
  private val CrcOffset: Long = 268L

  private def isAllZero(arr: Array[Byte]): Boolean =
    var i = 0
    var allZero = true
    while i < arr.length && allZero do
      if arr(i) != 0.toByte then allZero = false
      i += 1
    allZero

  /** Open or create the hard state file at the given path. */
  def open(path: Path): MmapHardState =
    val exists = java.nio.file.Files.exists(path)
    val channel = FileChannel.open(
      path,
      StandardOpenOption.CREATE,
      StandardOpenOption.READ,
      StandardOpenOption.WRITE
    )
    try
      val arena = Arena.ofShared()
      val mapped =
        channel.map(FileChannel.MapMode.READ_WRITE, 0, FileBytes, arena)

      val hs = new MmapHardState(mapped, arena)
      if !exists then hs.write(HardState.empty)
      hs
    finally channel.close()
