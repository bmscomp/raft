package raft.impl

import cats.effect.kernel.{Async, Fiber}
import cats.effect.std.Random
import cats.syntax.all.*
import scala.concurrent.duration.*

import raft.spi.{TimerService, TimerServiceFactory}

/** Default [[TimerService]] implementation backed by Cats Effect.
  *
  * This implementation uses Cats Effect `Fiber`s for cancellable timer
  * scheduling. When a heartbeat arrives from the leader, the runtime cancels
  * the outstanding election timer Fiber and starts a new one, effectively
  * resetting the timeout. This is more efficient than `sleep` + `cancel` loops
  * because Fiber cancellation is cooperative and non-blocking.
  *
  * Randomized election timeouts use 0–50% jitter (as recommended in §9.3 of the
  * Raft thesis) to prevent split votes. The jitter is generated using a Cats
  * Effect `Random` instance for reproducibility in tests.
  *
  * @tparam F
  *   the effect type (requires `Async`)
  * @param random
  *   the random number generator used for jitter
  * @see
  *   [[raft.spi.TimerService]] for the SPI contract
  * @see
  *   [[DefaultTimerServiceFactory]] for the companion factory
  */
class DefaultTimerService[F[_]: Async](random: Random[F])
    extends TimerService[F]:

  def scheduleElection(
      delay: FiniteDuration,
      onTimeout: F[Unit]
  ): F[Fiber[F, Throwable, Unit]] =
    Async[F].start(
      Async[F].sleep(delay) >> onTimeout
    )

  def scheduleHeartbeat(
      delay: FiniteDuration,
      onTimeout: F[Unit]
  ): F[Fiber[F, Throwable, Unit]] =
    Async[F].start(
      Async[F].sleep(delay) >> onTimeout
    )

  def randomElectionTimeout(base: FiniteDuration): F[FiniteDuration] =
    // Add 0-50% jitter to base timeout
    val jitterMax = base.toMillis / 2
    random.nextLongBounded(jitterMax.max(1L)).map { jitter =>
      (base.toMillis + jitter).millis
    }

/** Companion for [[DefaultTimerService]] providing a factory method.
  *
  * @see
  *   [[DefaultTimerServiceFactory]] for the [[TimerServiceFactory]]
  *   implementation
  */
object DefaultTimerService:
  /** Create a new [[DefaultTimerService]] with a Scala-based PRNG.
    *
    * @tparam F
    *   the effect type (requires `Async`)
    * @return
    *   a new timer service instance, wrapped in `F`
    */
  def apply[F[_]: Async]: F[DefaultTimerService[F]] =
    Random.scalaUtilRandom[F].map(new DefaultTimerService[F](_))

/** Factory for creating [[DefaultTimerService]] instances.
  *
  * Implements [[TimerServiceFactory]] so it can be injected as a dependency.
  *
  * @see
  *   [[raft.spi.TimerServiceFactory]] for the SPI contract
  */
object DefaultTimerServiceFactory:
  /** Create a [[TimerServiceFactory]] that produces [[DefaultTimerService]]
    * instances.
    *
    * @tparam F
    *   the effect type (requires `Async`)
    * @return
    *   a factory that creates default timer services
    */
  def apply[F[_]: Async]: TimerServiceFactory[F] = new TimerServiceFactory[F]:
    def create: F[TimerService[F]] =
      DefaultTimerService[F].widen[TimerService[F]]
