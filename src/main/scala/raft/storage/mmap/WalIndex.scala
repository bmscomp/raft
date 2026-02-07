package raft.storage.mmap

import scala.collection.mutable

/** In-memory index mapping log indices to their physical location in WAL
  * segments.
  *
  * Provides O(1) lookups by log index, backed by a mutable `LongMap`. The index
  * is rebuilt on startup by replaying WAL segments and can be checkpointed to
  * disk for fast recovery.
  *
  * '''Memory footprint''': ~50 bytes per entry. 1 million entries ≈ 50 MB.
  */
class WalIndex:

  /** Segment ID and byte offset within that segment. */
  case class Location(segmentId: Int, fileOffset: Long)

  private val index: mutable.LongMap[Location] = mutable.LongMap.empty
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
    index(logIndex) = Location(segmentId, fileOffset)
    if logIndex > lastIdx then lastIdx = logIndex

  /** Look up the physical location of a log entry.
    *
    * @param logIndex
    *   the Raft log index to find
    * @return
    *   `Some(Location)` if present, `None` otherwise
    */
  def get(logIndex: Long): Option[Location] =
    index.get(logIndex)

  /** The highest log index in the index, or 0 if empty. */
  def lastIndex: Long = lastIdx

  /** Whether the index contains no entries. */
  def isEmpty: Boolean = index.isEmpty

  /** Remove all entries before the given index (exclusive).
    *
    * @param toIndex
    *   the index up to which entries are removed (entries with index <
    *   `toIndex` are discarded)
    */
  def discardBefore(toIndex: Long): Unit =
    val toRemove = index.keys.filter(_ < toIndex).toList
    toRemove.foreach(index.remove)
    // lastIdx remains unchanged unless empty? No, lastIdx is max key.
    // If we remove everything, lastIdx should update?
    // Actually, raft state maintains "lastIncludedIndex" in snapshot, so WAL
    // might become empty but logically index > 0.
    // But WalIndex tracks physical locations. If empty, lastIdx=0 is correct for *this structure*,
    // but MmapWalEngine.lastLogIndex handles the snapshot offset.
    if index.isEmpty then lastIdx = 0L
    // If not empty, lastIdx is still the max key, which didn't change (we removed prefix).

  /** Remove all entries at or after the given index.
    *
    * @param fromIndex
    *   the first index to remove (inclusive)
    */
  def truncateFrom(fromIndex: Long): Unit =
    val toRemove = index.keys.filter(_ >= fromIndex).toList
    toRemove.foreach(index.remove)
    lastIdx = if index.isEmpty then 0L else index.keys.max

  /** Remove all entries. */
  def clear(): Unit =
    index.clear()
    lastIdx = 0L

  /** Serialize the index to a byte array for checkpointing.
    *
    * Format: count(8) followed by (logIndex(8) + segmentId(4) + fileOffset(8))
    * × count.
    */
  def serialize: Array[Byte] =
    val buf = java.nio.ByteBuffer.allocate(8 + index.size * 20)
    buf.putLong(index.size.toLong)
    index.foreach { (logIndex, loc) =>
      buf.putLong(logIndex)
      buf.putInt(loc.segmentId)
      buf.putLong(loc.fileOffset)
    }
    buf.array()

  /** Total number of indexed entries. */
  def size: Int = index.size

object WalIndex:

  /** Deserialize an index from a checkpoint byte array.
    *
    * @param bytes
    *   the serialized index data
    * @return
    *   a reconstructed `WalIndex`
    */
  def deserialize(bytes: Array[Byte]): WalIndex =
    val idx = new WalIndex
    val buf = java.nio.ByteBuffer.wrap(bytes)
    val count = buf.getLong()
    var i = 0L
    while i < count do
      val logIndex = buf.getLong()
      val segmentId = buf.getInt()
      val fileOffset = buf.getLong()
      idx.put(logIndex, segmentId, fileOffset)
      i += 1
    idx
