package raft.spi

import raft.state.Log

/**
 * Service Provider Interface for the replicated state machine.
 * 
 * User implements this to define what happens when commands are applied.
 * 
 * @tparam F effect type (e.g., IO)
 * @tparam R result type returned when commands are applied
 */
trait StateMachine[F[_], R]:
  /**
   * Apply a committed log entry to the state machine.
   * Returns the result of applying the command.
   */
  def apply(log: Log): F[R]
  
  /**
   * Take a snapshot of the current state machine.
   * Returns the snapshot data.
   */
  def snapshot: F[Array[Byte]]
  
  /**
   * Restore state machine from a snapshot.
   */
  def restore(data: Array[Byte]): F[Unit]
