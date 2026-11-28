package raft.storage.mmap

import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.channels.FileChannel
import java.nio.file.{Path, StandardOpenOption}
import java.util.zip.CRC32
import scala.annotation.tailrec

import raft.state.{Bytes, Log, LogType}

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

private[mmap] val LogTypeCount: Int = LogType.values.length

/** Result of crash-safe segment recovery (`openForRecovery`). */
private[mmap] final case class SegmentRecovery(
    segment: MmapSegment,
    entries: Vector[(Log, Long)],
    healedTornTail: Boolean
)

/** A single WAL segment backed by a memory-mapped file via the JDK Foreign
  * Function & Memory (`MemorySegment` / `Arena`) API.
  *
  *   - **Bounds-checked & CRC-first**: [[readEntry]] validates record bounds,
  *     `dataLen` consistency, `LogType` ordinal, and the 32-bit CRC32 checksum
  *     *before* allocating any heap payload buffer.
  *   - **Zero-allocation term reads**: [[readTerm]] reads the 8-byte `term`
  *     directly at `offset + 12` without deserializing `Log.data`.
  *   - **Torn-tail self-healing**: [[MmapSegment.openForRecovery]] detects
  *     unflushed/torn uncommitted tail records on the active segment and
  *     truncates them cleanly while failing fast with
  *     [[CorruptedEntryException]] on bit-rot or committed-prefix corruption.
  */
