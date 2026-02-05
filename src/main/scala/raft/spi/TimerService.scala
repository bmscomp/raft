package raft.spi

import cats.effect.kernel.Fiber
import scala.concurrent.duration.FiniteDuration

/**
 * Timer service for RAFT timeouts.
 * 
 * Provides election and heartbeat timer scheduling with proper cancellation.
 */
trait TimerService[F[_]]:
  /**
   * Schedule an election timeout. Returns a fiber that can be cancelled.
   * The provided callback is invoked when the timer fires.
   */
  def scheduleElection(delay: FiniteDuration, onTimeout: F[Unit]): F[Fiber[F, Throwable, Unit]]
  
  /**
   * Schedule a heartbeat timer.
   */
  def scheduleHeartbeat(delay: FiniteDuration, onTimeout: F[Unit]): F[Fiber[F, Throwable, Unit]]
  
  /**
   * Get a randomized election timeout with jitter.
   * Typically base timeout + random(0, base/2)
   */
  def randomElectionTimeout(base: FiniteDuration): F[FiniteDuration]

/**
 * Factory for creating TimerService instances.
 */
trait TimerServiceFactory[F[_]]:
  def create: F[TimerService[F]]
