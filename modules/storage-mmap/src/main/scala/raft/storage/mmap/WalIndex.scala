package raft.storage.mmap

import java.nio.ByteBuffer
import java.util
import java.util.zip.CRC32

import raft.state.RaftError

/** Thread-safe in-memory index mapping log indices to their physical location
  * in WAL segments.
  *
  * Provides O(1) lookups by log index using flat parallel arrays instead of a
  * hash map (`~12.1` bytes per entry). Checkpoints (`serialize`) include a
  * `"WIDX"` magic header, format version, and trailing CRC32 checksum so
  * corrupted or torn checkpoint files are detected deterministically on startup
  * and rebuilt from `.wal` segments.
  */
class WalIndex:

  /** Logical result of a lookup — not heap-allocated on the hot path. */
  case class Location(segmentId: Int, fileOffset: Long)

  private val InitialCapacity = 1024
  private var baseIndex: Long = 1L
  private var segmentIds: Array[Int] = new Array[Int](InitialCapacity)
  private var fileOffsets: Array[Long] = new Array[Long](InitialCapacity)
  private var occupied: util.BitSet = new util.BitSet(InitialCapacity)
  private var slots: Int = 0
  private var entryCount: Int = 0
  private var lastIdx: Long = 0L

  /** Register a log entry's physical location. */
  def put(logIndex: Long, segmentId: Int, fileOffset: Long): Unit =
    this.synchronized {
      if entryCount == 0 && slots == 0 then baseIndex = logIndex

      val slot = (logIndex - baseIndex).toInt
      if slot < 0 then
        val shift = (baseIndex - logIndex).toInt
        val newCap = math.max(segmentIds.length, slots + shift + 1)
        val newSegIds = new Array[Int](newCap)
        val newOffsets = new Array[Long](newCap)
        System.arraycopy(segmentIds, 0, newSegIds, shift, slots)
        System.arraycopy(fileOffsets, 0, newOffsets, shift, slots)
        val newOccupied = new util.BitSet(newCap)
        for i <- 0 until slots do
          if occupied.get(i) then newOccupied.set(i + shift)
        segmentIds = newSegIds
        fileOffsets = newOffsets
        occupied = newOccupied
        slots += shift
        baseIndex = logIndex
        segmentIds(0) = segmentId
        fileOffsets(0) = fileOffset
        if !occupied.get(0) then
          occupied.set(0)
          entryCount += 1
      else
        ensureCapacity(slot + 1)
        if !occupied.get(slot) then
          occupied.set(slot)
          entryCount += 1
        segmentIds(slot) = segmentId
        fileOffsets(slot) = fileOffset
        if slot >= slots then slots = slot + 1

      if logIndex > lastIdx then lastIdx = logIndex
    }

  /** Look up the physical location of a log entry. */
  def get(logIndex: Long): Option[Location] =
    this.synchronized {
      val slot = (logIndex - baseIndex).toInt
      if slot >= 0 && slot < slots && occupied.get(slot) then
        Some(Location(segmentIds(slot), fileOffsets(slot)))
      else None
    }

  /** Look up the segment ID for a log entry without heap allocation. */
  def getSegmentId(logIndex: Long): Int =
    this.synchronized {
      val slot = (logIndex - baseIndex).toInt
      if slot >= 0 && slot < slots && occupied.get(slot) then segmentIds(slot)
      else -1
    }

  /** Look up the file offset for a log entry without heap allocation. */
  def getFileOffset(logIndex: Long): Long =
    this.synchronized {
      val slot = (logIndex - baseIndex).toInt
      if slot >= 0 && slot < slots && occupied.get(slot) then fileOffsets(slot)
      else -1L
    }

  /** The highest log index in the index, or 0 if empty. */
  def lastIndex: Long = this.synchronized(lastIdx)

  /** Whether the index contains no entries. */
  def isEmpty: Boolean = this.synchronized(entryCount == 0)

  /** Remove all entries before the given index (exclusive). */
  def discardBefore(toIndex: Long): Unit =
    this.synchronized {
      if toIndex > baseIndex then
        val shift = math.min((toIndex - baseIndex).toInt, slots)
        val remaining = slots - shift
        if remaining <= 0 then
          slots = 0
          entryCount = 0
          lastIdx = 0L
          baseIndex = toIndex
          occupied.clear()
        else
          for i <- 0 until shift do if occupied.get(i) then entryCount -= 1
          System.arraycopy(segmentIds, shift, segmentIds, 0, remaining)
          System.arraycopy(fileOffsets, shift, fileOffsets, 0, remaining)
          val newOccupied = new util.BitSet(remaining)
          for i <- 0 until remaining do
            if occupied.get(i + shift) then newOccupied.set(i)
          occupied = newOccupied
          java.util.Arrays.fill(segmentIds, remaining, remaining + shift, 0)
          java.util.Arrays.fill(fileOffsets, remaining, remaining + shift, 0L)
          if entryCount == 0 then
            slots = 0
            lastIdx = 0L
          else
            val prevSet = occupied.previousSetBit(remaining - 1)
            slots = prevSet + 1
            lastIdx = baseIndex + shift + prevSet
          baseIndex = toIndex
    }

  /** Remove all entries at or after the given index. */
  def truncateFrom(fromIndex: Long): Unit =
    this.synchronized {
      if fromIndex <= baseIndex then clear()
      else
        val newSlots = math.min((fromIndex - baseIndex).toInt, slots)
        if newSlots < slots then
          for i <- newSlots until slots do
            if occupied.get(i) then entryCount -= 1
          occupied.clear(newSlots, slots)
          java.util.Arrays.fill(segmentIds, newSlots, slots, 0)
          java.util.Arrays.fill(fileOffsets, newSlots, slots, 0L)
          if entryCount == 0 then
            slots = 0
            lastIdx = 0L
          else
            val prevSet = occupied.previousSetBit(newSlots - 1)
            if prevSet < 0 then
              slots = 0
              lastIdx = 0L
            else
              slots = prevSet + 1
              lastIdx = baseIndex + prevSet
    }

  /** Remove all entries. */
  def clear(): Unit =
    this.synchronized {
      slots = 0
      entryCount = 0
      lastIdx = 0L
      baseIndex = 1L
      occupied.clear()
    }

  /** Serialize the index with a `"WIDX"` v1 header and trailing CRC32 checksum.
    *
    * Binary layout:
    * `magic(4) + version(1) + baseIndex(8) + entryCount(4) + [logIndex(8) + segmentId(4) + fileOffset(8)] * entryCount + crc32(4)`
    */
  def serialize: Array[Byte] =
    this.synchronized {
      val payloadSize = 4 + 1 + 8 + 4 + entryCount * 20
      val buf = ByteBuffer.allocate(payloadSize + 4)
      val _ = buf.putInt(WalIndex.CheckpointMagic)
      val _ = buf.put(WalIndex.CheckpointVersion)
      val _ = buf.putLong(baseIndex)
      val _ = buf.putInt(entryCount)
      var i = 0
      while i < slots do
        if occupied.get(i) then
          val _ = buf.putLong(baseIndex + i)
          val _ = buf.putInt(segmentIds(i))
          val _ = buf.putLong(fileOffsets(i))
        i += 1
      val raw = buf.array()
      val crc = new CRC32()
      crc.update(raw, 0, payloadSize)
      buf.putInt(payloadSize, crc.getValue.toInt)
      raw
    }

  /** Total number of indexed entries. */
  def size: Int = this.synchronized(entryCount)

  private def ensureCapacity(minCap: Int): Unit =
    if minCap > segmentIds.length then
      val newCap = math.max(segmentIds.length * 2, minCap)
      val newSegIds = new Array[Int](newCap)
      val newOffsets = new Array[Long](newCap)
      System.arraycopy(segmentIds, 0, newSegIds, 0, slots)
      System.arraycopy(fileOffsets, 0, newOffsets, 0, slots)
      segmentIds = newSegIds
      fileOffsets = newOffsets

