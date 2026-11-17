package raft.spi

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import raft.state.Snapshot

/** Service Provider Interface for snapshot persistence.
  *
  * Decouples snapshot storage from the [[StorageEngine]] so that snapshots can
  * be stored on different backends (local disk, NFS, S3, etc.) independently of
  * the WAL-based log storage.
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @see
  *   [[raft.state.Snapshot]] for the snapshot data type
  */
trait SnapshotStore[F[_]]:

  /** Persist a snapshot to durable storage.
    *
    * Must be atomic — a partially written snapshot must not be visible to
    * [[loadLatest]].
    *
    * @param snapshot
    *   the snapshot to persist
    */
  def save(snapshot: Snapshot[F]): F[Unit]

  /** Load the most recent snapshot, if any.
    *
    * @return
    *   `Some(snapshot)` if at least one snapshot exists, `None` otherwise
    */
  def loadLatest: F[Option[Snapshot[F]]]

  /** List metadata for all available snapshots, ordered by index ascending.
    *
    * @return
    *   snapshot metadata entries
    */
  def list: F[Vector[SnapshotMetadata]]

  /** Delete the snapshot at a specific index.
    *
    * Used for garbage-collecting old snapshots after newer ones are confirmed.
    *
    * @param index
    *   the `lastIncludedIndex` of the snapshot to delete
    */
  def delete(index: Long): F[Unit]

/** Metadata for a persisted snapshot (without loading the full data). */
final case class SnapshotMetadata(
    lastIncludedIndex: Long,
    lastIncludedTerm: Long,
    sizeBytes: Long
)

/** Factory for creating [[SnapshotStore]] instances with resource lifecycle. */
trait SnapshotStoreFactory[F[_]]:
  def create(path: String): Resource[F, SnapshotStore[F]]

object SnapshotStoreFactory:
  /** Create an in-memory [[SnapshotStoreFactory]] for testing and prototyping.
    */
  def inMemory[F[_]: Async]: SnapshotStoreFactory[F] =
    new SnapshotStoreFactory[F]:
      def create(path: String): Resource[F, SnapshotStore[F]] =
        Resource.eval(
          raft.impl.InMemSnapshotStore[F]().map(s => s: SnapshotStore[F])
        )
