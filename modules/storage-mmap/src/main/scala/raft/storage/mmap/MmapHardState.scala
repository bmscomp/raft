package raft.storage.mmap

import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, StandardOpenOption}

import raft.state.{NodeId, Term}
import raft.storage.HardState

/** Memory-mapped hard state file for persisting `currentTerm` and `votedFor`.
  *
  * The file is exactly 280 bytes — a tiny mmap that stays resident in the page
  * cache. Every write overwrites the file contents and calls `force()` (msync)
  * for durability before returning.
  *
  * '''Binary layout''':
  * {{{
  *   Offset  Size   Field
  *   0       8      currentTerm (big-endian Long)
  *   8       4      votedForLen (length of votedFor string, 0 = no vote)
  *   12      256    votedFor (UTF-8, padded)
  *   268     4      crc32 (over bytes 0..267)
  *   272     8      padding
  * }}}
  *
  * @param mapped
  *   the memory-mapped region of the hard state file
  * @param arena
  *   the FFM arena managing this mapping's lifetime
  */
class MmapHardState private (
    private val mapped: MemorySegment,
    private val arena: Arena
):

  /** Read the current hard state from the memory map. */
  def read: HardState =
    val term = mapped.get(ValueLayout.JAVA_LONG, 0)
    val votedForLen = mapped.get(ValueLayout.JAVA_INT, 8)
    val votedFor =
      if votedForLen <= 0 then None
      else
        val bytes = new Array[Byte](votedForLen)
        MemorySegment.copy(
          mapped,
          ValueLayout.JAVA_BYTE,
          12,
          bytes,
          0,
          votedForLen
        )
        Some(NodeId(new String(bytes, StandardCharsets.UTF_8)))
    HardState(Term.unsafeFrom(term), votedFor)

  /** Write the hard state and fsync to durable storage. */
  def write(state: HardState): Unit =
    mapped.set(ValueLayout.JAVA_LONG, 0, state.currentTerm.value)
    state.votedFor match
      case Some(nodeId) =>
        val bytes = nodeId.value.getBytes(StandardCharsets.UTF_8)
        val len = math.min(bytes.length, 256)
        mapped.set(ValueLayout.JAVA_INT, 8, len)
        MemorySegment.copy(bytes, 0, mapped, ValueLayout.JAVA_BYTE, 12, len)
      case None =>
        mapped.set(ValueLayout.JAVA_INT, 8, 0)

    // Write CRC
    val dataBytes = new Array[Byte](268)
    MemorySegment.copy(mapped, ValueLayout.JAVA_BYTE, 0, dataBytes, 0, 268)
    val crc = new java.util.zip.CRC32()
    crc.update(dataBytes)
    mapped.set(ValueLayout.JAVA_INT, 268, crc.getValue.toInt)

    mapped.force()

  /** Close the memory mapping. */
  def close(): Unit =
    arena.close()

object MmapHardState:

  /** Open or create the hard state file at the given path.
    *
    * If the file doesn't exist, it is created with empty hard state.
    *
    * @param path
    *   the filesystem path for the hard state file
    * @return
    *   a memory-mapped hard state manager
    */
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
      val mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0, 280, arena)

      val hs = new MmapHardState(mapped, arena)
      if !exists then hs.write(HardState.empty)
      hs
    finally channel.close()
