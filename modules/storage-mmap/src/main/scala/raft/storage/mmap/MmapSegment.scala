package raft.storage.mmap

import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.channels.FileChannel
import java.nio.file.{Path, StandardOpenOption}
import java.util.zip.CRC32

import raft.state.{Log, LogType}

/** Magic bytes identifying a valid WAL segment file ("RWAL"). */
private[mmap] val SegmentMagic: Int = 0x5257414c

/** Current binary format version. */
private[mmap] val SegmentVersion: Int = 1

/** Size of the segment file header in bytes. */
private[mmap] val HeaderSize: Int =
  24 // magic(4) + version(4) + firstIndex(8) + entryCount(8)

/** Fixed overhead per entry record (excluding variable-length data). */
private[mmap] val EntryOverhead: Int =
  29 // recordLen(4) + index(8) + term(8) + logType(1) + dataLen(4) + crc(4)

/** A single WAL segment backed by a memory-mapped file via the Foreign Function
  * & Memory API.
  *
  * Each segment stores a contiguous range of log entries in a packed binary
  * format with CRC32 integrity checks. The segment is mapped into memory using
  * `FileChannel.map()` with an `Arena` for deterministic lifecycle management.
  *
  * '''Write path''': `appendEntry` writes at the current write offset using
  * `MemorySegment.set()` — zero-copy, no intermediate buffers. After a batch of
  * appends, call `force()` to msync the data to durable storage.
  *
  * '''Read path''': `readEntry` seeks to the given file offset and reads the
  * entry fields using `ValueLayout` accessors — also zero-copy via the page
  * cache.
  *
  * @param path
  *   filesystem path of this segment file
  * @param arena
  *   the FFM arena managing this segment's mapped memory lifetime
  * @param mapped
  *   the memory-mapped region of the file
  * @param firstIndex
  *   the first log index stored in this segment
  * @param writeOffset
  *   the current byte offset for the next append (mutable, thread-safe via
  *   caller synchronization)
  * @param entryCount
  *   the number of entries in this segment
  */
