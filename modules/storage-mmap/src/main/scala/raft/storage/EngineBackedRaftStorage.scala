package raft.storage

import cats.effect.Async
import cats.syntax.all.*
import fs2.{Chunk, Stream}

import raft.raw.{LightReady, MemStorage, RaftStorage, Ready}
import raft.state.{
  ClusterConfig,
  Log,
  LogIndex,
  LogType,
  Snapshot,
  SnapshotMetadata
}

/** Production [[raft.raw.RaftStorage]] adapter backed by any durable
  * [[StorageEngine]] (such as [[raft.storage.mmap.MmapWalEngine]]).
  *
  * Follows the `tikv/raft-rs` / CockroachDB storage pattern:
  *   - On [[EngineBackedRaftStorage.open]], recovers durable `HardState`, the
  *     latest snapshot, active `ClusterConfig`, and uncompacted WAL entries
  *     into a synchronized in-memory read cache so Sans-I/O `RawNode` queries
  *     (`term`, `entries`, `firstIndex`, `lastIndex`) execute synchronously in
  *     `O(1)` without blocking event-loop threads.
  *   - [[persistReady]], [[persistLightReady]], and [[compactTo]] flush
  *     mutations durably (`msync`) to the underlying `StorageEngine` *before*
  *     updating the read cache and returning to `RawNode.advance`.
  */
final class EngineBackedRaftStorage[F[_]: Async] private (
    val engine: StorageEngine[F],
    cache: MemStorage
) extends RaftStorage:

  private val F = Async[F]

  def initialState: (HardState, ClusterConfig) =
    this.synchronized(cache.initialState)

  def entries(
      low: Long,
      high: Long,
      maxBytes: Long = Long.MaxValue
  ): Vector[Log] =
    this.synchronized(cache.entries(low, high, maxBytes))

  def term(index: Long): Option[Long] =
    this.synchronized(cache.term(index))

  def firstIndex: Long =
    this.synchronized(cache.firstIndex)

  def lastIndex: Long =
    this.synchronized(cache.lastIndex)

  def snapshotMetadata: Option[SnapshotMetadata] =
    this.synchronized(cache.snapshotMetadata)

  def hardState: HardState =
    this.synchronized(cache.hardState)

  def clusterConfig: ClusterConfig =
    this.synchronized(cache.clusterConfig)

  /** Durably persist all storage mutations in a Sans-I/O [[Ready]] batch to the
    * underlying [[StorageEngine]] (`snapshot`, `truncateFromIndex`, `entries`,
    * and `hardState`) before updating the read cache.
    */
  def persistReady(ready: Ready): F[Unit] =
    val persistSnap = ready.snapshot.traverse_ { meta =>
      val snapStream =
        if meta.data.nonEmpty then
          Stream.chunk(Chunk.array(meta.data)).covary[F]
        else Stream.empty.covary[F]
      engine.saveSnapshot(
        Snapshot[F](
          lastIncludedIndex = meta.lastIncludedIndex,
          lastIncludedTerm = meta.lastIncludedTerm,
          data = snapStream,
          config = meta.config
        )
      ) *> engine.clearLog
    }

    val persistTruncate = ready.truncateFromIndex.traverse_ { fromIdx =>
      if fromIdx >= 1L then engine.truncateFrom(LogIndex.unsafeFrom(fromIdx))
      else engine.clearLog
    }

    val persistEntries =
      if ready.entries.nonEmpty then engine.appendEntries(ready.entries)
      else F.unit

    val persistHs = ready.hardState.traverse_(engine.writeHardState)

    persistSnap *> persistTruncate *> persistEntries *> persistHs *>
      F.delay(this.synchronized(cache.persistReady(ready)))

  /** Durably persist follow-up `commitIndex` updates from [[LightReady]]. */
  def persistLightReady(light: LightReady): F[Unit] =
    light.commitIndex.traverse_ { cIdx =>
      for
        nextHs <- F.delay(
          this.synchronized(cache.hardState.copy(commitIndex = cIdx))
        )
        _ <- engine.writeHardState(nextHs)
        _ <- F.delay(this.synchronized(cache.setHardState(nextHs)))
      yield ()
    }

  /** Save a compacted snapshot at `compactIndex` to the underlying
    * [[StorageEngine]], discard obsolete WAL segments up to `compactIndex`, and
    * update the read cache.
    */
  def compactTo(
      compactIndex: Long,
      snapshotData: Array[Byte] = Array.emptyByteArray
  ): F[Unit] =
    F.delay(this.synchronized((cache.term(compactIndex), cache.clusterConfig)))
      .flatMap {
        case (Some(compactTerm), cfg) =>
          val dataStream =
            if snapshotData.nonEmpty then
              Stream.chunk(Chunk.array(snapshotData)).covary[F]
            else Stream.empty.covary[F]
          val snap = Snapshot[F](
            lastIncludedIndex = compactIndex,
            lastIncludedTerm = compactTerm,
            data = dataStream,
            config = Some(cfg)
          )
          val discardAt = LogIndex.unsafeFrom(compactIndex + 1L)
          engine.saveSnapshot(snap) *>
            engine.discardBefore(discardAt) *>
            F.delay(
              this.synchronized(cache.compactTo(compactIndex, snapshotData))
            )
        case (None, _) => F.unit
      }

object EngineBackedRaftStorage:

  /** Open or recover an [[EngineBackedRaftStorage]] from an existing
    * [[StorageEngine]].
    */
  def open[F[_]: Async](
      engine: StorageEngine[F],
      bootstrapConfig: ClusterConfig
  ): F[EngineBackedRaftStorage[F]] =
    for
      hs <- engine.readHardState
      snapOpt <- engine.loadSnapshot
      snapMetaOpt <- snapOpt.traverse { snap =>
        snap.data.compile.to(Array).map { bytes =>
          SnapshotMetadata(
            lastIncludedIndex = snap.lastIncludedIndex,
            lastIncludedTerm = snap.lastIncludedTerm,
            config = snap.config,
            data = bytes
          )
        }
      }
      lastIdx <- engine.lastLogIndex
      scanStart = snapMetaOpt.map(_.lastIncludedIndex + 1L).getOrElse(1L)
      tailEntries <-
        if lastIdx.value >= scanStart then
          engine.readRange(LogIndex.unsafeFrom(scanStart), lastIdx)
        else Async[F].pure(Vector.empty[Log])
      baseCfg = snapMetaOpt.flatMap(_.config).getOrElse(bootstrapConfig)
      finalCfg = tailEntries.foldLeft(baseCfg) { (acc, entry) =>
        if entry.logType == LogType.Configuration then
          ClusterConfig.decode(entry.data).getOrElse(acc)
        else acc
      }
      effectiveHs = snapMetaOpt match
        case Some(sm) if hs.commitIndex < sm.lastIncludedIndex =>
          hs.copy(commitIndex = sm.lastIncludedIndex)
        case _ => hs
      mem = MemStorage.create(
        config = finalCfg,
        hardState = effectiveHs,
        snapshot = snapMetaOpt,
        entries = tailEntries
      )
    yield new EngineBackedRaftStorage[F](engine, mem)