class MmapSegment private (
    val path: Path,
    private val arena: Arena,
    private val mapped: MemorySegment,
    val firstIndex: Long,
    private var writeOffset: Long,
    private var entryCount: Long
):

  /** Read only the 8-byte `term` field at `offset + 12` with zero heap
    * allocation (`O(1)`).
    */
  def readTerm(offset: Long): Long =
    mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + 12L)

  /** Read a log entry at `offset` with full structural bounds and CRC32
    * verification prior to heap allocation.
    */
  def readEntry(offset: Long): Log =
    val cap = mapped.byteSize()
    if offset < HeaderSize.toLong || offset + EntryOverhead.toLong > cap then
      throw CorruptedEntryException(path, offset, -1L, 0, -1)

    val recordLen = mapped.get(ValueLayout.JAVA_INT_UNALIGNED, offset)
    val index = mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + 4L)
    if recordLen < EntryOverhead || offset + recordLen.toLong > cap then
      throw CorruptedEntryException(path, offset, index, 0, -1)

    val term = mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + 12L)
    val logTypeByte = mapped.get(ValueLayout.JAVA_BYTE, offset + 20L).toInt
    val dataLen = mapped.get(ValueLayout.JAVA_INT_UNALIGNED, offset + 21L)
    if dataLen < 0 || EntryOverhead + dataLen != recordLen ||
      logTypeByte < 0 || logTypeByte >= LogTypeCount
    then throw CorruptedEntryException(path, offset, index, 0, -1)

    val storedCrc =
      mapped.get(
        ValueLayout.JAVA_INT_UNALIGNED,
        offset + 25L + dataLen.toLong
      )
    val computedCrc = computeCrc(offset + 4L, recordLen - 8)
    if storedCrc != computedCrc then
      throw CorruptedEntryException(path, offset, index, storedCrc, computedCrc)

    val payload =
      if dataLen == 0 then Bytes.empty
      else
        val arr = new Array[Byte](dataLen)
        MemorySegment.copy(
          mapped,
          ValueLayout.JAVA_BYTE,
          offset + 25L,
          arr,
          0,
          dataLen
        )
        Bytes.unsafeWrap(arr)

    Log(index, term, LogType.fromOrdinal(logTypeByte), payload)

  /** Read a previously validated in-process log entry without recomputing
    * CRC32.
    */
  def readEntryUnchecked(offset: Long): Log =
    val index = mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + 4L)
    val term = mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + 12L)
    val logTypeByte = mapped.get(ValueLayout.JAVA_BYTE, offset + 20L).toInt
    val dataLen = mapped.get(ValueLayout.JAVA_INT_UNALIGNED, offset + 21L)
    val payload =
      if dataLen <= 0 then Bytes.empty
      else
        val arr = new Array[Byte](dataLen)
        MemorySegment.copy(
          mapped,
          ValueLayout.JAVA_BYTE,
          offset + 25L,
          arr,
          0,
          dataLen
        )
        Bytes.unsafeWrap(arr)
    Log(index, term, LogType.fromOrdinal(logTypeByte), payload)

  /** Append a single log entry at the current write position. Flushes record
    * bytes before updating header `entryCount` so a crash cannot expose an
    * incremented count over unwritten data pages.
    */
  def appendEntry(log: Log): Long =
    val pos = writeRawEntry(log)
    force()
    mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, 16L, entryCount)
    pos

  /** Append a batch of log entries sequentially, flushing record payloads
    * before updating header `entryCount` once at the end.
    */
  def appendBatch(entries: Seq[Log]): Vector[(Long, Long)] =
    val results = Vector.newBuilder[(Long, Long)]
    entries.foreach { log =>
      val pos = writeRawEntry(log)
      results += ((log.index, pos))
    }
    force()
    mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, 16L, entryCount)
    results.result()

  /** Flush pending writes to durable storage (`msync`). */
  def force(): Unit =
    val _ = mapped.force()

  /** Flush bytes in `[fromOffset, toOffset)` to durable storage. */
  def forceRange(fromOffset: Long, toOffset: Long): Unit =
    val _ = (fromOffset, toOffset)
    val _ = mapped.force()

  /** Truncate the segment at `offset` with `newEntryCount` valid entries,
    * flushing the reduced header count first and then zero-filling stale tail
    * bytes so aborted records can never be resurrected or confuse recovery.
    */
  def truncateAt(offset: Long, newEntryCount: Long): Unit =
    val oldWriteOffset = writeOffset
    writeOffset = offset
    entryCount = newEntryCount
    mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, 16L, entryCount)
    force()
    if oldWriteOffset > offset then
      mapped.asSlice(offset, oldWriteOffset - offset).fill(0.toByte)
      force()

  /** Count physical records stored strictly before `targetOffset`
    * (`[HeaderSize, targetOffset)`), unaffected by logical
    * `WalIndexState.discardBefore` pruning.
    */
  def countEntriesBeforeOffset(targetOffset: Long): Long =
    val limit = math.min(targetOffset, writeOffset)
    @tailrec
    def loop(pos: Long, acc: Long): Long =
      if pos >= limit || pos + 4L > limit then acc
      else
        val recLen = mapped.get(ValueLayout.JAVA_INT_UNALIGNED, pos)
        if recLen < EntryOverhead || pos + recLen.toLong > limit then acc
        else loop(pos + recLen.toLong, acc + 1L)
    loop(HeaderSize.toLong, 0L)

  /** Current write position in the segment file. */
  def currentWriteOffset: Long = writeOffset

  /** Number of entries currently stored. */
  def currentEntryCount: Long = entryCount

  /** Total mapped byte capacity of this segment. */
  def capacity: Long = mapped.byteSize()

  /** Remaining writable capacity in bytes. */
  def remaining: Long = mapped.byteSize() - writeOffset

  /** Replay all entries in this segment in log order, verifying CRC32 on each.
    */
  def replay(callback: (Log, Long) => Unit): Unit =
    @tailrec
    def loop(pos: Long, count: Long): Unit =
      if count < entryCount then
        val log = readEntry(pos)
        callback(log, pos)
        val recordLen = mapped.get(ValueLayout.JAVA_INT_UNALIGNED, pos)
        loop(pos + recordLen.toLong, count + 1L)

    loop(HeaderSize.toLong, 0L)

  /** Close this segment, releasing its FFM `Arena` mapping. */
  def close(): Unit =
    arena.close()

  private def writeRawEntry(log: Log): Long =
    val rawPayload = log.payload
    val dataLen = rawPayload.length
    val recordLen = EntryOverhead + dataLen
    val pos = writeOffset

    mapped.set(ValueLayout.JAVA_INT_UNALIGNED, pos, recordLen)
    mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, pos + 4L, log.index)
    mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, pos + 12L, log.term)
    mapped.set(ValueLayout.JAVA_BYTE, pos + 20L, log.logType.ordinal.toByte)
    mapped.set(ValueLayout.JAVA_INT_UNALIGNED, pos + 21L, dataLen)
    if dataLen > 0 then
      val srcSeg = MemorySegment.ofBuffer(rawPayload.asReadOnlyByteBuffer)
      MemorySegment.copy(srcSeg, 0L, mapped, pos + 25L, dataLen.toLong)

    val crc = computeCrc(pos + 4L, recordLen - 8)
    mapped.set(ValueLayout.JAVA_INT_UNALIGNED, pos + 25L + dataLen.toLong, crc)

    writeOffset = pos + recordLen.toLong
    entryCount += 1L
    pos

  private[mmap] def computeCrc(offset: Long, length: Int): Int =
    val crc = new CRC32()
    crc.update(mapped.asSlice(offset, length.toLong).asByteBuffer())
    crc.getValue.toInt