object WalIndex:
  private val CheckpointMagic: Int = 0x57494458 // "WIDX"
  private val CheckpointVersion: Byte = 1

  /** Validate and deserialize a checkpoint byte array, returning `Left(reason)`
    * if the checkpoint is truncated or fails CRC32 verification.
    *
    * Remains backward-compatible with pre-v1 unchecksummed checkpoints whose
    * exact byte length matches their declared entry count.
    */
  def deserializeChecked(bytes: Array[Byte]): Either[String, WalIndex] =
    if bytes.isEmpty then Left("Empty WalIndex checkpoint")
    else if bytes.length >= 4 && ByteBuffer
        .wrap(bytes, 0, 4)
        .getInt == CheckpointMagic
    then
      if bytes.length < 21 then
        Left(s"Truncated WIDX checkpoint (${bytes.length} bytes)")
      else
        val buf = ByteBuffer.wrap(bytes)
        val _ = buf.getInt()
        val version = buf.get()
        if version != CheckpointVersion then
          Left(s"Unsupported WIDX checkpoint version: $version")
        else
          val _ = buf.getLong() // baseIndex
          val count = buf.getInt()
          val expectedLen = 4 + 1 + 8 + 4 + count.toLong * 20L + 4L
          if count < 0 || bytes.length.toLong != expectedLen then
            Left(
              s"Truncated or malformed WIDX checkpoint: count=$count, length=${bytes.length}, expected=$expectedLen"
            )
          else
            val payloadSize = bytes.length - 4
            val crc = new CRC32()
            crc.update(bytes, 0, payloadSize)
            val expectedCrc = crc.getValue.toInt
            val storedCrc = ByteBuffer.wrap(bytes, payloadSize, 4).getInt
            if expectedCrc != storedCrc then
              Left(
                f"WIDX checkpoint CRC32 mismatch: stored=0x$storedCrc%08x, computed=0x$expectedCrc%08x"
              )
            else
              val idx = new WalIndex
              var i = 0
              while i < count do
                val logIndex = buf.getLong()
                val segmentId = buf.getInt()
                val fileOffset = buf.getLong()
                idx.put(logIndex, segmentId, fileOffset)
                i += 1
              Right(idx)
    else if bytes.length >= 8 then
      val buf = ByteBuffer.wrap(bytes)
      val firstLong = buf.getLong(0)
      val isLegacyV0 =
        firstLong >= 0 && firstLong <= Int.MaxValue && bytes.length.toLong == 8L + firstLong * 20L
      if isLegacyV0 then
        val idx = new WalIndex
        val count = buf.getLong()
        var i = 0L
        while i < count do
          idx.put(buf.getLong(), buf.getInt(), buf.getLong())
          i += 1L
        Right(idx)
      else if bytes.length >= 12 then
        val _ = buf.getLong()
        val count = buf.getInt()
        if count >= 0 && bytes.length.toLong == 12L + count.toLong * 20L then
          val idx = new WalIndex
          var i = 0
          while i < count do
            idx.put(buf.getLong(), buf.getInt(), buf.getLong())
            i += 1
          Right(idx)
        else
          Left(s"Corrupted legacy WalIndex checkpoint (${bytes.length} bytes)")
      else Left(s"Truncated WalIndex checkpoint (${bytes.length} bytes)")
    else Left(s"Truncated WalIndex checkpoint (${bytes.length} bytes)")

  /** Deserialize an index from a checkpoint byte array, throwing
    * [[raft.state.RaftError.StorageCorruption]] on invalid data.
    */
  def deserialize(bytes: Array[Byte]): WalIndex =
    if bytes.isEmpty then new WalIndex
    else
      deserializeChecked(bytes) match
        case Right(idx) => idx
        case Left(err)  => throw RaftError.StorageCorruption(err)
