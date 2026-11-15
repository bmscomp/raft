package raft.impl

import cats.Functor
import cats.effect.kernel.{Async, Ref}
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import raft.state.{
  ClusterConfig,
  HardState,
  Log,
  LogIndex,
  NodeId,
  Snapshot,
  Term
}
import raft.spi.{
  LogStore,
  SnapshotMetadata as StoredSnapshotMetadata,
  SnapshotStore,
  StableStore
}

/** In-memory [[LogStore]] implementation for testing and prototyping.
  *
  * Production Raft deployments require durable log storage so that committed
  * entries survive crashes (§5.2). This implementation intentionally sacrifices
  * that durability guarantee by storing entries in a `Ref[F, Vector[Log]]`,
  * making it ideal for:
  *   - '''Unit tests''' — fast, in-process, repeatable tests against the
  *     [[raft.logic.RaftLogic]] layer without disk I/O
  *   - '''Prototyping''' — quickly standing up a multi-node cluster to explore
  *     API design and message flows
  *   - '''Property-based testing''' — generating large numbers of random
  *     scenarios without worrying about cleanup
  *
  * @tparam F
  *   the effect type (requires `Async`)
  * @see
  *   [[raft.spi.LogStore]] for the SPI contract
  * @see
  *   [[raft.spi.LogStoreFactory.inMemory]] for creating instances via the
  *   factory pattern
  */
class InMemLogStore[F[_]: Async] private (
    logs: Ref[F, Vector[Log]]
) extends LogStore[F]:

  def get(index: LogIndex): F[Option[Log]] =
    logs.get.map(_.find(_.index == index.value))

  def getRange(start: LogIndex, end: LogIndex): F[Vector[Log]] =
    logs.get.map(_.filter(l => l.index >= start.value && l.index <= end.value))

  def lastIndex: F[LogIndex] =
    logs.get.map(v =>
      LogIndex.unsafeFrom(v.lastOption.map(_.index).getOrElse(0L))
    )

  def termAt(index: LogIndex): F[Option[Term]] =
    logs.get.map(
      _.find(_.index == index.value).map(l => Term.unsafeFrom(l.term))
    )

  def isEmpty: F[Boolean] =
    logs.get.map(_.isEmpty)

  def append(entries: Seq[Log]): F[Unit] =
    logs.update(_ ++ entries)

  def truncateFrom(index: LogIndex): F[Unit] =
    logs.update(_.filter(_.index < index.value))

  def discardBefore(index: LogIndex): F[Unit] =
    logs.update(_.filter(_.index >= index.value))

  def clear: F[Unit] =
    logs.set(Vector.empty)

/** Companion for [[InMemLogStore]] providing a factory method. */
object InMemLogStore:
  /** Create a new empty [[InMemLogStore]].
    *
    * @tparam F
    *   the effect type (requires `Async`)
    * @return
    *   a new in-memory log store, wrapped in `F`
    */
  def apply[F[_]: Async](): F[InMemLogStore[F]] =
    Ref.of[F, Vector[Log]](Vector.empty).map(new InMemLogStore[F](_))

/** In-memory [[StableStore]] implementation for testing and prototyping.
  *
  * Stores the current term and voted-for candidate in `Ref`s with no
  * persistence. Data is lost on restart.
  *
  * @tparam F
  *   the effect type (requires `Async`)
  * @see
  *   [[raft.spi.StableStore]] for the SPI contract
  * @see
  *   [[raft.spi.StableStoreFactory.inMemory]] for creating instances via the
  *   factory pattern
  */
class InMemStableStore[F[_]: Functor] private (
    stateRef: Ref[F, HardState]
) extends StableStore[F]:

  def currentTerm: F[Term] = stateRef.get.map(_.currentTerm)
  def setCurrentTerm(term: Term): F[Unit] =
    stateRef.update(_.copy(term = term.value))
  def votedFor: F[Option[NodeId]] = stateRef.get.map(_.votedFor)
  def setVotedFor(nodeId: Option[NodeId]): F[Unit] =
    stateRef.update(_.copy(votedFor = nodeId))
  def hardState: F[HardState] = stateRef.get
  def setHardState(term: Term, votedFor: Option[NodeId]): F[Unit] =
    stateRef.set(HardState(term.value, votedFor))

/** Companion for [[InMemStableStore]] providing a factory method. */
object InMemStableStore:
  /** Create a new [[InMemStableStore]] initialized to term 0 with no vote.
    *
    * @tparam F
    *   the effect type (requires `Async`)
    * @return
    *   a new in-memory stable store, wrapped in `F`
    */
  def apply[F[_]: Async](): F[InMemStableStore[F]] =
    Ref.of[F, HardState](HardState.Empty).map(new InMemStableStore[F](_))

/** Replayable in-memory [[SnapshotStore]] implementation for testing and
  * prototyping.
  *
  * Materializes snapshot streams into immutable byte arrays in a single `Ref`
  * so [[loadLatest]] can be re-read multiple times safely (e.g., when streaming
  * to several lagging followers).
  */
class InMemSnapshotStore[F[_]: Async] private (
    snapshotsRef: Ref[
      F,
      Map[Long, (Long, Option[ClusterConfig], Array[Byte])]
    ]
) extends SnapshotStore[F]:

  def save(snapshot: Snapshot[F]): F[Unit] =
    snapshot.data.compile.to(Array).flatMap { bytes =>
      snapshotsRef.update(
        _.updated(
          snapshot.lastIncludedIndex,
          (snapshot.lastIncludedTerm, snapshot.config, bytes)
        )
      )
    }

  def loadLatest: F[Option[Snapshot[F]]] =
    snapshotsRef.get.map { m =>
      m.maxByOption(_._1).map { case (idx, (term, cfg, bytes)) =>
        Snapshot[F](
          lastIncludedIndex = idx,
          lastIncludedTerm = term,
          data = Stream.chunk(Chunk.array(bytes)).covary[F],
          config = cfg
        )
      }
    }

  def list: F[Vector[StoredSnapshotMetadata]] =
    snapshotsRef.get.map { m =>
      m.toVector
        .sortBy(_._1)
        .map { case (idx, (term, _, bytes)) =>
          StoredSnapshotMetadata(idx, term, bytes.length.toLong)
        }
    }

  def delete(index: Long): F[Unit] =
    snapshotsRef.update(_ - index)

object InMemSnapshotStore:
  def apply[F[_]: Async](): F[InMemSnapshotStore[F]] =
    Ref
      .of[F, Map[Long, (Long, Option[ClusterConfig], Array[Byte])]](Map.empty)
      .map(new InMemSnapshotStore[F](_))
