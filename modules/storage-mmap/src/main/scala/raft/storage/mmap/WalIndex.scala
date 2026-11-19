package raft.storage.mmap

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import scala.annotation.tailrec

import raft.state.RaftError

/** Physical location of a Raft log entry inside a segmented `.wal` file. */
final case class WalLocation(segmentId: Int, fileOffset: Long)

/** Pure, immutable 2-level radix paged WAL index (`4,096` entries per
  * super-page, `64` entries per leaf chunk).
  *
  *   - **Referentially transparent**: `0` `var`s, `0` `while` loops, `0`
  *     `synchronized` blocks, `0` `null`s, and `0` 32-bit `(logIndex -
  *     baseIndex).toInt` truncation bugs (`logIndex` safe up to
  *     `Long.MaxValue`).
  *   - **`O(1)` allocation-free lookups**: `getSegmentId` and `getFileOffset`
  *     execute three unconditional `IArray` loads using shared sentinel
  *     `EmptyChunk` / `EmptySuperPage` values.
  *   - **Structural sharing**: single inserts copy only one 64-entry leaf chunk
  *     (`768` bytes); batch inserts copy each touched leaf chunk once.
  */
final case class WalIndexState private (
    private val baseSuperPage: Long,
    private val superPages: IArray[WalIndexState.SuperPage],
    entryCount: Int,
    firstIndex: Long,
    lastIndex: Long
):
  import WalIndexState.*

  def isEmpty: Boolean = entryCount == 0

  def nonEmpty: Boolean = entryCount > 0

  def size: Int = entryCount

  /** Allocation-free segment ID lookup (`-1` if absent). */
  def getSegmentId(logIndex: Long): Int =
    if entryCount == 0 || logIndex < firstIndex || logIndex > lastIndex then -1
    else
      val spOff = (logIndex >>> SuperShift) - baseSuperPage
      if spOff < 0L || spOff >= superPages.length.toLong then -1
      else
        val chunkIdx = ((logIndex >>> ChunkShift) & ChunkIndexMask).toInt
        val slot = (logIndex & SlotMask).toInt
        superPages(spOff.toInt).chunkAt(chunkIdx).segmentIdAt(slot)

  /** Allocation-free file offset lookup (`-1L` if absent). */
  def getFileOffset(logIndex: Long): Long =
    if entryCount == 0 || logIndex < firstIndex || logIndex > lastIndex then -1L
    else
      val spOff = (logIndex >>> SuperShift) - baseSuperPage
      if spOff < 0L || spOff >= superPages.length.toLong then -1L
      else
        val chunkIdx = ((logIndex >>> ChunkShift) & ChunkIndexMask).toInt
        val slot = (logIndex & SlotMask).toInt
        superPages(spOff.toInt).chunkAt(chunkIdx).offsetAt(slot)

  /** Boxed lookup returning `Some(WalLocation)` when `logIndex` is indexed. */
  def get(logIndex: Long): Option[WalLocation] =
    val segId = getSegmentId(logIndex)
    if segId == -1 then None
    else Some(WalLocation(segId, getFileOffset(logIndex)))

  /** Return a new [[WalIndexState]] with `(logIndex, segmentId, fileOffset)`
    * recorded.
    */
  def put(logIndex: Long, segmentId: Int, fileOffset: Long): WalIndexState =
    val targetSp = logIndex >>> SuperShift
    val chunkIdx = ((logIndex >>> ChunkShift) & ChunkIndexMask).toInt
    val slot = (logIndex & SlotMask).toInt

    if entryCount == 0 then
      val ch = EmptyChunk.updated(slot, segmentId, fileOffset)._1
      val sp = EmptySuperPage.updatedChunk(chunkIdx, ch)
      WalIndexState(
        baseSuperPage = targetSp,
        superPages = IArray(sp),
        entryCount = 1,
        firstIndex = logIndex,
        lastIndex = logIndex
      )
    else
      val aligned = ensureSuperPageRange(targetSp)
      val spPos = (targetSp - aligned.baseSuperPage).toInt
      val oldSp = aligned.superPages(spPos)
      val (newCh, addedDelta) =
        oldSp.chunkAt(chunkIdx).updated(slot, segmentId, fileOffset)
      val newSp = oldSp.updatedChunk(chunkIdx, newCh)
      val nextSupers = copyUpdateSuper(aligned.superPages, spPos, newSp)
      WalIndexState(
        baseSuperPage = aligned.baseSuperPage,
        superPages = nextSupers,
        entryCount = aligned.entryCount + addedDelta,
        firstIndex = math.min(aligned.firstIndex, logIndex),
        lastIndex = math.max(aligned.lastIndex, logIndex)
      )

  /** Batch-insert `(logIndex, fileOffset)` pairs belonging to `segmentId`,
    * cloning each touched 64-entry chunk at most once when sequential.
    */
  def putBatch(
      segmentId: Int,
      entries: Vector[(Long, Long)]
  ): WalIndexState =
    @tailrec
    def loop(pos: Int, acc: WalIndexState): WalIndexState =
      if pos >= entries.length then acc
      else
        val (firstIdxInRun, _) = entries(pos)
        val runChunkId = firstIdxInRun >>> ChunkShift

        @tailrec
        def findRunEnd(i: Int): Int =
          if i < entries.length && (entries(i)._1 >>> ChunkShift) == runChunkId
          then findRunEnd(i + 1)
          else i

        val end = findRunEnd(pos + 1)
        val nextAcc =
          if end - pos == 1 then
            val (lIdx, fOff) = entries(pos)
            acc.put(lIdx, segmentId, fOff)
          else acc.putSameChunkSlice(segmentId, entries, pos, end)
        loop(end, nextAcc)

    loop(0, this)

  private def putSameChunkSlice(
      segmentId: Int,
      entries: Vector[(Long, Long)],
      from: Int,
      until: Int
  ): WalIndexState =
    val sampleIdx = entries(from)._1
    val targetSp = sampleIdx >>> SuperShift
    val chunkIdx = ((sampleIdx >>> ChunkShift) & ChunkIndexMask).toInt

    val aligned =
      if entryCount == 0 then
        WalIndexState(
          baseSuperPage = targetSp,
          superPages = IArray(EmptySuperPage),
          entryCount = 0,
          firstIndex = sampleIdx,
          lastIndex = sampleIdx
        )
      else ensureSuperPageRange(targetSp)

    val spPos = (targetSp - aligned.baseSuperPage).toInt
    val oldSp = aligned.superPages(spPos)
    val (newCh, delta, minIdx, maxIdx) =
      oldSp
        .chunkAt(chunkIdx)
        .updatedSlice(segmentId, entries, from, until)
    val newSp = oldSp.updatedChunk(chunkIdx, newCh)
    val nextSupers = copyUpdateSuper(aligned.superPages, spPos, newSp)
    val nextFirst =
      if aligned.entryCount == 0 then minIdx
      else math.min(aligned.firstIndex, minIdx)
    val nextLast =
      if aligned.entryCount == 0 then maxIdx
      else math.max(aligned.lastIndex, maxIdx)

    WalIndexState(
      baseSuperPage = aligned.baseSuperPage,
      superPages = nextSupers,
      entryCount = aligned.entryCount + delta,
      firstIndex = nextFirst,
      lastIndex = nextLast
    )

  /** Remove all entries at or after `fromIndex` (`>= fromIndex`). */
  def truncateFrom(fromIndex: Long): WalIndexState =
    if entryCount == 0 || fromIndex > lastIndex then this
    else if fromIndex <= firstIndex then WalIndexState.empty
    else
      val cutSp = fromIndex >>> SuperShift
      val cutSpOffset = cutSp - baseSuperPage
      if cutSpOffset < 0L then WalIndexState.empty
      else if cutSpOffset >= superPages.length.toLong then this
      else
        val spPos = cutSpOffset.toInt
        val cutChunk = ((fromIndex >>> ChunkShift) & ChunkIndexMask).toInt
        val cutSlot = (fromIndex & SlotMask).toInt

        val removedAfterSupers = sumOccupiedSupers(spPos + 1, superPages.length)
        val (trimmedBoundarySp, removedInBoundary) =
          superPages(spPos).truncateFrom(cutChunk, cutSlot)

        val nextCount = entryCount - removedAfterSupers - removedInBoundary
        if nextCount <= 0 then WalIndexState.empty
        else
          val keepLen =
            if trimmedBoundarySp.occupied == 0 then spPos else spPos + 1
          val nextSupers = new Array[SuperPage](keepLen)
          if spPos > 0 then
            System.arraycopy(superPages.unsafeArray, 0, nextSupers, 0, spPos)
          if keepLen > spPos then nextSupers(spPos) = trimmedBoundarySp
          val immSupers = IArray.unsafeFromArray(nextSupers)
          val nextLast =
            findPreviousOccupied(baseSuperPage, immSupers, fromIndex - 1L)
          if nextLast < 0L then WalIndexState.empty
          else
            WalIndexState(
              baseSuperPage = baseSuperPage,
              superPages = immSupers,
              entryCount = nextCount,
              firstIndex = firstIndex,
              lastIndex = nextLast
            )

  /** Remove all entries strictly before `toIndex` (`< toIndex`). */
  def discardBefore(toIndex: Long): WalIndexState =
    if entryCount == 0 || toIndex <= firstIndex then this
    else if toIndex > lastIndex then WalIndexState.empty
    else
      val targetSp = toIndex >>> SuperShift
      val spShift = targetSp - baseSuperPage
      if spShift >= superPages.length.toLong then WalIndexState.empty
      else
        val dropSupers = math.max(0, spShift.toInt)
        val removedBeforeSupers = sumOccupiedSupers(0, dropSupers)
        val remLen = superPages.length - dropSupers
        val cutChunk = ((toIndex >>> ChunkShift) & ChunkIndexMask).toInt
        val cutSlot = (toIndex & SlotMask).toInt

        val (trimmedFirstSp, removedInFirst) =
          if spShift < 0L then (superPages(0), 0)
          else superPages(dropSupers).discardBefore(cutChunk, cutSlot)

        val nextCount = entryCount - removedBeforeSupers - removedInFirst
        if nextCount <= 0 then WalIndexState.empty
        else
          val nextSupersArr = new Array[SuperPage](remLen)
          nextSupersArr(0) = trimmedFirstSp
          if remLen > 1 then
            System.arraycopy(
              superPages.unsafeArray,
              dropSupers + 1,
              nextSupersArr,
              1,
              remLen - 1
            )

          // Trim leading completely empty super-pages so baseSuperPage stays tight
          @tailrec
          def countLeadingEmpty(i: Int): Int =
            if i < nextSupersArr.length && nextSupersArr(i).occupied == 0 then
              countLeadingEmpty(i + 1)
            else i

          val leadEmpty = countLeadingEmpty(0)
          val finalLen = remLen - leadEmpty
          val finalArr =
            if leadEmpty == 0 then nextSupersArr
            else
              val sliced = new Array[SuperPage](finalLen)
              System.arraycopy(nextSupersArr, leadEmpty, sliced, 0, finalLen)
              sliced
          val immSupers = IArray.unsafeFromArray(finalArr)
          val newBaseSp = baseSuperPage + dropSupers.toLong + leadEmpty.toLong
          val nextFirst =
            findNextOccupied(
              newBaseSp,
              immSupers,
              math.max(toIndex, newBaseSp << SuperShift),
              lastIndex
            )
          if nextFirst < 0L then WalIndexState.empty
          else
            WalIndexState(
              baseSuperPage = newBaseSp,
              superPages = immSupers,
              entryCount = nextCount,
              firstIndex = nextFirst,
              lastIndex = lastIndex
            )

  /** Count indexed entries belonging to `segmentId` in
    * `[fromIndex, untilIndex)`.
    */
  def countSegmentEntries(
      segmentId: Int,
      fromIndex: Long,
      untilIndex: Long
  ): Long =
    if entryCount == 0 || fromIndex >= untilIndex then 0L
    else
      val lo = math.max(firstIndex, fromIndex)
      val hi = math.min(lastIndex + 1L, untilIndex)
      @tailrec
      def loop(idx: Long, acc: Long): Long =
        if idx >= hi then acc
        else
          val spOff = (idx >>> SuperShift) - baseSuperPage
          if spOff < 0L then loop((baseSuperPage << SuperShift), acc)
          else if spOff >= superPages.length.toLong then acc
          else
            val sp = superPages(spOff.toInt)
            if sp.occupied == 0 then
              val nextSpStart = ((idx >>> SuperShift) + 1L) << SuperShift
              loop(nextSpStart, acc)
            else
              val cIdx = ((idx >>> ChunkShift) & ChunkIndexMask).toInt
              val ch = sp.chunkAt(cIdx)
              if ch.occupied == 0 then
                val nextChStart = ((idx >>> ChunkShift) + 1L) << ChunkShift
                loop(nextChStart, acc)
              else
                val slot = (idx & SlotMask).toInt
                val hit = if ch.segmentIdAt(slot) == segmentId then 1L else 0L
                loop(idx + 1L, acc + hit)
      loop(lo, 0L)

  /** Collect `(logIndex, segmentId, fileOffset)` for `[startIdx, endIdx]`
    * inclusive.
    */
  def rangeLocations(
      startIdx: Long,
      endIdx: Long
  ): Vector[(Long, Int, Long)] =
    if entryCount == 0 || endIdx < startIdx || endIdx < firstIndex || startIdx > lastIndex
    then Vector.empty
    else
      val lo = math.max(startIdx, firstIndex)
      val hi = math.min(endIdx, lastIndex)
      val b = Vector.newBuilder[(Long, Int, Long)]

      @tailrec
      def loop(idx: Long): Unit =
        if idx <= hi then
          val spOff = (idx >>> SuperShift) - baseSuperPage
          if spOff < 0L then loop(baseSuperPage << SuperShift)
          else if spOff < superPages.length.toLong then
            val sp = superPages(spOff.toInt)
            if sp.occupied == 0 then
              loop(((idx >>> SuperShift) + 1L) << SuperShift)
            else
              val cIdx = ((idx >>> ChunkShift) & ChunkIndexMask).toInt
              val ch = sp.chunkAt(cIdx)
              if ch.occupied == 0 then
                loop(((idx >>> ChunkShift) + 1L) << ChunkShift)
              else
                val slot = (idx & SlotMask).toInt
                val seg = ch.segmentIdAt(slot)
                if seg != -1 then b += ((idx, seg, ch.offsetAt(slot)))
                loop(idx + 1L)

      loop(lo)
      b.result()

  /** Serialize into a `"WIDX"` v1 binary checkpoint with trailing CRC32. */
  def serialize: Array[Byte] =
    val baseIdx = if entryCount == 0 then 1L else firstIndex
    val payloadSize = 4 + 1 + 8 + 4 + entryCount * 20
    val buf = ByteBuffer.allocate(payloadSize + 4)
    val _ = buf.putInt(WalIndex.CheckpointMagic)
    val _ = buf.put(WalIndex.CheckpointVersion)
    val _ = buf.putLong(baseIdx)
    val _ = buf.putInt(entryCount)

    @tailrec
    def writeSupers(spPos: Int): Unit =
      if spPos < superPages.length then
        val sp = superPages(spPos)
        if sp.occupied > 0 then
          val spBaseIdx = (baseSuperPage + spPos.toLong) << SuperShift
          writeChunks(sp, spBaseIdx, 0)
        writeSupers(spPos + 1)

    @tailrec
    def writeChunks(sp: SuperPage, spBaseIdx: Long, cPos: Int): Unit =
      if cPos < ChunksPerSuper then
        val ch = sp.chunkAt(cPos)
        if ch.occupied > 0 then
          val chBaseIdx = spBaseIdx + (cPos.toLong << ChunkShift)
          writeSlots(ch, chBaseIdx, 0)
        writeChunks(sp, spBaseIdx, cPos + 1)

    @tailrec
    def writeSlots(ch: IndexChunk, chBaseIdx: Long, slot: Int): Unit =
      if slot < ChunkSize then
        val seg = ch.segmentIdAt(slot)
        if seg != -1 then
          val _ = buf.putLong(chBaseIdx + slot.toLong)
          val _ = buf.putInt(seg)
          val _ = buf.putLong(ch.offsetAt(slot))
        writeSlots(ch, chBaseIdx, slot + 1)

    if entryCount > 0 then writeSupers(0)
    val raw = buf.array()
    val crc = new CRC32()
    crc.update(raw, 0, payloadSize)
    val _ = buf.putInt(payloadSize, crc.getValue.toInt)
    raw

  private def sumOccupiedSupers(from: Int, until: Int): Int =
    @tailrec
    def loop(i: Int, acc: Int): Int =
      if i >= until || i >= superPages.length then acc
      else loop(i + 1, acc + superPages(i).occupied)
    loop(math.max(0, from), 0)

  private def ensureSuperPageRange(targetSp: Long): WalIndexState =
    if targetSp < baseSuperPage then
      val shift = (baseSuperPage - targetSp).toInt
      val nextLen = superPages.length + shift
      val arr = Array.fill[SuperPage](nextLen)(EmptySuperPage)
      System.arraycopy(
        superPages.unsafeArray,
        0,
        arr,
        shift,
        superPages.length
      )
      copy(
        baseSuperPage = targetSp,
        superPages = IArray.unsafeFromArray(arr)
      )
    else if targetSp >= baseSuperPage + superPages.length.toLong then
      val neededLen = (targetSp - baseSuperPage + 1L).toInt
      val arr = Array.fill[SuperPage](neededLen)(EmptySuperPage)
      System.arraycopy(superPages.unsafeArray, 0, arr, 0, superPages.length)
      copy(superPages = IArray.unsafeFromArray(arr))
    else this

