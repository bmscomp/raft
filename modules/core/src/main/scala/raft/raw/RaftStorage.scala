package raft.raw

import scala.annotation.tailrec

import raft.state.{
  Bytes,
  ClusterConfig,
  HardState,
  Log,
  LogType,
  SnapshotMetadata
}

/** Pure synchronous read-only storage view consumed by [[RawNode]] and
  * [[raft.kernel.ConsensusKernel]] (modeled after `tikv/raft-rs`'s `Storage`).
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

/** Immutable state snapshot backing [[MemStorage]]. */
final case class MemStorageState(
    bootstrapConfig: ClusterConfig,
    hardState: HardState,
    clusterConfig: ClusterConfig,
    snapshot: Option[SnapshotMetadata],
    logs: Vector[Log]
):
  def firstIndex: Long =
    logs.headOption
      .map(_.index)
      .orElse(snapshot.map(_.lastIncludedIndex + 1L))
      .getOrElse(1L)

  def lastIndex: Long =
    logs.lastOption
      .map(_.index)
      .orElse(snapshot.map(_.lastIncludedIndex))
      .getOrElse(0L)

  def term(index: Long): Option[Long] =
    if index == 0L then Some(0L)
    else
      snapshot match
        case Some(snap) if index == snap.lastIncludedIndex =>
          Some(snap.lastIncludedTerm)
        case Some(snap) if index < snap.lastIncludedIndex =>
          None
        case _ =>
          val offset = firstIndex
          val diff = index - offset
          if diff >= 0L && diff < logs.length.toLong then
            val candidate = logs(diff.toInt)
            if candidate.index == index then Some(candidate.term)
            else logs.find(_.index == index).map(_.term)
          else logs.find(_.index == index).map(_.term)

  def entries(
      low: Long,
      high: Long,
      maxBytes: Long = Long.MaxValue
  ): Vector[Log] =
    if low >= high || logs.isEmpty then Vector.empty
    else
      val slice = logs.filter(e => e.index >= low && e.index < high)
      if maxBytes == Long.MaxValue then slice
      else MemStorageState.takeUpToBytes(slice, maxBytes)

  /** Truncate entries from `fromIndex` onwards AND reconstruct `clusterConfig`
    * from snapshot + remaining log entries so aborted Joint Consensus
    * configurations roll back cleanly (Ongaro §4.1).
    */
  def truncateFrom(fromIndex: Long): MemStorageState =
    val kept = logs.takeWhile(_.index < fromIndex)
    val baseCfg = snapshot.flatMap(_.config).getOrElse(bootstrapConfig)
    val resolvedCfg = kept
      .filter(_.logType == LogType.Configuration)
      .lastOption
      .flatMap(e => ClusterConfig.decodeBytes(e.payload).toOption)
      .getOrElse(baseCfg)
    copy(logs = kept, clusterConfig = resolvedCfg)

  def append(newEntries: Seq[Log]): MemStorageState =
    if newEntries.isEmpty then this
    else
      val firstNew = newEntries.head.index
      val merged = logs.takeWhile(_.index < firstNew) ++ newEntries
      val nextCfg = newEntries
        .filter(_.logType == LogType.Configuration)
        .lastOption
        .flatMap(e => ClusterConfig.decodeBytes(e.payload).toOption)
        .getOrElse(clusterConfig)
      copy(logs = merged, clusterConfig = nextCfg)

  def applySnapshot(meta: SnapshotMetadata): MemStorageState =
    val nextCfg = meta.config.getOrElse(clusterConfig)
    copy(
      snapshot = Some(meta),
      clusterConfig = nextCfg,
      logs = logs.dropWhile(_.index <= meta.lastIncludedIndex)
    )

  def compactTo(compactIndex: Long, snapshotPayload: Bytes): MemStorageState =
    term(compactIndex) match
      case None              => this
      case Some(compactTerm) =>
        val snap = SnapshotMetadata(
          compactIndex,
          compactTerm,
          Some(clusterConfig),
          snapshotPayload
        )
        copy(
          snapshot = Some(snap),
          logs = logs.dropWhile(_.index <= compactIndex)
        )

  def persistReady(ready: Ready): MemStorageState =
    val s1 = ready.snapshot.fold(this)(applySnapshot)
    val s2 = ready.truncateFromIndex.fold(s1)(s1.truncateFrom)
    val s3 = if ready.entries.nonEmpty then s2.append(ready.entries) else s2
    ready.hardState.fold(s3)(hs => s3.copy(hardState = hs))

