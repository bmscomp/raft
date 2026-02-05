package examples.protocol

import cats.effect.*
import cats.effect.std.Random
import scala.concurrent.duration.*

import raft.impl.DefaultTimerService
import raft.spi.TimerService

/** Example demonstrating the TimerService for RAFT election and heartbeat timing.
  *
  * Shows how to use cancellable timers with randomized jitter to prevent
  * split votes and ensure leader stability.
  */
object TimerServiceExample extends IOApp.Simple:
  
  def run: IO[Unit] =
    IO.println("=== Timer Service Example ===\n") *>
    demonstrateTimers
  
  private def demonstrateTimers: IO[Unit] =
    for
      _ <- IO.println("The TimerService provides cancellable timers with jitter for RAFT.")
      _ <- IO.println("This prevents split votes by randomizing election timeouts.\n")
      
      // Create timer service
      timer <- DefaultTimerService[IO]
      
      // Demonstrate randomized election timeout
      _ <- IO.println("━━━ Randomized Election Timeout ━━━")
      _ <- IO.println("RAFT uses random timeouts (150-300ms typically) to reduce split votes.\n")
      
      base = 150.millis
      t1 <- timer.randomElectionTimeout(base)
      t2 <- timer.randomElectionTimeout(base)
      t3 <- timer.randomElectionTimeout(base)
      t4 <- timer.randomElectionTimeout(base)
      t5 <- timer.randomElectionTimeout(base)
      
      _ <- IO.println(s"  Base timeout: ${base.toMillis}ms")
      _ <- IO.println(s"  Jitter range: 0-${base.toMillis / 2}ms (50% of base)")
      _ <- IO.println(s"\n  Generated timeouts:")
      _ <- IO.println(s"    Timeout 1: ${t1.toMillis}ms")
      _ <- IO.println(s"    Timeout 2: ${t2.toMillis}ms")
      _ <- IO.println(s"    Timeout 3: ${t3.toMillis}ms")
      _ <- IO.println(s"    Timeout 4: ${t4.toMillis}ms")
      _ <- IO.println(s"    Timeout 5: ${t5.toMillis}ms")
      
      // Demonstrate timer scheduling
      _ <- IO.println("\n━━━ Scheduling Election Timer ━━━")
      _ <- IO.println("Timers are cancellable Fibers that fire after the specified delay.\n")
      
      counter <- Ref.of[IO, Int](0)
      _ <- IO.println("  Scheduling election timer for 100ms...")
      
      fiber <- timer.scheduleElection(100.millis, counter.update(_ + 1))
      _ <- IO.println("  Timer scheduled, waiting...")
      _ <- IO.sleep(150.millis)
      
      count1 <- counter.get
      _ <- IO.println(s"  Timer fired! Counter: $count1 ✓")
      
      // Demonstrate timer cancellation
      _ <- IO.println("\n━━━ Timer Cancellation ━━━")
      _ <- IO.println("When a follower receives a valid heartbeat, it cancels and resets its timer.\n")
      
      cancelCounter <- Ref.of[IO, Int](0)
      _ <- IO.println("  Scheduling election timer for 200ms...")
      
      cancelFiber <- timer.scheduleElection(200.millis, cancelCounter.update(_ + 1))
      _ <- IO.sleep(50.millis)
      _ <- IO.println("  Cancelling timer after 50ms (simulating heartbeat receipt)...")
      _ <- cancelFiber.cancel
      
      _ <- IO.sleep(200.millis)  // Wait past when it would have fired
      count2 <- cancelCounter.get
      _ <- IO.println(s"  Counter after cancellation: $count2 (should be 0) ✓")
      
      // Demonstrate heartbeat timer
      _ <- IO.println("\n━━━ Heartbeat Timer (Leader) ━━━")
      _ <- IO.println("Leaders send heartbeats at regular intervals (e.g., 50ms).\n")
      
      heartbeatCounter <- Ref.of[IO, Int](0)
      _ <- IO.println("  Scheduling 3 consecutive heartbeat timers (50ms each)...")
      
      _ <- scheduleHeartbeats(timer, heartbeatCounter, 3)
      
      finalCount <- heartbeatCounter.get
      _ <- IO.println(s"  Heartbeats sent: $finalCount ✓")
      
      // Practical usage pattern
      _ <- IO.println("\n━━━ Practical Usage Pattern ━━━")
      _ <- IO.println("""
  |  // In RaftNode event loop:
  |  def resetElectionTimer: F[Unit] =
  |    for
  |      // Cancel existing timer
  |      _ <- currentTimerRef.get.flatMap(_.cancel)
  |      // Get randomized timeout
  |      timeout <- timerService.randomElectionTimeout(config.electionTimeoutMin)
  |      // Schedule new timer
  |      fiber <- timerService.scheduleElection(timeout, processMessage(ElectionTimeout))
  |      // Store fiber for future cancellation
  |      _ <- currentTimerRef.set(fiber)
  |    yield ()
  """.stripMargin)
      
      _ <- IO.println("✅ Timer service example complete!")
      _ <- IO.println("\nKey takeaways:")
      _ <- IO.println("  • Randomized timeouts prevent split votes in elections")
      _ <- IO.println("  • Timers return Fibers that can be cancelled on heartbeat")
      _ <- IO.println("  • Leaders use heartbeat timers, followers use election timers")
      _ <- IO.println("  • Jitter is typically 50% of the base timeout")
    yield ()
  
  private def scheduleHeartbeats(
    timer: TimerService[IO], 
    counter: Ref[IO, Int], 
    remaining: Int
  ): IO[Unit] =
    if remaining <= 0 then IO.unit
    else
      for
        fiber <- timer.scheduleHeartbeat(50.millis, counter.update(_ + 1))
        _ <- IO.sleep(60.millis)  // Wait for heartbeat to fire
        _ <- scheduleHeartbeats(timer, counter, remaining - 1)
      yield ()
