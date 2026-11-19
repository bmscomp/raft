package raft.storage

import cats.effect.Async
import cats.effect.std.Mutex
import cats.syntax.all.*
import fs2.{Chunk, Stream}

import raft.raw.{LightReady, MemStorage, RaftStorage, Ready}
import raft.state.{
  Bytes,
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
  *   - Lock-free `O(1)` synchronous reads via immutable `MemStorageState`
  *     snapshots (`0` `synchronized` blocks).
  *   - Effectful persistence (`persistReady`, `persistLightReady`, `compactTo`)
  *     serialized via Cats Effect [[Mutex]], flushing `msync`/`fsync` durably
  *     to `engine` *before* publishing the updated `MemStorage` snapshot.
  */
final class EngineBackedRaftStorage[F[_]: Async] private (
    val engine: StorageEngine[F],
    writeMutex: Mutex[F],
    cache: MemStorage
) extends RaftStorage:

  private val F = Async[F]

  def initialState: (HardState, ClusterConfig) =
    cache.initialState

  def entries(
      low: Long,
      high: Long,
      maxBytes: Long = Long.MaxValue
  ): Vector[Log] =
    cache.entries(low, high, maxBytes)

  def term(index: Long): Option[Long] =
    cache.term(index)

  def firstIndex: Long =
    cache.firstIndex

  def lastIndex: Long =
    cache.lastIndex

  def snapshotMetadata: Option[SnapshotMetadata] =
    cache.snapshotMetadata

  def hardState: HardState =
    cache.hardState

  def clusterConfig: ClusterConfig =
    cache.clusterConfig

  /** Durably persist all storage mutations in a Sans-I/O `Ready` batch to the
    * underlying [[StorageEngine]] before updating the lock-free read cache.
    */
  def persistReady(ready: Ready): F[Unit] =
    writeMutex.lock.surround {
      val persistSnap = ready.snapshot.traverse_ { meta =>
        val raw = meta.payload
        val snapStream =
          if raw.nonEmpty then
            Stream.chunk(Chunk.byteBuffer(raw.asReadOnlyByteBuffer)).covary[F]
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
        F.delay(cache.persistReady(ready))
    }

  /** Durably persist follow-up `commitIndex` updates from `LightReady`. */
  def persistLightReady(light: LightReady): F[Unit] =
    writeMutex.lock.surround {
      light.commitIndex.traverse_ { cIdx =>
        for
          nextHs <- F.delay(cache.hardState.copy(commitIndex = cIdx))
          _ <- engine.writeHardState(nextHs)
          _ <- F.delay(cache.setHardState(nextHs))
        yield ()
      }
    }

  /** Save a compacted snapshot at `compactIndex` to `engine`, discard obsolete
    * WAL segments up to `compactIndex`, and publish the updated read cache.
    */
  def compactTo(
      compactIndex: Long,
      snapshotData: Array[Byte] = Array.emptyByteArray
  ): F[Unit] =
    compactToBytes(compactIndex, Bytes.copyFrom(snapshotData))

  /** Pure `Bytes`-based variant of [[compactTo]]. */
  def compactToBytes(
      compactIndex: Long,
      snapshotPayload: Bytes
  ): F[Unit] =
    writeMutex.lock.surround {
      F.delay((cache.term(compactIndex), cache.clusterConfig)).flatMap {
        case (Some(compactTerm), cfg) =>
          val dataStream =
            if snapshotPayload.nonEmpty then
              Stream
                .chunk(Chunk.byteBuffer(snapshotPayload.asReadOnlyByteBuffer))
                .covary[F]
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
            F.delay(cache.compactToBytes(compactIndex, snapshotPayload))
        case (None, _) => F.unit
      }
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
      mutex <- Mutex[F]
      hs <- engine.readHardState
      snapOpt <- engine.loadSnapshot
      snapMetaOpt <- snapOpt.traverse { snap =>
        snap.data.compile.to(Array).map { bytes =>
          SnapshotMetadata(
            lastIncludedIndex = snap.lastIncludedIndex,
            lastIncludedTerm = snap.lastIncludedTerm,
            config = snap.config,
            payload = Bytes.unsafeWrap(bytes)
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
          ClusterConfig.decodeBytes(entry.payload).getOrElse(acc)
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
    yield new EngineBackedRaftStorage[F](engine, mutex, mem)