object MemStorageState:
  private def takeUpToBytes(
      slice: Vector[Log],
      maxBytes: Long
  ): Vector[Log] =
    @tailrec
    def loop(
        idx: Int,
        usedBytes: Long,
        acc: Vector[Log]
    ): Vector[Log] =
      if idx >= slice.length then acc
      else
        val entry = slice(idx)
        val sz = entry.payload.length.toLong
        if acc.nonEmpty && usedBytes + sz > maxBytes then acc
        else loop(idx + 1, usedBytes + sz, acc :+ entry)

    loop(0, 0L, Vector.empty)

/** Reference in-memory implementation of [[RaftStorage]] backed by a single
  * immutable [[MemStorageState]] value.
  */
final class MemStorage private (initial: MemStorageState) extends RaftStorage:
  private val stateRef =
    new java.util.concurrent.atomic.AtomicReference[MemStorageState](initial)

  def stateSnapshot: MemStorageState = stateRef.get()

  def initialState: (HardState, ClusterConfig) =
    val s = stateRef.get()
    (s.hardState, s.clusterConfig)

  def hardState: HardState = stateRef.get().hardState
  def clusterConfig: ClusterConfig = stateRef.get().clusterConfig
  def snapshotMetadata: Option[SnapshotMetadata] = stateRef.get().snapshot
  def firstIndex: Long = stateRef.get().firstIndex
  def lastIndex: Long = stateRef.get().lastIndex
  def term(index: Long): Option[Long] = stateRef.get().term(index)

  def entries(
      low: Long,
      high: Long,
      maxBytes: Long = Long.MaxValue
  ): Vector[Log] =
    stateRef.get().entries(low, high, maxBytes)

  // === Host-side persistence mutators ===

  def setHardState(hs: HardState): Unit =
    val _ = stateRef.updateAndGet(_.copy(hardState = hs))

  def setClusterConfig(cfg: ClusterConfig): Unit =
    val _ = stateRef.updateAndGet(_.copy(clusterConfig = cfg))

  def truncateFrom(fromIndex: Long): Unit =
    val _ = stateRef.updateAndGet(_.truncateFrom(fromIndex))

  def append(newEntries: Seq[Log]): Unit =
    val _ = stateRef.updateAndGet(_.append(newEntries))

  def applySnapshot(meta: SnapshotMetadata): Unit =
    val _ = stateRef.updateAndGet(_.applySnapshot(meta))

  def compactTo(
      compactIndex: Long,
      snapshotData: Array[Byte] = Array.emptyByteArray
  ): Unit =
    compactToBytes(compactIndex, Bytes.copyFrom(snapshotData))

  def compactToBytes(compactIndex: Long, snapshotPayload: Bytes): Unit =
    val _ = stateRef.updateAndGet(_.compactTo(compactIndex, snapshotPayload))

  def persistReady(ready: Ready): Unit =
    val _ = stateRef.updateAndGet(_.persistReady(ready))

object MemStorage:
  def create(
      config: ClusterConfig,
      hardState: HardState = HardState.Empty,
      snapshot: Option[SnapshotMetadata] = None,
      entries: Vector[Log] = Vector.empty
  ): MemStorage =
    val baseCfg = snapshot.flatMap(_.config).getOrElse(config)
    val resolvedCfg = entries
      .filter(_.logType == LogType.Configuration)
      .lastOption
      .flatMap(e => ClusterConfig.decodeBytes(e.payload).toOption)
      .getOrElse(baseCfg)
    new MemStorage(
      MemStorageState(
        bootstrapConfig = config,
        hardState = hardState,
        clusterConfig = resolvedCfg,
        snapshot = snapshot,
        logs = entries
      )
    )
