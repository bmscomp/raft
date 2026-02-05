package raft.impl

import cats.effect.kernel.{Async, Fiber}
import cats.effect.std.Random
import cats.syntax.all.*
import scala.concurrent.duration.*

import raft.spi.{TimerService, TimerServiceFactory}

/**
 * Default timer service implementation using Cats Effect.
 * 
 * Features:
 * - Election timeout with configurable jitter (50% variation)
 * - Cancellable timers via Fiber
 * - Thread-safe operations
 */
class DefaultTimerService[F[_]: Async](random: Random[F]) extends TimerService[F]:
  
  def scheduleElection(delay: FiniteDuration, onTimeout: F[Unit]): F[Fiber[F, Throwable, Unit]] =
    Async[F].start(
      Async[F].sleep(delay) >> onTimeout
    )
  
  def scheduleHeartbeat(delay: FiniteDuration, onTimeout: F[Unit]): F[Fiber[F, Throwable, Unit]] =
    Async[F].start(
      Async[F].sleep(delay) >> onTimeout
    )
  
  def randomElectionTimeout(base: FiniteDuration): F[FiniteDuration] =
    // Add 0-50% jitter to base timeout
    val jitterMax = base.toMillis / 2
    random.nextLongBounded(jitterMax.max(1L)).map { jitter =>
      (base.toMillis + jitter).millis
    }

object DefaultTimerService:
  def apply[F[_]: Async]: F[DefaultTimerService[F]] =
    Random.scalaUtilRandom[F].map(new DefaultTimerService[F](_))

/**
 * Factory for DefaultTimerService.
 */
object DefaultTimerServiceFactory:
  def apply[F[_]: Async]: TimerServiceFactory[F] = new TimerServiceFactory[F]:
    def create: F[TimerService[F]] = DefaultTimerService[F].widen[TimerService[F]]
