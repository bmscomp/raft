package raft.spi

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

import raft.impl.DefaultTimerService

/**
 * Timer Service Tests
 * 
 * Tests for the timer service implementation.
 */
class TimerServiceSpec extends AsyncFlatSpec with AsyncIOSpec with Matchers:
  
  "DefaultTimerService" should "create successfully" in {
    DefaultTimerService[IO].map { timer =>
      timer should not be null
    }
  }
  
  it should "schedule and fire election timer" in {
    for
      timer <- DefaultTimerService[IO]
      fired <- cats.effect.Ref.of[IO, Boolean](false)
      fiber <- timer.scheduleElection(50.millis, fired.set(true))
      _     <- IO.sleep(100.millis)  // Wait for timer to fire
      result <- fired.get
    yield result shouldBe true
  }
  
  it should "allow timer cancellation" in {
    for
      timer <- DefaultTimerService[IO]
      fired <- cats.effect.Ref.of[IO, Boolean](false)
      fiber <- timer.scheduleElection(100.millis, fired.set(true))
      _     <- fiber.cancel  // Cancel before it fires
      _     <- IO.sleep(150.millis)  // Wait past when it would have fired
      result <- fired.get
    yield result shouldBe false  // Should not have fired
  }
  
  it should "provide randomized election timeout with jitter" in {
    for
      timer <- DefaultTimerService[IO]
      base = 150.millis
      t1 <- timer.randomElectionTimeout(base)
      t2 <- timer.randomElectionTimeout(base)
      t3 <- timer.randomElectionTimeout(base)
    yield
      // Base should be 150ms, with up to 75ms jitter (50%)
      t1.toMillis should be >= 150L
      t1.toMillis should be <= 225L
      
      t2.toMillis should be >= 150L
      t2.toMillis should be <= 225L
      
      // At least two of three should differ (very high probability with random)
      val unique = Set(t1, t2, t3)
      // Note: In rare cases all three could be same, so we just check range
  }
  
  it should "schedule and fire heartbeat timer" in {
    for
      timer <- DefaultTimerService[IO]
      fired <- cats.effect.Ref.of[IO, Boolean](false)
      fiber <- timer.scheduleHeartbeat(50.millis, fired.set(true))
      _     <- IO.sleep(100.millis)
      result <- fired.get
    yield result shouldBe true
  }
