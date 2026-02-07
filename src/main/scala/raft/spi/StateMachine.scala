package raft.spi

import raft.state.Log

/** Service Provider Interface for the replicated state machine.
  *
  * Users implement this trait to define what happens when committed log entries
  * are applied. The state machine is the ''consumer'' of the RAFT replicated
  * log: once an entry is committed (replicated to a majority), it is applied to
  * this state machine in log order.
  *
  * Implementations must be deterministic — given the same sequence of log
  * entries, every node must produce the same state.
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @tparam R
  *   the result type returned when a command is applied
  * @see
  *   [[raft.effect.Effect.ApplyToStateMachine]] for the effect that triggers
  *   application
  * @see
  *   [[raft.state.Log]] for the entry structure passed to `apply`
  */
trait StateMachine[F[_], R]:
  /** Apply a committed log entry to the state machine.
    *
    * Called exactly once per committed entry, in log index order. The
    * implementation should decode the entry's `data` field and execute the
    * corresponding operation.
    *
    * @param log
    *   the committed log entry to apply
    * @return
    *   the result of applying the command (e.g., a read result or
    *   acknowledgment)
    */
  def apply(log: Log): F[R]

  /** Capture a snapshot of the current state machine for log compaction.
    *
    * The returned byte array should be a complete, self-contained serialization
    * of the state machine that can be restored via [[restore]].
    *
    * @return
    *   the serialized state machine data
    * @see
    *   [[restore]] for the inverse operation
    */
  def snapshot: F[Array[Byte]]

  /** Restore the state machine from a previously captured snapshot.
    *
    * After restoration, the state machine should be in the exact same state as
    * it was when [[snapshot]] was called.
    *
    * @param data
    *   the serialized state machine data from a snapshot
    * @see
    *   [[snapshot]] for the inverse operation
    */
  def restore(data: Array[Byte]): F[Unit]