class MmapSegment private (
    val path: Path,
    private val arena: Arena,
    private val mapped: MemorySegment,
    val firstIndex: Long,
    private var writeOffset: Long,
    private var entryCount: Long
):

  /** Read a log entry at the given file offset, with CRC32 validation.
    *
    * Use this on recovery paths where corruption detection is essential. For
    * hot-path reads during normal operation, prefer `readEntryUnchecked`.
    *
    * @param offset
    *   byte offset within the segment file
    * @return
    *   the deserialized log entry
    */
  def readEntry(offset: Long): Log =
    val recordLen = mapped.get(ValueLayout.JAVA_INT_UNALIGNED, offset)
    val index = mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + 4)
    val term = mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + 12)
    val logTypeByte = mapped.get(ValueLayout.JAVA_BYTE, offset + 20)
    val dataLen = mapped.get(ValueLayout.JAVA_INT_UNALIGNED, offset + 21)
    val data = new Array[Byte](dataLen)
    MemorySegment.copy(
      mapped,
      ValueLayout.JAVA_BYTE,
      offset + 25,
      data,
      0,
      dataLen
    )
    val storedCrc =
      mapped.get(ValueLayout.JAVA_INT_UNALIGNED, offset + 25 + dataLen)

    val computedCrc = computeCrc(offset + 4, recordLen - 8)
    if storedCrc != computedCrc then
      throw CorruptedEntryException(path, offset, index, storedCrc, computedCrc)

    Log(index, term, LogType.fromOrdinal(logTypeByte.toInt), data)

  /** Read a log entry without CRC validation.
    *
    * Faster than `readEntry` — skips the checksum computation and the
    * intermediate buffer allocation. Safe to use on entries that were written
    * by this process (i.e., not yet recovered from a crash).
    */
  def readEntryUnchecked(offset: Long): Log =
    val index = mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + 4)
    val term = mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + 12)
    val logTypeByte = mapped.get(ValueLayout.JAVA_BYTE, offset + 20)
    val dataLen = mapped.get(ValueLayout.JAVA_INT_UNALIGNED, offset + 21)
    val data = new Array[Byte](dataLen)
    MemorySegment.copy(
      mapped,
      ValueLayout.JAVA_BYTE,
      offset + 25,
      data,
      0,
      dataLen
    )
    Log(index, term, LogType.fromOrdinal(logTypeByte.toInt), data)

  /** Append a log entry at the current write position.
    *
    * Does NOT call `force()` — the caller should batch multiple appends and
    * call `force()` once after the batch for efficiency.
    *
    * @param log
    *   the entry to write
    * @return
    *   the file offset where the entry was written (for the index)
    */
  def appendEntry(log: Log): Long =
    val pos = writeRawEntry(log)
    mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, 16, entryCount)
    pos

  /** Append a batch of log entries in a tight loop.
    *
    * Writes all entries sequentially, updating the header entry count once at
    * the end. Returns `(logIndex, fileOffset)` pairs for index updates. The
    * caller must call `force()` or `forceRange()` after this method.
    *
    * @param entries
    *   the entries to write
    * @return
    *   vector of `(logIndex, fileOffset)` pairs
    */
  def appendBatch(entries: Seq[Log]): Vector[(Long, Long)] =
    val results = Vector.newBuilder[(Long, Long)]
    entries.foreach { log =>
      val pos = writeRawEntry(log)
      results += ((log.index, pos))
    }
    mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, 16, entryCount)
    results.result()

  /** Flush all pending writes to durable storage (msync). */
  def force(): Unit =
    mapped.force()

  /** Flush only the bytes in `[fromOffset, toOffset)` to durable storage.
    *
    * Currently delegates to full `force()` since `MemorySegment` does not
    * expose range-based msync. The method boundary exists for future
    * platform-specific optimization (e.g., direct msync syscall via FFM).
    */
  def forceRange(fromOffset: Long, toOffset: Long): Unit =
    val _ = (fromOffset, toOffset)
    mapped.force()

  /** Truncate the segment at the given file offset.
    *
    * Resets the write pointer to `offset` so subsequent appends overwrite the
    * truncated data. Used during log conflict resolution.
    *
    * @param offset
    *   the byte offset to truncate at (entries at and after this offset are
    *   discarded)
    * @param newEntryCount
    *   the corrected entry count after truncation
    */
  def truncateAt(offset: Long, newEntryCount: Long): Unit =
    writeOffset = offset
    entryCount = newEntryCount
    mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, 16, entryCount)
    force()

  /** Current write position in the segment file. */
  def currentWriteOffset: Long = writeOffset

  /** Number of entries currently stored. */
  def currentEntryCount: Long = entryCount

  /** The total mapped size of this segment. */
  def capacity: Long = mapped.byteSize()

  /** Remaining capacity in bytes. */
  def remaining: Long = mapped.byteSize() - writeOffset

  /** Replay all entries in this segment, invoking `callback` for each.
    *
    * Used during startup to rebuild the in-memory index.
    *
    * @param callback
    *   invoked with `(log, fileOffset)` for each entry
    */
  def replay(callback: (Log, Long) => Unit): Unit =
    var pos = HeaderSize.toLong
    var count = 0L
    while count < entryCount do
      val log = readEntry(pos)
      callback(log, pos)
      val recordLen = mapped.get(ValueLayout.JAVA_INT_UNALIGNED, pos)
      pos += recordLen
      count += 1

  /** Close this segment, releasing its memory mapping. */
  def close(): Unit =
    arena.close()

  /** Write a single entry without updating the header. Shared by `appendEntry`
    * and `appendBatch`.
    */
  private def writeRawEntry(log: Log): Long =
    val recordLen = EntryOverhead + log.data.length
    val pos = writeOffset

    mapped.set(ValueLayout.JAVA_INT_UNALIGNED, pos, recordLen)
    mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, pos + 4, log.index)
    mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, pos + 12, log.term)
    mapped.set(ValueLayout.JAVA_BYTE, pos + 20, log.logType.ordinal.toByte)
    mapped.set(ValueLayout.JAVA_INT_UNALIGNED, pos + 21, log.data.length)
    if log.data.length > 0 then
      MemorySegment.copy(
        log.data,
        0,
        mapped,
        ValueLayout.JAVA_BYTE,
        pos + 25,
        log.data.length
      )

    val crc = computeCrc(pos + 4, recordLen - 8)
    mapped.set(ValueLayout.JAVA_INT_UNALIGNED, pos + 25 + log.data.length, crc)

    writeOffset = pos + recordLen
    entryCount += 1
    pos

  private def computeCrc(offset: Long, length: Int): Int =
    val crc = new CRC32()
    crc.update(mapped.asSlice(offset, length.toLong).asByteBuffer())
    crc.getValue.toInt