/** Error raised when a WAL entry fails structural bounds or CRC32 validation.
  */
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

  private enum RecordCheck:
    case Valid(log: Log, nextOffset: Long)
    case TornTail(offset: Long, readableIndex: Long)
    case Corrupted(err: CorruptedEntryException)

  /** Create a new pre-allocated `.wal` segment file and memory-map it. */
  def create(
      path: Path,
      firstIndex: Long,
      size: Long = DefaultSegmentSize
  ): MmapSegment =
    require(
      size >= HeaderSize.toLong,
      s"Segment size ($size) must be >= HeaderSize ($HeaderSize)"
    )
    val channel = FileChannel.open(
      path,
      StandardOpenOption.CREATE,
      StandardOpenOption.READ,
      StandardOpenOption.WRITE
    )
    try
      val arena = Arena.ofShared()
      try
        val mapped =
          channel.map(FileChannel.MapMode.READ_WRITE, 0L, size, arena)
        mapped.set(ValueLayout.JAVA_INT_UNALIGNED, 0L, SegmentMagic)
        mapped.set(ValueLayout.JAVA_INT_UNALIGNED, 4L, SegmentVersion)
        mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, 8L, firstIndex)
        mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, 16L, 0L)
        val _ = mapped.force()
        new MmapSegment(path, arena, mapped, firstIndex, HeaderSize.toLong, 0L)
      catch
        case t: Throwable =>
          arena.close()
          throw t
    finally channel.close()

  /** Open an existing segment file, validating header magic/version and record
    * length framing (`@tailrec`, never infinite-loops on zeroed `recordLen`).
    */
  def open(path: Path, readOnly: Boolean = false): MmapSegment =
    val options =
      if readOnly then java.util.Set.of(StandardOpenOption.READ)
      else java.util.Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
    val channel = FileChannel.open(path, options)
    try
      val size = channel.size()
      if size < HeaderSize.toLong then
        throw new IllegalStateException(
          s"Invalid segment file $path: truncated header ($size bytes < $HeaderSize)"
        )
      val arena = Arena.ofShared()
      try
        val mode =
          if readOnly then FileChannel.MapMode.READ_ONLY
          else FileChannel.MapMode.READ_WRITE
        val mapped = channel.map(mode, 0L, size, arena)
        val (firstIndex, declaredCount) = validateHeader(path, mapped)

        @tailrec
        def scanOffsets(writeOff: Long, count: Long): Long =
          if count >= declaredCount then writeOff
          else if writeOff + EntryOverhead.toLong > size then
            throw CorruptedEntryException(path, writeOff, -1L, 0, -1)
          else
            val recordLen =
              mapped.get(ValueLayout.JAVA_INT_UNALIGNED, writeOff)
            if recordLen < EntryOverhead || writeOff + recordLen.toLong > size
            then
              val idx =
                mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, writeOff + 4L)
              throw CorruptedEntryException(path, writeOff, idx, 0, -1)
            else scanOffsets(writeOff + recordLen.toLong, count + 1L)

        val endOff = scanOffsets(HeaderSize.toLong, 0L)
        new MmapSegment(path, arena, mapped, firstIndex, endOff, declaredCount)
      catch
        case t: Throwable =>
          arena.close()
          throw t
    finally channel.close()

  /** Crash-safe recovery open for [[MmapWalEngine]].
    *
    *   - Verifies header magic/version and every entry's CRC32.
    *   - When `isTailSegment == true`, if an uncommitted tail record (`index >
    *     minProtectedIndex`) exhibits a torn/incomplete write signature
    *     (truncated EOF, `recordLen == 0` or invalid framing, invalid
    *     `LogType`, or unflushed zeroed CRC32 with no valid records following),
    *     cleanly truncates the active segment at `(lastValidOffset,
    *     validCount)` and `msync`s the healed header.
    *   - Any bit-rot of a complete record (`storedCrc != 0 && storedCrc !=
    *     computedCrc`), any corruption in a sealed segment, or any corruption
    *     at `<= minProtectedIndex` raises [[CorruptedEntryException]].
    */
  private[mmap] def openForRecovery(
      path: Path,
      isTailSegment: Boolean,
      minProtectedIndex: Long
  ): SegmentRecovery =
    val readOnly = !isTailSegment
    val options =
      if readOnly then java.util.Set.of(StandardOpenOption.READ)
      else java.util.Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
    val channel = FileChannel.open(path, options)
    try
      val size = channel.size()
      if size < HeaderSize.toLong then
        throw new IllegalStateException(
          s"Invalid segment file $path: truncated header ($size bytes < $HeaderSize)"
        )
      val arena = Arena.ofShared()
      try
        val mode =
          if readOnly then FileChannel.MapMode.READ_ONLY
          else FileChannel.MapMode.READ_WRITE
        val mapped = channel.map(mode, 0L, size, arena)
        val (firstIndex, declaredCount) = validateHeader(path, mapped)

        val entriesBuilder = Vector.newBuilder[(Log, Long)]

        @tailrec
        def loop(
            pos: Long,
            count: Long,
            lastValidIndex: Long
        ): (Long, Long, Boolean) =
          if count >= declaredCount then (pos, count, false)
          else
            inspectRecord(path, mapped, size, pos) match
              case RecordCheck.Valid(log, nextOff) =>
                entriesBuilder += ((log, pos))
                loop(nextOff, count + 1L, log.index)

              case RecordCheck.TornTail(_, readableIdx) =>
                val expectedIdx =
                  if count == 0L then firstIndex else lastValidIndex + 1L
                val effectiveTornIdx =
                  if readableIdx > 0L then readableIdx else expectedIdx
                val canHeal =
                  isTailSegment &&
                    effectiveTornIdx > minProtectedIndex &&
                    !hasSubsequentValidRecord(mapped, size, pos)
                if canHeal then (pos, count, true)
                else
                  throw CorruptedEntryException(
                    path,
                    pos,
                    effectiveTornIdx,
                    0,
                    -1
                  )

              case RecordCheck.Corrupted(err) =>
                throw err

        val (validWriteOff, validCount, healed) =
          loop(HeaderSize.toLong, 0L, firstIndex - 1L)

        // Also scan past `declaredCount` on the tail segment in case entries
        // were flushed right before the header `entryCount` update completed,
        // or zero out uncounted torn bytes? Raft safety: entries whose header
        // `entryCount` was never updated were never acknowledged (`appendBatch`
        // updates `entryCount` BEFORE `forceRange`), so stopping at
        // `validCount` is exact.
        val seg = new MmapSegment(
          path,
          arena,
          mapped,
          firstIndex,
          validWriteOff,
          validCount
        )
        if healed then
          seg.truncateAt(validWriteOff, validCount)
          if size > validWriteOff then
            mapped.asSlice(validWriteOff, size - validWriteOff).fill(0.toByte)
            seg.force()
        SegmentRecovery(seg, entriesBuilder.result(), healed)
      catch
        case t: Throwable =>
          arena.close()
          throw t
    finally channel.close()

  private def validateHeader(
      path: Path,
      mapped: MemorySegment
  ): (Long, Long) =
    val magic = mapped.get(ValueLayout.JAVA_INT_UNALIGNED, 0L)
    if magic != SegmentMagic then
      throw new IllegalStateException(
        s"Invalid segment file $path: bad magic 0x${magic.toHexString}"
      )
    val version = mapped.get(ValueLayout.JAVA_INT_UNALIGNED, 4L)
    if version != SegmentVersion then
      throw new IllegalStateException(
        s"Unsupported segment version $version in $path (expected $SegmentVersion)"
      )
    val firstIndex = mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, 8L)
    val declaredCount = mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, 16L)
    if declaredCount < 0L then
      throw new IllegalStateException(
        s"Negative entryCount ($declaredCount) in segment $path"
      )
    (firstIndex, declaredCount)

  private def inspectRecord(
      path: Path,
      mapped: MemorySegment,
      size: Long,
      offset: Long
  ): RecordCheck =
    if offset + EntryOverhead.toLong > size then
      val readableIdx =
        if offset + 12L <= size then
          mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + 4L)
        else -1L
      RecordCheck.TornTail(offset, readableIdx)
    else
      val recordLen = mapped.get(ValueLayout.JAVA_INT_UNALIGNED, offset)
      val index = mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + 4L)
      if recordLen < EntryOverhead || offset + recordLen.toLong > size then
        RecordCheck.TornTail(offset, index)
      else
        val term = mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + 12L)
        val logTypeByte = mapped.get(ValueLayout.JAVA_BYTE, offset + 20L).toInt
        val dataLen = mapped.get(ValueLayout.JAVA_INT_UNALIGNED, offset + 21L)
        if dataLen < 0 || EntryOverhead + dataLen != recordLen ||
          logTypeByte < 0 || logTypeByte >= LogTypeCount
        then RecordCheck.TornTail(offset, index)
        else
          val storedCrc =
            mapped.get(
              ValueLayout.JAVA_INT_UNALIGNED,
              offset + 25L + dataLen.toLong
            )
          val crc = new CRC32()
          crc.update(
            mapped.asSlice(offset + 4L, (recordLen - 8).toLong).asByteBuffer()
          )
          val computedCrc = crc.getValue.toInt
          if storedCrc == computedCrc then
            val payload =
              if dataLen == 0 then Bytes.empty
              else
                val arr = new Array[Byte](dataLen)
                MemorySegment.copy(
                  mapped,
                  ValueLayout.JAVA_BYTE,
                  offset + 25L,
                  arr,
                  0,
                  dataLen
                )
                Bytes.unsafeWrap(arr)
            RecordCheck.Valid(
              Log(index, term, LogType.fromOrdinal(logTypeByte), payload),
              offset + recordLen.toLong
            )
          else if storedCrc == 0 then
            // Trailing CRC32 slot is still zero-filled from segment pre-allocation
            // (process died mid-entry before writing final 4-byte CRC).
            RecordCheck.TornTail(offset, index)
          else
            RecordCheck.Corrupted(
              CorruptedEntryException(
                path,
                offset,
                index,
                storedCrc,
                computedCrc
              )
            )

  private def hasSubsequentValidRecord(
      mapped: MemorySegment,
      size: Long,
      offset: Long
  ): Boolean =
    if offset + EntryOverhead.toLong > size then false
    else
      val recordLen = mapped.get(ValueLayout.JAVA_INT_UNALIGNED, offset)
      if recordLen < EntryOverhead || offset + recordLen.toLong > size then
        false
      else
        val nextPos = offset + recordLen.toLong
        if nextPos + EntryOverhead.toLong > size then false
        else
          val nextLen = mapped.get(ValueLayout.JAVA_INT_UNALIGNED, nextPos)
          val nextType = mapped.get(ValueLayout.JAVA_BYTE, nextPos + 20L).toInt
          val nextDataLen =
            mapped.get(ValueLayout.JAVA_INT_UNALIGNED, nextPos + 21L)
          if nextLen < EntryOverhead ||
            nextPos + nextLen.toLong > size ||
            nextDataLen < 0 ||
            EntryOverhead + nextDataLen != nextLen ||
            nextType < 0 ||
            nextType >= LogTypeCount
          then false
          else
            val stored = mapped.get(
              ValueLayout.JAVA_INT_UNALIGNED,
              nextPos + 25L + nextDataLen.toLong
            )
            val crc = new CRC32()
            crc.update(
              mapped
                .asSlice(nextPos + 4L, (nextLen - 8).toLong)
                .asByteBuffer()
            )
            stored != 0 && stored == crc.getValue.toInt
