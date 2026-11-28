package raft.storage

import cats.Monad
import cats.syntax.all.*
import raft.state.{NodeId, Term}
import raft.spi.StableStore

/** Adapter that bridges [[StorageEngine]] to the existing [[StableStore]] SPI.
  *
  * The `StableStore` interface has separate `setCurrentTerm` and `setVotedFor`
  * methods, but the underlying `StorageEngine` persists them atomically as a
  * single [[HardState]]. This adapter reads the current hard state, updates the
  * relevant field, and writes back.
  *
  * @param engine
  *   the underlying storage engine
  * @tparam F
  *   the effect type (requires `Monad`)
  */
/** Optional mixin for [[StorageEngine]] implementations that support atomic
  * locked read-modify-write updates to [[HardState]].
  */
trait AtomicHardStateStore[F[_]]:
  def updateHardState(f: HardState => HardState): F[Unit]

class StorageBackedStableStore[F[_]: Monad](engine: StorageEngine[F])
    extends StableStore[F]:

  private def modifyHardState(f: HardState => HardState): F[Unit] =
    engine match
      case atomic: AtomicHardStateStore[F] @unchecked =>
        atomic.updateHardState(f)
      case _ =>
        engine.readHardState.flatMap(s => engine.writeHardState(f(s)))

  def currentTerm: F[Term] =
    engine.readHardState.map(_.currentTerm)

  def setCurrentTerm(term: Term): F[Unit] =
    modifyHardState(_.copy(term = term.value))

  def votedFor: F[Option[NodeId]] =
    engine.readHardState.map(_.votedFor)

  def setVotedFor(nodeId: Option[NodeId]): F[Unit] =
    modifyHardState(_.copy(votedFor = nodeId))

  def hardState: F[HardState] =
    engine.readHardState

  def setHardState(term: Term, votedFor: Option[NodeId]): F[Unit] =
    modifyHardState(cur => cur.copy(term = term.value, votedFor = votedFor))