/** Error raised when a WAL entry fails CRC validation. */
case class CorruptedEntryException(
    segmentPath: Path,
    offset: Long,
    logIndex: Long,
    storedCrc: Int,
    computedCrc: Int
) extends RuntimeException(
      s"Corrupted WAL entry at $segmentPath offset=$offset index=$logIndex: " +
        s"stored CRC=0x${storedCrc.toHexString} computed CRC=0x${computedCrc.toHexString}"
    )

object MmapSegment:

  /** Default segment size: 64 MB. */
  val DefaultSegmentSize: Long = 64L * 1024 * 1024

  /** Create a new segment file and memory-map it.
    *
    * @param path
    *   filesystem path for the new segment
    * @param firstIndex
    *   the first log index that will be stored in this segment
    * @param size
    *   the segment file size in bytes (pre-allocated)
    * @return
    *   a new writable segment
    */
  def create(
      path: Path,
      firstIndex: Long,
      size: Long = DefaultSegmentSize
  ): MmapSegment =
    val channel = FileChannel.open(
      path,
      StandardOpenOption.CREATE,
      StandardOpenOption.READ,
      StandardOpenOption.WRITE
    )
    try
      val arena = Arena.ofShared()
      val mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0, size, arena)

      // Write header
      mapped.set(ValueLayout.JAVA_INT_UNALIGNED, 0, SegmentMagic)
      mapped.set(ValueLayout.JAVA_INT_UNALIGNED, 4, SegmentVersion)
      mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, 8, firstIndex)
      mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, 16, 0L) // entryCount = 0
      mapped.force()

      new MmapSegment(path, arena, mapped, firstIndex, HeaderSize.toLong, 0L)
    finally channel.close()

  /** Open an existing segment file and memory-map it.
    *
    * Validates the magic bytes and version, then reads the header metadata.
    *
    * @param path
    *   the existing segment file path
    * @param readOnly
    *   if true, the segment is mapped read-only (for sealed segments)
    * @return
    *   the opened segment
    */
  def open(path: Path, readOnly: Boolean = false): MmapSegment =
    val options =
      if readOnly then java.util.Set.of(StandardOpenOption.READ)
      else java.util.Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
    val channel = FileChannel.open(path, options)
    try
      val size = channel.size()
      val arena = Arena.ofShared()
      val mode =
        if readOnly then FileChannel.MapMode.READ_ONLY
        else FileChannel.MapMode.READ_WRITE
      val mapped = channel.map(mode, 0, size, arena)

      val magic = mapped.get(ValueLayout.JAVA_INT_UNALIGNED, 0)
      if magic != SegmentMagic then
        throw new IllegalStateException(
          s"Invalid segment file $path: bad magic 0x${magic.toHexString}"
        )
      val version = mapped.get(ValueLayout.JAVA_INT_UNALIGNED, 4)
      if version != SegmentVersion then
        throw new IllegalStateException(
          s"Unsupported segment version $version in $path (expected $SegmentVersion)"
        )

      val firstIndex = mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, 8)
      val entryCount = mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, 16)

      // Calculate the write offset by scanning to the end of entries
      var writeOff = HeaderSize.toLong
      var count = 0L
      while count < entryCount do
        val recordLen = mapped.get(ValueLayout.JAVA_INT_UNALIGNED, writeOff)
        writeOff += recordLen
        count += 1

      new MmapSegment(path, arena, mapped, firstIndex, writeOff, entryCount)
    finally channel.close()