object WalIndexState:
  private[mmap] inline val ChunkShift = 6
  private[mmap] inline val ChunkSize = 1 << ChunkShift // 64
  private[mmap] inline val SlotMask = 63L

  private[mmap] inline val SuperShift = 12
  private[mmap] inline val ChunksPerSuper = 64 // 64 * 64 = 4096
  private[mmap] inline val ChunkIndexMask = 63L

  private[mmap] final class IndexChunk private[WalIndexState] (
      private val segIds: IArray[Int],
      private val offsets: IArray[Long],
      val occupied: Int
  ):
    inline def segmentIdAt(slot: Int): Int = segIds(slot)
    inline def offsetAt(slot: Int): Long = offsets(slot)

    def updated(
        slot: Int,
        segmentId: Int,
        fileOffset: Long
    ): (IndexChunk, Int) =
      val nextSegs = new Array[Int](ChunkSize)
      val nextOffs = new Array[Long](ChunkSize)
      System.arraycopy(segIds.unsafeArray, 0, nextSegs, 0, ChunkSize)
      System.arraycopy(offsets.unsafeArray, 0, nextOffs, 0, ChunkSize)
      val delta = if nextSegs(slot) == -1 then 1 else 0
      nextSegs(slot) = segmentId
      nextOffs(slot) = fileOffset
      (
        new IndexChunk(
          IArray.unsafeFromArray(nextSegs),
          IArray.unsafeFromArray(nextOffs),
          occupied + delta
        ),
        delta
      )

    def updatedSlice(
        segmentId: Int,
        entries: Vector[(Long, Long)],
        from: Int,
        until: Int
    ): (IndexChunk, Int, Long, Long) =
      val nextSegs = new Array[Int](ChunkSize)
      val nextOffs = new Array[Long](ChunkSize)
      System.arraycopy(segIds.unsafeArray, 0, nextSegs, 0, ChunkSize)
      System.arraycopy(offsets.unsafeArray, 0, nextOffs, 0, ChunkSize)

      @tailrec
      def applyRun(
          i: Int,
          deltaAcc: Int,
          minIdx: Long,
          maxIdx: Long
      ): (Int, Long, Long) =
        if i >= until then (deltaAcc, minIdx, maxIdx)
        else
          val (lIdx, fOff) = entries(i)
          val slot = (lIdx & SlotMask).toInt
          val d = if nextSegs(slot) == -1 then 1 else 0
          nextSegs(slot) = segmentId
          nextOffs(slot) = fOff
          applyRun(
            i + 1,
            deltaAcc + d,
            math.min(minIdx, lIdx),
            math.max(maxIdx, lIdx)
          )

      val firstEntryIdx = entries(from)._1
      val (delta, minI, maxI) =
        applyRun(from, 0, firstEntryIdx, firstEntryIdx)
      (
        new IndexChunk(
          IArray.unsafeFromArray(nextSegs),
          IArray.unsafeFromArray(nextOffs),
          occupied + delta
        ),
        delta,
        minI,
        maxI
      )

    def truncateFromSlot(cutSlot: Int): (IndexChunk, Int) =
      if cutSlot <= 0 then (EmptyChunk, occupied)
      else if occupied == 0 || cutSlot >= ChunkSize then (this, 0)
      else
        val nextSegs = Array.fill[Int](ChunkSize)(-1)
        val nextOffs = Array.fill[Long](ChunkSize)(-1L)
        System.arraycopy(segIds.unsafeArray, 0, nextSegs, 0, cutSlot)
        System.arraycopy(offsets.unsafeArray, 0, nextOffs, 0, cutSlot)

        @tailrec
        def countRemoved(s: Int, acc: Int): Int =
          if s >= ChunkSize then acc
          else
            val r = if segIds(s) != -1 then 1 else 0
            countRemoved(s + 1, acc + r)

        val removed = countRemoved(cutSlot, 0)
        val remOcc = occupied - removed
        if remOcc == 0 then (EmptyChunk, occupied)
        else
          (
            new IndexChunk(
              IArray.unsafeFromArray(nextSegs),
              IArray.unsafeFromArray(nextOffs),
              remOcc
            ),
            removed
          )

    def discardBeforeSlot(cutSlot: Int): (IndexChunk, Int) =
      if cutSlot <= 0 || occupied == 0 then (this, 0)
      else if cutSlot >= ChunkSize then (EmptyChunk, occupied)
      else
        val nextSegs = Array.fill[Int](ChunkSize)(-1)
        val nextOffs = Array.fill[Long](ChunkSize)(-1L)
        val keep = ChunkSize - cutSlot
        System.arraycopy(segIds.unsafeArray, cutSlot, nextSegs, cutSlot, keep)
        System.arraycopy(offsets.unsafeArray, cutSlot, nextOffs, cutSlot, keep)

        @tailrec
        def countRemoved(s: Int, acc: Int): Int =
          if s >= cutSlot then acc
          else
            val r = if segIds(s) != -1 then 1 else 0
            countRemoved(s + 1, acc + r)

        val removed = countRemoved(0, 0)
        val remOcc = occupied - removed
        if remOcc == 0 then (EmptyChunk, occupied)
        else
          (
            new IndexChunk(
              IArray.unsafeFromArray(nextSegs),
              IArray.unsafeFromArray(nextOffs),
              remOcc
            ),
            removed
          )

  private[mmap] val EmptyChunk: IndexChunk =
    new IndexChunk(
      IArray.fill(ChunkSize)(-1),
      IArray.fill(ChunkSize)(-1L),
      0
    )

  private[mmap] final class SuperPage private[WalIndexState] (
      private val chunks: IArray[IndexChunk],
      val occupied: Int
  ):
    inline def chunkAt(chunkIdx: Int): IndexChunk = chunks(chunkIdx)

    def updatedChunk(chunkIdx: Int, nextChunk: IndexChunk): SuperPage =
      val oldOcc = chunks(chunkIdx).occupied
      val nextOcc = occupied - oldOcc + nextChunk.occupied
      if nextOcc == 0 then EmptySuperPage
      else
        val arr = new Array[IndexChunk](ChunksPerSuper)
        System.arraycopy(chunks.unsafeArray, 0, arr, 0, ChunksPerSuper)
        arr(chunkIdx) = nextChunk
        new SuperPage(IArray.unsafeFromArray(arr), nextOcc)

    def truncateFrom(cutChunk: Int, cutSlot: Int): (SuperPage, Int) =
      if occupied == 0 then (EmptySuperPage, 0)
      else
        @tailrec
        def countChunksFrom(c: Int, acc: Int): Int =
          if c >= ChunksPerSuper then acc
          else countChunksFrom(c + 1, acc + chunks(c).occupied)

        val removedAfterChunk = countChunksFrom(cutChunk + 1, 0)
        val (trimmedCutChunk, removedInCut) =
          chunks(cutChunk).truncateFromSlot(cutSlot)
        val totalRemoved = removedAfterChunk + removedInCut
        val remOcc = occupied - totalRemoved
        if remOcc == 0 then (EmptySuperPage, occupied)
        else
          val arr = Array.fill[IndexChunk](ChunksPerSuper)(EmptyChunk)
          if cutChunk > 0 then
            System.arraycopy(chunks.unsafeArray, 0, arr, 0, cutChunk)
          arr(cutChunk) = trimmedCutChunk
          (new SuperPage(IArray.unsafeFromArray(arr), remOcc), totalRemoved)

    def discardBefore(cutChunk: Int, cutSlot: Int): (SuperPage, Int) =
      if occupied == 0 then (EmptySuperPage, 0)
      else
        @tailrec
        def countChunksUntil(c: Int, acc: Int): Int =
          if c >= cutChunk then acc
          else countChunksUntil(c + 1, acc + chunks(c).occupied)

        val removedBeforeChunk = countChunksUntil(0, 0)
        val (trimmedCutChunk, removedInCut) =
          chunks(cutChunk).discardBeforeSlot(cutSlot)
        val totalRemoved = removedBeforeChunk + removedInCut
        val remOcc = occupied - totalRemoved
        if remOcc == 0 then (EmptySuperPage, occupied)
        else
          val arr = Array.fill[IndexChunk](ChunksPerSuper)(EmptyChunk)
          arr(cutChunk) = trimmedCutChunk
          val tailCount = ChunksPerSuper - (cutChunk + 1)
          if tailCount > 0 then
            System.arraycopy(
              chunks.unsafeArray,
              cutChunk + 1,
              arr,
              cutChunk + 1,
              tailCount
            )
          (new SuperPage(IArray.unsafeFromArray(arr), remOcc), totalRemoved)

  private[mmap] val EmptySuperPage: SuperPage =
    new SuperPage(IArray.fill(ChunksPerSuper)(EmptyChunk), 0)

  val empty: WalIndexState =
    WalIndexState(
      baseSuperPage = 0L,
      superPages = IArray.empty[SuperPage],
      entryCount = 0,
      firstIndex = 0L,
      lastIndex = 0L
    )

  private def copyUpdateSuper(
      src: IArray[SuperPage],
      pos: Int,
      updated: SuperPage
  ): IArray[SuperPage] =
    val arr = new Array[SuperPage](src.length)
    System.arraycopy(src.unsafeArray, 0, arr, 0, src.length)
    arr(pos) = updated
    IArray.unsafeFromArray(arr)

  @tailrec
  private def findPreviousOccupied(
      baseSp: Long,
      supers: IArray[SuperPage],
      candidateIdx: Long
  ): Long =
    if candidateIdx < 0L then -1L
    else
      val spOff = (candidateIdx >>> SuperShift) - baseSp
      if spOff < 0L then -1L
      else if spOff >= supers.length.toLong then
        val maxIdx = ((baseSp + supers.length.toLong) << SuperShift) - 1L
        findPreviousOccupied(baseSp, supers, maxIdx)
      else
        val sp = supers(spOff.toInt)
        if sp.occupied == 0 then
          val prevSpEnd = ((candidateIdx >>> SuperShift) << SuperShift) - 1L
          findPreviousOccupied(baseSp, supers, prevSpEnd)
        else
          val cIdx = ((candidateIdx >>> ChunkShift) & ChunkIndexMask).toInt
          val ch = sp.chunkAt(cIdx)
          if ch.occupied == 0 then
            val prevChEnd = ((candidateIdx >>> ChunkShift) << ChunkShift) - 1L
            findPreviousOccupied(baseSp, supers, prevChEnd)
          else
            val slot = (candidateIdx & SlotMask).toInt
            if ch.segmentIdAt(slot) != -1 then candidateIdx
            else findPreviousOccupied(baseSp, supers, candidateIdx - 1L)

  @tailrec
  private def findNextOccupied(
      baseSp: Long,
      supers: IArray[SuperPage],
      candidateIdx: Long,
      upperInclusive: Long
  ): Long =
    if candidateIdx > upperInclusive then -1L
    else
      val spOff = (candidateIdx >>> SuperShift) - baseSp
      if spOff < 0L then
        findNextOccupied(baseSp, supers, baseSp << SuperShift, upperInclusive)
      else if spOff >= supers.length.toLong then -1L
      else
        val sp = supers(spOff.toInt)
        if sp.occupied == 0 then
          val nextSpStart = ((candidateIdx >>> SuperShift) + 1L) << SuperShift
          findNextOccupied(baseSp, supers, nextSpStart, upperInclusive)
        else
          val cIdx = ((candidateIdx >>> ChunkShift) & ChunkIndexMask).toInt
          val ch = sp.chunkAt(cIdx)
          if ch.occupied == 0 then
            val nextChStart = ((candidateIdx >>> ChunkShift) + 1L) << ChunkShift
            findNextOccupied(baseSp, supers, nextChStart, upperInclusive)
          else
            val slot = (candidateIdx & SlotMask).toInt
            if ch.segmentIdAt(slot) != -1 then candidateIdx
            else
              findNextOccupied(
                baseSp,
                supers,
                candidateIdx + 1L,
                upperInclusive
              )

  /** Pure `"WIDX"` v1 (and legacy v0) checkpoint decoder. */
  def deserializeChecked(bytes: Array[Byte]): Either[String, WalIndexState] =
    @tailrec
    def readEntries(
        buf: ByteBuffer,
        remaining: Long,
        acc: WalIndexState
    ): WalIndexState =
      if remaining <= 0L then acc
      else
        val logIdx = buf.getLong()
        val segId = buf.getInt()
        val fileOff = buf.getLong()
        readEntries(buf, remaining - 1L, acc.put(logIdx, segId, fileOff))

    if bytes.isEmpty then Left("Empty WalIndex checkpoint")
    else if bytes.length >= 4 && ByteBuffer
        .wrap(bytes, 0, 4)
        .getInt == WalIndex.CheckpointMagic
    then
      if bytes.length < 21 then
        Left(s"Truncated WIDX checkpoint (${bytes.length} bytes)")
      else
        val buf = ByteBuffer.wrap(bytes)
        val _ = buf.getInt()
        val version = buf.get()
        if version != WalIndex.CheckpointVersion then
          Left(s"Unsupported WIDX checkpoint version: $version")
        else
          val _ = buf.getLong() // baseIndex
          val count = buf.getInt()
          val expectedLen = 4L + 1L + 8L + 4L + count.toLong * 20L + 4L
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
            else Right(readEntries(buf, count.toLong, WalIndexState.empty))
    else if bytes.length >= 8 then
      val buf = ByteBuffer.wrap(bytes)
      val firstLong = buf.getLong(0)
      val isLegacyV0 =
        firstLong >= 0L && firstLong <= Int.MaxValue.toLong &&
          bytes.length.toLong == 8L + firstLong * 20L
      if isLegacyV0 then
        val count = buf.getLong()
        Right(readEntries(buf, count, WalIndexState.empty))
      else if bytes.length >= 12 then
        val _ = buf.getLong()
        val count = buf.getInt()
        if count >= 0 && bytes.length.toLong == 12L + count.toLong * 20L then
          Right(readEntries(buf, count.toLong, WalIndexState.empty))
        else
          Left(s"Corrupted legacy WalIndex checkpoint (${bytes.length} bytes)")
      else Left(s"Truncated WalIndex checkpoint (${bytes.length} bytes)")
    else Left(s"Truncated WalIndex checkpoint (${bytes.length} bytes)")

