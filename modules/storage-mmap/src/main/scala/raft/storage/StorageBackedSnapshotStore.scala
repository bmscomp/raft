package raft.storage

import cats.effect.Async
import cats.syntax.all.*
import raft.spi.{SnapshotMetadata, SnapshotStore}
import raft.state.Snapshot
import raft.storage.mmap.MmapWalEngine

/** Adapter bridging [[StorageEngine]] (and [[MmapWalEngine]]) to the
  * [[raft.spi.SnapshotStore]] SPI consumed by [[raft.RaftNode]].
  */
class StorageBackedSnapshotStore[F[_]: Async] private (
    engine: StorageEngine[F],
    listOverride: Option[() => F[Vector[SnapshotMetadata]]],
    deleteOverride: Option[Long => F[Unit]]
) extends SnapshotStore[F]:

  def this(engine: StorageEngine[F]) =
    this(engine, None, None)

  def save(snapshot: Snapshot[F]): F[Unit] =
    engine.saveSnapshot(snapshot)

  def loadLatest: F[Option[Snapshot[F]]] =
    engine.loadSnapshot

  def list: F[Vector[SnapshotMetadata]] =
    listOverride match
      case Some(fn) => fn()
      case None     =>
        engine.loadSnapshot.flatMap {
          case None       => Async[F].pure(Vector.empty)
          case Some(snap) =>
            snap.data.compile.count.map { byteCount =>
              Vector(
                SnapshotMetadata(
                  snap.lastIncludedIndex,
                  snap.lastIncludedTerm,
                  byteCount
                )
              )
            }
        }

  def delete(index: Long): F[Unit] =
    deleteOverride match
      case Some(fn) => fn(index)
      case None     => Async[F].unit

object StorageBackedSnapshotStore:
  /** Create a [[SnapshotStore]] directly backed by an [[MmapWalEngine]],
    * delegating `list` and `delete` to the engine's snapshot directory.
    */
  def apply[F[_]: Async](
      engine: MmapWalEngine[F]
  ): StorageBackedSnapshotStore[F] =
    new StorageBackedSnapshotStore[F](
      engine,
      Some(() => engine.listSnapshots),
      Some(idx => engine.deleteSnapshot(idx))
    )

  /** Create a [[SnapshotStore]] backed by any generic [[StorageEngine]]. */
  def fromEngine[F[_]: Async](
      engine: StorageEngine[F]
  ): StorageBackedSnapshotStore[F] =
    new StorageBackedSnapshotStore[F](engine, None, None)
