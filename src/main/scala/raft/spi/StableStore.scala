package raft.spi

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import raft.state.{NodeId, Term}

/**
 * Stable storage for RAFT hard state.
 * 
 * Must be durable - survives restarts.
 */
trait StableStore[F[_]]:
  /** Get current term */
  def currentTerm: F[Term]
  
  /** Set current term */
  def setCurrentTerm(term: Term): F[Unit]
  
  /** Get voted-for candidate in current term */
  def votedFor: F[Option[NodeId]]
  
  /** Set voted-for candidate */
  def setVotedFor(nodeId: Option[NodeId]): F[Unit]

/**
 * Factory for creating StableStore instances.
 */
trait StableStoreFactory[F[_]]:
  def create(path: String): Resource[F, StableStore[F]]

object StableStoreFactory:
  def inMemory[F[_]: Async]: StableStoreFactory[F] = new StableStoreFactory[F]:
    def create(path: String): Resource[F, StableStore[F]] =
      Resource.eval(
        raft.impl.InMemStableStore[F]().map(s => s: StableStore[F])
      )
