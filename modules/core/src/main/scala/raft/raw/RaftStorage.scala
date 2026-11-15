package raft.raw

import raft.state.{ClusterConfig, HardState, Log, SnapshotMetadata}

/** Pure synchronous read-only storage view consumed by [[RawNode]] (modeled
  * after `tikv/raft-rs`'s `Storage` trait).
  *
  * **Sans-I/O Principle**: `RawNode` *only reads* from `RaftStorage`; it never
  * performs writes or disk flushes itself. Every state mutation (`HardState`,
  * unstable `Log` entries, `TruncateLog`, `SnapshotMetadata`) is emitted in
  * [[Ready]] for the **host system** to persist in its own WAL/database before
  * calling `RawNode.advance(ready)`.
  */
trait RaftStorage:
  /** Initial persistent state (`HardState` + latest `ClusterConfig`) on boot.
    */
  def initialState: (HardState, ClusterConfig)

  /** Read contiguous persisted log entries in `[low, high)` (end-exclusive),
    * capped at `maxBytes` payload bytes.
    */
  def entries(
      low: Long,
      high: Long,
      maxBytes: Long = Long.MaxValue
  ): Vector[Log]

  /** Look up the term at `index` (including
    * `snapshotMetadata.lastIncludedIndex`).
    */
  def term(index: Long): Option[Long]

  /** First available log index (`snapshot.lastIncludedIndex + 1`, or `1` if no
    * snapshot).
    */
  def firstIndex: Long

  /** Highest persisted log index (`snapshot.lastIncludedIndex` if log is
    * empty).
    */
  def lastIndex: Long

  /** Metadata of the most recently installed snapshot, if any. */
  def snapshotMetadata: Option[SnapshotMetadata]

/** Reference in-memory implementation of [[RaftStorage]] (modeled after
  * `tikv/raft-rs`'s `MemStorage`) for deterministic simulation, unit tests, and
  * lightweight in-memory state machines.
  */
final class MemStorage private (
    private var hardStateVar: HardState,
    private var clusterConfigVar: ClusterConfig,
    private var snapshotVar: Option[SnapshotMetadata],
    private var logsVar: Vector[Log]
) extends RaftStorage:

  def initialState: (HardState, ClusterConfig) =
    (hardStateVar, clusterConfigVar)

  def hardState: HardState = hardStateVar
  def clusterConfig: ClusterConfig = clusterConfigVar
  def snapshotMetadata: Option[SnapshotMetadata] = snapshotVar

  def firstIndex: Long =
    logsVar.headOption
      .map(_.index)
      .orElse(snapshotVar.map(_.lastIncludedIndex + 1L))
      .getOrElse(1L)

  def lastIndex: Long =
    logsVar.lastOption
      .map(_.index)
      .orElse(snapshotVar.map(_.lastIncludedIndex))
      .getOrElse(0L)

  def term(index: Long): Option[Long] =
    if index == 0L then Some(0L)
    else
      snapshotVar match
        case Some(snap) if index == snap.lastIncludedIndex =>
          Some(snap.lastIncludedTerm)
        case Some(snap) if index < snap.lastIncludedIndex =>
          None
        case _ =>
          val offset = firstIndex
          val pos = (index - offset).toInt
          if pos >= 0 && pos < logsVar.length && logsVar(pos).index == index
          then Some(logsVar(pos).term)
          else logsVar.find(_.index == index).map(_.term)

  def entries(
      low: Long,
      high: Long,
      maxBytes: Long = Long.MaxValue
  ): Vector[Log] =
    if low >= high || logsVar.isEmpty then Vector.empty
    else
      val slice = logsVar.filter(e => e.index >= low && e.index < high)
      if maxBytes == Long.MaxValue then slice
      else
        val out = Vector.newBuilder[Log]
        var bytes = 0L
        var count = 0
        val iter = slice.iterator
        var stop = false
        while iter.hasNext && !stop do
          val entry = iter.next()
          val sz = entry.data.length.toLong
          if count > 0 && bytes + sz > maxBytes then stop = true
          else
            out += entry
            bytes += sz
            count += 1
        out.result()

  // === Host-side persistence mutators ===

  def setHardState(hs: HardState): Unit =
    hardStateVar = hs

  def setClusterConfig(cfg: ClusterConfig): Unit =
    clusterConfigVar = cfg

  def truncateFrom(fromIndex: Long): Unit =
    logsVar = logsVar.takeWhile(_.index < fromIndex)

  def append(newEntries: Seq[Log]): Unit =
    if newEntries.nonEmpty then
      val firstNew = newEntries.head.index
      logsVar = logsVar.takeWhile(_.index < firstNew) ++ newEntries
      newEntries.foreach { e =>
        if e.logType == raft.state.LogType.Configuration then
          ClusterConfig.decode(e.data).foreach(cfg => clusterConfigVar = cfg)
      }

  def applySnapshot(meta: SnapshotMetadata): Unit =
    snapshotVar = Some(meta)
    meta.config.foreach(cfg => clusterConfigVar = cfg)
    logsVar = logsVar.dropWhile(_.index <= meta.lastIncludedIndex)

  def compactTo(
      compactIndex: Long,
      snapshotData: Array[Byte] = Array.emptyByteArray
  ): Unit =
    term(compactIndex).foreach { compactTerm =>
      snapshotVar = Some(
        SnapshotMetadata(
          compactIndex,
          compactTerm,
          Some(clusterConfigVar),
          snapshotData
        )
      )
      logsVar = logsVar.dropWhile(_.index <= compactIndex)
    }

  /** Convenience helper applying the storage half of a [[Ready]] batch in exact
    * persist order.
    */
  def persistReady(ready: Ready): Unit =
    ready.snapshot.foreach(applySnapshot)
    ready.truncateFromIndex.foreach(truncateFrom)
    if ready.entries.nonEmpty then append(ready.entries)
    ready.hardState.foreach(setHardState)

object MemStorage:
  def create(
      config: ClusterConfig,
      hardState: HardState = HardState.Empty,
      snapshot: Option[SnapshotMetadata] = None,
      entries: Vector[Log] = Vector.empty
  ): MemStorage =
    new MemStorage(hardState, config, snapshot, entries)
