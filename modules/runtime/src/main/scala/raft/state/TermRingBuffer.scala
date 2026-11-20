package raft.state

import scala.annotation.tailrec

/** Pure, immutable bounded window cache mapping recent log indices to their
  * terms (`O(1)` lookup, `0` `var`s, `0` `while` loops).
  *
  * Replaces `O(N)` full-log `LogStore.getRange` scans on every leader heartbeat
  * and `AppendEntries` event in `RaftNode`, falling back to `LogStore.termAt`
  * only when a severely lagging follower probes deeper than `capacity`.
  */
final case class TermRingBuffer private (
    capacity: Int,
    baseIndex: Long,
    terms: Vector[Long],
    snapIndex: Long,
    snapTerm: Long
):
  /** Look up a cached term at `index` in `O(1)`. */
  def termAt(index: Long): Option[Long] =
    if index == 0L then Some(0L)
    else if snapIndex > 0L && index == snapIndex then Some(snapTerm)
    else if terms.nonEmpty && index >= baseIndex then
      val offset = index - baseIndex
      if offset < terms.length.toLong then Some(terms(offset.toInt))
      else None
    else None

  /** Last cached log coordinate `(index, term)`, falling back to the snapshot
    * anchor when the suffix window is empty.
    */
  def lastCoordinate: Option[(Long, Long)] =
    if terms.nonEmpty then
      Some((baseIndex + terms.length.toLong - 1L, terms.last))
    else if snapIndex > 0L then Some((snapIndex, snapTerm))
    else None

  /** Append contiguous `entries` in order, trimming older entries beyond
    * `capacity`.
    */
  def append(entries: Seq[Log]): TermRingBuffer =
    if entries.isEmpty then this
    else
      val sorted = entries.toVector
      val firstNewIdx = sorted.head.index
      val aligned =
        if terms.isEmpty then
          copy(baseIndex = firstNewIdx, terms = sorted.map(_.term))
        else
          val currentEnd = baseIndex + terms.length.toLong
          if firstNewIdx == currentEnd then
            copy(terms = terms ++ sorted.map(_.term))
          else if firstNewIdx >= baseIndex && firstNewIdx < currentEnd then
            val keep = (firstNewIdx - baseIndex).toInt
            copy(terms = terms.take(keep) ++ sorted.map(_.term))
          else
            // Discontiguous jump (e.g. after snapshot/clear): reset window to new suffix
            copy(baseIndex = firstNewIdx, terms = sorted.map(_.term))
      aligned.trimToCapacity

  /** Seed an explicit `(index -> term)` batch (e.g. during startup recovery).
    */
  def seedFromEntries(entries: Vector[Log]): TermRingBuffer =
    @tailrec
    def splitRuns(
        rem: Vector[Log],
        acc: TermRingBuffer
    ): TermRingBuffer =
      if rem.isEmpty then acc
      else
        @tailrec
        def findContiguousEnd(i: Int): Int =
          if i < rem.length && rem(i).index == rem(i - 1).index + 1L then
            findContiguousEnd(i + 1)
          else i

        val end = findContiguousEnd(1)
        val (run, rest) = rem.splitAt(end)
        splitRuns(rest, acc.append(run))

    splitRuns(entries, this)

  /** Truncate any cached terms at or after `fromIndex` (`>= fromIndex`). */
  def truncateFrom(fromIndex: Long): TermRingBuffer =
    if terms.isEmpty then this
    else if fromIndex <= baseIndex then copy(terms = Vector.empty)
    else
      val end = baseIndex + terms.length.toLong
      if fromIndex >= end then this
      else
        val keep = (fromIndex - baseIndex).toInt
        copy(terms = terms.take(keep))

  /** Discard cached terms strictly before `toIndex` (`< toIndex`). */
  def discardBefore(toIndex: Long): TermRingBuffer =
    if terms.isEmpty || toIndex <= baseIndex then this
    else
      val dropCount = toIndex - baseIndex
      if dropCount >= terms.length.toLong then
        copy(baseIndex = toIndex, terms = Vector.empty)
      else
        copy(
          baseIndex = toIndex,
          terms = terms.drop(dropCount.toInt)
        )

  /** Record the latest snapshot boundary `(lastIncludedIndex,
    * lastIncludedTerm)`.
    */
  def withSnapshot(index: Long, term: Long): TermRingBuffer =
    if index <= 0L then this
    else
      val nextSnapIdx = math.max(snapIndex, index)
      val nextSnapTerm = if index >= snapIndex then term else snapTerm
      copy(snapIndex = nextSnapIdx, snapTerm = nextSnapTerm)

  /** Clear all cached log terms while optionally retaining the snapshot anchor.
    */
  def clearLogOnly: TermRingBuffer =
    copy(baseIndex = snapIndex + 1L, terms = Vector.empty)

  private def trimToCapacity: TermRingBuffer =
    if terms.length <= capacity then this
    else
      val drop = terms.length - capacity
      copy(
        baseIndex = baseIndex + drop.toLong,
        terms = terms.drop(drop)
      )

object TermRingBuffer:
  val DefaultCapacity: Int = 4096

  def empty(capacity: Int = DefaultCapacity): TermRingBuffer =
    TermRingBuffer(
      capacity = math.max(64, capacity),
      baseIndex = 1L,
      terms = Vector.empty,
      snapIndex = 0L,
      snapTerm = 0L
    )
