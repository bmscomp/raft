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
class StorageBackedStableStore[F[_]: Monad](engine: StorageEngine[F])
    extends StableStore[F]:

  def currentTerm: F[Term] =
    engine.readHardState.map(_.currentTerm)

  def setCurrentTerm(term: Term): F[Unit] =
    engine.readHardState.flatMap(s =>
      engine.writeHardState(s.copy(currentTerm = term))
    )

  def votedFor: F[Option[NodeId]] =
    engine.readHardState.map(_.votedFor)

  def setVotedFor(nodeId: Option[NodeId]): F[Unit] =
    engine.readHardState.flatMap(s =>
      engine.writeHardState(s.copy(votedFor = nodeId))
    )
