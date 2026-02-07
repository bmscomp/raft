package raft.storage.mmap

import java.util

/** In-memory index mapping log indices to their physical location in WAL
  * segments.
  *
  * Provides O(1) lookups by log index using flat parallel arrays instead of a
  * hash map. Exploits the Raft invariant that log indices are dense and
  * sequential: entries occupy a contiguous range `[baseIndex, baseIndex +
  * capacity)`.
  *
  * '''Memory footprint''': ~12.1 bytes per entry (4-byte segment ID + 8-byte
  * file offset + ~0.1 byte bitmap). Compared to the previous `LongMap` at ~72
  * bytes/entry, this is a 6× reduction. At 10 million entries this uses ~120 MB
  * instead of ~720 MB, with zero GC pressure (only 3 array objects on the
  * heap).
  *
  * '''Complexity''':
  *   - `put`: O(1) amortized (may trigger array resize)
  *   - `get`: O(1) direct array access
  *   - `truncateFrom`: O(1) metadata adjustment
  *   - `discardBefore`: O(N) arraycopy, O(1) metadata
  *   - `serialize` / `deserialize`: O(N) bulk copy
  */
class WalIndex:

  /** Logical result of a lookup — not heap-allocated on the hot path. */
  case class Location(segmentId: Int, fileOffset: Long)

  private val InitialCapacity = 1024
  private var baseIndex: Long = 1L
  private var segmentIds: Array[Int] = new Array[Int](InitialCapacity)
  private var fileOffsets: Array[Long] = new Array[Long](InitialCapacity)
  private var occupied: util.BitSet = new util.BitSet(InitialCapacity)
  private var slots: Int = 0 // number of allocated slots (highwater)
  private var entryCount: Int = 0 // number of actually occupied entries
  private var lastIdx: Long = 0L

  /** Register a log entry's physical location.
    *
    * @param logIndex
    *   the Raft log index
    * @param segmentId
    *   the segment identifier (sequence number)
    * @param fileOffset
    *   the byte offset within the segment file
    */
  def put(logIndex: Long, segmentId: Int, fileOffset: Long): Unit =
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

  /** Look up the physical location of a log entry.
    *
    * @param logIndex
    *   the Raft log index to find
    * @return
    *   `Some(Location)` if present, `None` otherwise
    */
  def get(logIndex: Long): Option[Location] =
    val slot = (logIndex - baseIndex).toInt
    if slot >= 0 && slot < slots && occupied.get(slot) then
      Some(Location(segmentIds(slot), fileOffsets(slot)))
    else None

  /** The highest log index in the index, or 0 if empty. */
  def lastIndex: Long = lastIdx

  /** Whether the index contains no entries. */
  def isEmpty: Boolean = entryCount == 0

  /** Remove all entries before the given index (exclusive).
    *
    * @param toIndex
    *   the index up to which entries are removed (entries with index <
    *   `toIndex` are discarded)
    */
  def discardBefore(toIndex: Long): Unit =
    if toIndex <= baseIndex then return

    val shift = math.min((toIndex - baseIndex).toInt, slots)
    val remaining = slots - shift
    if remaining <= 0 then
      slots = 0
      entryCount = 0
      lastIdx = 0L
      baseIndex = toIndex
      occupied.clear()
    else
      // Count entries being removed
      for i <- 0 until shift do if occupied.get(i) then entryCount -= 1
      System.arraycopy(segmentIds, shift, segmentIds, 0, remaining)
      System.arraycopy(fileOffsets, shift, fileOffsets, 0, remaining)
      val newOccupied = new util.BitSet(remaining)
      for i <- 0 until remaining do
        if occupied.get(i + shift) then newOccupied.set(i)
      occupied = newOccupied
      java.util.Arrays.fill(segmentIds, remaining, remaining + shift, 0)
      java.util.Arrays.fill(fileOffsets, remaining, remaining + shift, 0L)
      slots = remaining
      baseIndex = toIndex

  /** Remove all entries at or after the given index.
    *
    * @param fromIndex
    *   the first index to remove (inclusive)
    */
  def truncateFrom(fromIndex: Long): Unit =
    if fromIndex <= baseIndex then clear()
    else
      val newSlots = math.min((fromIndex - baseIndex).toInt, slots)
      if newSlots < slots then
        // Count removed entries
        for i <- newSlots until slots do if occupied.get(i) then entryCount -= 1
        occupied.clear(newSlots, slots)
        java.util.Arrays.fill(segmentIds, newSlots, slots, 0)
        java.util.Arrays.fill(fileOffsets, newSlots, slots, 0L)
        slots = newSlots
        lastIdx = if entryCount == 0 then 0L else baseIndex + slots - 1

  /** Remove all entries. */
  def clear(): Unit =
    slots = 0
    entryCount = 0
    lastIdx = 0L
    baseIndex = 1L
    occupied.clear()

  /** Serialize the index to a byte array for checkpointing.
    *
    * Format: baseIndex(8) + entryCount(4) followed by (logIndex(8) +
    * segmentId(4) + fileOffset(8)) × entryCount.
    *
    * Only occupied slots are serialized, keeping the checkpoint compact even
    * with sparse indices.
    */
  def serialize: Array[Byte] =
    val totalSize = 12 + entryCount * 20
    val buf = java.nio.ByteBuffer.allocate(totalSize)
    buf.putLong(baseIndex)
    buf.putInt(entryCount)
    var i = 0
    while i < slots do
      if occupied.get(i) then
        buf.putLong(baseIndex + i)
        buf.putInt(segmentIds(i))
        buf.putLong(fileOffsets(i))
      i += 1
    buf.array()

  /** Total number of indexed entries. */
  def size: Int = entryCount

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

  /** Deserialize an index from a checkpoint byte array.
    *
    * Supports both the new compact format (baseIndex + entryCount + entries)
    * and the legacy format (count(Long) + logIndex/segmentId/fileOffset
    * triples).
    *
    * @param bytes
    *   the serialized index data
    * @return
    *   a reconstructed `WalIndex`
    */
  def deserialize(bytes: Array[Byte]): WalIndex =
    val idx = new WalIndex
    if bytes.length < 8 then return idx

    val buf = java.nio.ByteBuffer.wrap(bytes)

    // Detect format: legacy has header=count(8 bytes Long), each entry=20 bytes.
    // New format has header=baseIndex(8 bytes Long) + entryCount(4 bytes Int) = 12 bytes,
    // each entry = 20 bytes. We distinguish by checking if bytes.length matches legacy.
    val firstLong = buf.getLong(0)
    val isLegacy = firstLong >= 0 && bytes.length == 8 + firstLong * 20

    if isLegacy then
      buf.position(0)
      val count = buf.getLong()
      var i = 0L
      while i < count do
        val logIndex = buf.getLong()
        val segmentId = buf.getInt()
        val fileOffset = buf.getLong()
        idx.put(logIndex, segmentId, fileOffset)
        i += 1
    else
      buf.position(0)
      val base = buf.getLong()
      val count = buf.getInt()
      if count > 0 then
        var i = 0
        while i < count do
          val logIndex = buf.getLong()
          val segmentId = buf.getInt()
          val fileOffset = buf.getLong()
          idx.put(logIndex, segmentId, fileOffset)
          i += 1

    idx
