package raft.spi

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import raft.state.{NodeId, Term}

/** Stable storage for RAFT hard state (current term and voted-for candidate).
  *
  * The Raft paper (§5.2, Figure 2) identifies two values that must be persisted
  * to stable storage before a node responds to any RPC:
  *   - '''currentTerm''' — the latest term this node has seen
  *   - '''votedFor''' — the candidate this node voted for in the current term
  *
  * These two values are the minimum '''hard state''' required for safety. If a
  * node forgets its `currentTerm`, it might regress to a stale term and violate
  * the Election Safety property. If it forgets `votedFor`, it might grant a
  * second vote in the same term, allowing two leaders to be elected.
  *
  * The "persist before responding" invariant is critical: the node must `fsync`
  * the hard state to disk ''before'' sending any RPC response, ensuring that
  * the state survives an immediate crash.
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @see
  *   [[raft.effect.Effect.PersistHardState]] for the effect that triggers
  *   writes
  * @see
  *   [[raft.impl.InMemStableStore]] for an in-memory test implementation
  */
trait StableStore[F[_]]:
  /** Retrieve the persisted current term.
    *
    * @return
    *   the current term (defaults to [[Term.Zero]] if never set)
    */
  def currentTerm: F[Term]

  /** Persist the current term to durable storage.
    *
    * Must be flushed before any RPC response is sent.
    *
    * @param term
    *   the term to persist
    */
  def setCurrentTerm(term: Term): F[Unit]

  /** Retrieve the candidate voted for in the current term.
    *
    * @return
    *   `Some(candidateId)` if a vote was cast, `None` otherwise
    */
  def votedFor: F[Option[NodeId]]

  /** Persist the voted-for candidate to durable storage.
    *
    * @param nodeId
    *   `Some(candidateId)` when casting a vote, `None` to clear
    */
  def setVotedFor(nodeId: Option[NodeId]): F[Unit]

/** Factory for creating [[StableStore]] instances with proper resource
  * lifecycle.
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  */
trait StableStoreFactory[F[_]]:
  /** Create a new stable store as a managed resource.
    *
    * @param path
    *   the storage path for the hard state file
    * @return
    *   a managed resource wrapping the created stable store
    */
  def create(path: String): Resource[F, StableStore[F]]

/** Companion for [[StableStoreFactory]] providing built-in factory
  * implementations.
  */
object StableStoreFactory:
  /** Create an in-memory [[StableStoreFactory]] (useful for testing).
    *
    * @tparam F
    *   the effect type (requires `Async`)
    * @return
    *   a factory that produces [[raft.impl.InMemStableStore]] instances
    */
  def inMemory[F[_]: Async]: StableStoreFactory[F] = new StableStoreFactory[F]:
    def create(path: String): Resource[F, StableStore[F]] =
      Resource.eval(
        raft.impl.InMemStableStore[F]().map(s => s: StableStore[F])
      )