/** Lock-free atomic façade over immutable [[WalIndexState]] (`0` `synchronized`
  * blocks, `0` `var`s, `0` `while` loops).
  *
  * Inside [[MmapWalEngine]], [[WalIndexState]] is managed directly as an
  * immutable value; `WalIndex` preserves full source and binary compatibility
  * for standalone callers and benchmarks.
  */
class WalIndex(initial: WalIndexState = WalIndexState.empty):
  /** Logical result of a lookup. */
  case class Location(segmentId: Int, fileOffset: Long)

  private val ref = new AtomicReference[WalIndexState](initial)

  /** Current immutable snapshot of the index. */
  def snapshot: WalIndexState = ref.get()

  /** Register a log entry's physical location via lock-free CAS. */
  def put(logIndex: Long, segmentId: Int, fileOffset: Long): Unit =
    val _ = ref.updateAndGet(_.put(logIndex, segmentId, fileOffset))

  /** Look up the physical location of `logIndex`. */
  def get(logIndex: Long): Option[Location] =
    val st = ref.get()
    val segId = st.getSegmentId(logIndex)
    if segId == -1 then None
    else Some(Location(segId, st.getFileOffset(logIndex)))

  /** Allocation-free segment ID lookup (`-1` if absent). */
  def getSegmentId(logIndex: Long): Int =
    ref.get().getSegmentId(logIndex)

  /** Allocation-free file offset lookup (`-1L` if absent). */
  def getFileOffset(logIndex: Long): Long =
    ref.get().getFileOffset(logIndex)

  /** Highest indexed log index, or `0L` if empty. */
  def lastIndex: Long =
    ref.get().lastIndex

  /** True iff the index contains zero entries. */
  def isEmpty: Boolean =
    ref.get().isEmpty

  /** Remove all entries strictly before `toIndex`. */
  def discardBefore(toIndex: Long): Unit =
    val _ = ref.updateAndGet(_.discardBefore(toIndex))

  /** Remove all entries at or after `fromIndex`. */
  def truncateFrom(fromIndex: Long): Unit =
    val _ = ref.updateAndGet(_.truncateFrom(fromIndex))

  /** Remove all entries. */
  def clear(): Unit =
    ref.set(WalIndexState.empty)

  /** Serialize the current immutable snapshot with `"WIDX"` v1 + CRC32 framing.
    */
  def serialize: Array[Byte] =
    ref.get().serialize

  /** Total number of indexed entries. */
  def size: Int =
    ref.get().size

object WalIndex:
  private[mmap] val CheckpointMagic: Int = 0x57494458 // "WIDX"
  private[mmap] val CheckpointVersion: Byte = 1

  /** Validate and deserialize a `"WIDX"` checkpoint byte array. */
  def deserializeChecked(bytes: Array[Byte]): Either[String, WalIndex] =
    WalIndexState.deserializeChecked(bytes).map(st => new WalIndex(st))

  /** Deserialize an index from a checkpoint byte array, throwing
    * [[raft.state.RaftError.StorageCorruption]] on invalid data.
    */
  def deserialize(bytes: Array[Byte]): WalIndex =
    if bytes.isEmpty then new WalIndex(WalIndexState.empty)
    else
      deserializeChecked(bytes) match
        case Right(idx) => idx
        case Left(err)  => throw RaftError.StorageCorruption(err)
