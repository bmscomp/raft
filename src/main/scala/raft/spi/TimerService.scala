package raft.spi

import cats.effect.kernel.Fiber
import scala.concurrent.duration.FiniteDuration

/** Timer service for scheduling RAFT election and heartbeat timeouts.
  *
  * Provides cancellable timer scheduling and randomized timeout generation.
  * Election timeouts use jitter to prevent multiple nodes from starting
  * elections simultaneously (split votes).
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @see
  *   [[raft.effect.Effect.ResetElectionTimer]] for the effect that triggers
  *   timer resets
  * @see
  *   [[raft.impl.DefaultTimerService]] for the built-in Cats Effect
  *   implementation
  */
trait TimerService[F[_]]:
  /** Schedule an election timeout that fires after the given delay.
    *
    * The returned `Fiber` can be cancelled to prevent the timeout from firing
    * (e.g., when a heartbeat is received from the leader).
    *
    * @param delay
    *   the duration to wait before firing
    * @param onTimeout
    *   the effect to execute when the timer fires
    * @return
    *   a cancellable fiber representing the scheduled timeout
    */
  def scheduleElection(
      delay: FiniteDuration,
      onTimeout: F[Unit]
  ): F[Fiber[F, Throwable, Unit]]

  /** Schedule a heartbeat timer that fires after the given delay.
    *
    * Used by the leader to periodically send heartbeats to followers.
    *
    * @param delay
    *   the duration to wait before firing
    * @param onTimeout
    *   the effect to execute when the timer fires
    * @return
    *   a cancellable fiber representing the scheduled timeout
    */
  def scheduleHeartbeat(
      delay: FiniteDuration,
      onTimeout: F[Unit]
  ): F[Fiber[F, Throwable, Unit]]

  /** Generate a randomized election timeout with jitter.
    *
    * Adds random jitter (typically 0–50% of the base) to prevent coordinated
    * elections across nodes. This is critical for avoiding split-vote
    * scenarios.
    *
    * @param base
    *   the base timeout duration before jitter is applied
    * @return
    *   a randomized duration >= `base`
    */
  def randomElectionTimeout(base: FiniteDuration): F[FiniteDuration]

/** Factory for creating [[TimerService]] instances.
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @see
  *   [[raft.impl.DefaultTimerServiceFactory]] for the built-in implementation
  */
trait TimerServiceFactory[F[_]]:
  /** Create a new timer service instance.
    *
    * @return
    *   a ready-to-use timer service
    */
  def create: F[TimerService[F]]
