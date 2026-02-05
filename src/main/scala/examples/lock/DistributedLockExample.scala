package examples.lock

import cats.effect.*
import cats.effect.std.Queue
import cats.syntax.all.*
import raft.state.*
import raft.spi.StateMachine

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/**
 * Example 2: Distributed Lock Service
 * 
 * A distributed lock service replicated via RAFT.
 * Similar to ZooKeeper's lock recipes or etcd's distributed locks.
 */

// === Lock Commands ===

enum LockCommand:
  case Acquire(lockId: String, clientId: String, ttl: Duration)
  case Release(lockId: String, clientId: String)
  case Heartbeat(lockId: String, clientId: String)

object LockCommand:
  def encode(cmd: LockCommand): Array[Byte] =
    val str = cmd match
      case LockCommand.Acquire(lock, client, ttl) =>
        s"ACQUIRE:$lock:$client:${ttl.toMillis}"
      case LockCommand.Release(lock, client) =>
        s"RELEASE:$lock:$client"
      case LockCommand.Heartbeat(lock, client) =>
        s"HEARTBEAT:$lock:$client"
    str.getBytes(StandardCharsets.UTF_8)
  
  def decode(data: Array[Byte]): Either[String, LockCommand] =
    val str = new String(data, StandardCharsets.UTF_8)
    str.split(":").toList match
      case "ACQUIRE" :: lock :: client :: ttl :: Nil =>
        Right(LockCommand.Acquire(lock, client, ttl.toLong.millis))
      case "RELEASE" :: lock :: client :: Nil =>
        Right(LockCommand.Release(lock, client))
      case "HEARTBEAT" :: lock :: client :: Nil =>
        Right(LockCommand.Heartbeat(lock, client))
      case _ =>
        Left(s"Invalid lock command: $str")

// === Lock State ===

final case class LockInfo(
  owner: String,
  expiresAt: Long
)

enum LockResult:
  case Acquired
  case Released
  case Renewed
  case Denied(reason: String)
  case NotFound
  case Error(msg: String)

// === Lock State Machine ===

class LockStateMachine[F[_]: Sync: Clock](
  locks: Ref[F, Map[String, LockInfo]]
) extends StateMachine[F, LockResult]:
  
  def apply(log: Log): F[LockResult] =
    LockCommand.decode(log.data) match
      case Right(LockCommand.Acquire(lockId, clientId, ttl)) =>
        acquireLock(lockId, clientId, ttl)
      
      case Right(LockCommand.Release(lockId, clientId)) =>
        releaseLock(lockId, clientId)
      
      case Right(LockCommand.Heartbeat(lockId, clientId)) =>
        renewLock(lockId, clientId)
      
      case Left(err) =>
        Sync[F].pure(LockResult.Error(err))
  
  private def acquireLock(lockId: String, clientId: String, ttl: Duration): F[LockResult] =
    for
      now <- Clock[F].realTime
      result <- locks.modify { current =>
        current.get(lockId) match
          // Lock doesn't exist or expired - grant it
          case None =>
            val info = LockInfo(clientId, now.toMillis + ttl.toMillis)
            (current.updated(lockId, info), LockResult.Acquired)
          
          case Some(lock) if lock.expiresAt < now.toMillis =>
            val info = LockInfo(clientId, now.toMillis + ttl.toMillis)
            (current.updated(lockId, info), LockResult.Acquired)
          
          // Lock held by same client - renew
          case Some(lock) if lock.owner == clientId =>
            val info = LockInfo(clientId, now.toMillis + ttl.toMillis)
            (current.updated(lockId, info), LockResult.Renewed)
          
          // Lock held by different client
          case Some(lock) =>
            (current, LockResult.Denied(s"Lock held by ${lock.owner}"))
      }
    yield result
  
  private def releaseLock(lockId: String, clientId: String): F[LockResult] =
    locks.modify { current =>
      current.get(lockId) match
        case Some(lock) if lock.owner == clientId =>
          (current - lockId, LockResult.Released)
        case Some(lock) =>
          (current, LockResult.Denied(s"Lock held by ${lock.owner}"))
        case None =>
          (current, LockResult.NotFound)
    }
  
  private def renewLock(lockId: String, clientId: String): F[LockResult] =
    for
      now <- Clock[F].realTime
      result <- locks.modify { current =>
        current.get(lockId) match
          case Some(lock) if lock.owner == clientId =>
            val renewed = lock.copy(expiresAt = now.toMillis + 30000)
            (current.updated(lockId, renewed), LockResult.Renewed)
          case Some(lock) =>
            (current, LockResult.Denied(s"Lock held by ${lock.owner}"))
          case None =>
            (current, LockResult.NotFound)
      }
    yield result
  
  def snapshot: F[Array[Byte]] =
    locks.get.map { m =>
      m.map { case (k, v) => s"$k:${v.owner}:${v.expiresAt}" }.mkString("\n").getBytes
    }
  
  def restore(data: Array[Byte]): F[Unit] =
    val entries = new String(data).split("\n").flatMap { line =>
      line.split(":") match
        case Array(lockId, owner, expiresAt) =>
          Some(lockId -> LockInfo(owner, expiresAt.toLong))
        case _ => None
    }.toMap
    locks.set(entries)
  
  // For queries
  def isLocked(lockId: String): F[Boolean] =
    for
      now <- Clock[F].realTime
      result <- locks.get.map { m =>
        m.get(lockId).exists(_.expiresAt > now.toMillis)
      }
    yield result
  
  def getOwner(lockId: String): F[Option[String]] =
    for
      now <- Clock[F].realTime
      result <- locks.get.map { m =>
        m.get(lockId).filter(_.expiresAt > now.toMillis).map(_.owner)
      }
    yield result

object LockStateMachine:
  def apply[F[_]: Sync: Clock]: F[LockStateMachine[F]] =
    Ref.of[F, Map[String, LockInfo]](Map.empty).map(new LockStateMachine[F](_))

// === Example Usage with RAFT Library Integration ===

import raft.state.NodeState.*
import raft.message.RaftMessage.*
import raft.logic.*
import raft.effect.Effect.*

object DistributedLockExample extends IOApp.Simple:
  
  def run: IO[Unit] =
    for
      _ <- IO.println("═" * 60)
      _ <- IO.println("  Distributed Lock Service with RAFT Library")
      _ <- IO.println("═" * 60)
      _ <- IO.println("\nThis example uses RaftLogic for consensus.\n")
      
      lockService <- LockStateMachine[IO]
      
      // Create RAFT node state
      nodeId = NodeId("lock-node-1")
      config = RaftConfig(nodeId, 150.millis, 300.millis, 50.millis)
      stateRef <- Ref.of[IO, NodeState](Follower(0, None, None))
      logRef <- Ref.of[IO, Vector[Log]](Vector.empty)
      logIndexRef <- Ref.of[IO, Long](0L)
      
      // Step 1: Leader election via RaftLogic
      _ <- IO.println("Step 1: Leader Election via RaftLogic")
      _ <- IO.println("─" * 40)
      
      state1 <- stateRef.get
      trans1 = RaftLogic.onMessage(state1, ElectionTimeout, config, 0, 0, 3, _ => None)
      _ <- stateRef.set(trans1.state)
      _ <- IO.println(s"   After ElectionTimeout: ${trans1.state}")
      
      // Simulate votes and become leader
      voteResp = RequestVoteResponse(term = 1, voteGranted = true)
      state2 <- stateRef.get
      trans2 = RaftLogic.onMessage(state2, voteResp, config, 0, 0, 3, _ => None)
      trans3 = RaftLogic.onMessage(trans2.state, voteResp, config, 0, 0, 3, _ => None)
      _ <- stateRef.set(trans3.state)
      _ <- IO.println(s"   After votes: ${trans3.state}")
      
      // Step 2: Client-1 acquires lock through RAFT
      _ <- IO.println("\nStep 2: Client-1 Acquiring Lock via RAFT")
      _ <- IO.println("─" * 40)
      
      cmd1 = LockCommand.Acquire("resource-a", "client-1", 30.seconds)
      idx1 <- logIndexRef.updateAndGet(_ + 1)
      entry1 = Log.command(idx1, 1, LockCommand.encode(cmd1))
      _ <- logRef.update(_ :+ entry1)
      _ <- IO.println(s"   Appended to RAFT log: index=$idx1")
      
      r1 <- lockService.apply(entry1)
      _ <- IO.println(s"   Applied to state machine: $r1")
      
      owner1 <- lockService.getOwner("resource-a")
      _ <- IO.println(s"   Lock owner: ${owner1.getOrElse("none")}")
      
      // Step 3: Client-2 tries to acquire same lock
      _ <- IO.println("\nStep 3: Client-2 Trying to Acquire (Conflict)")
      _ <- IO.println("─" * 40)
      
      cmd2 = LockCommand.Acquire("resource-a", "client-2", 30.seconds)
      idx2 <- logIndexRef.updateAndGet(_ + 1)
      entry2 = Log.command(idx2, 1, LockCommand.encode(cmd2))
      _ <- logRef.update(_ :+ entry2)
      _ <- IO.println(s"   Appended to RAFT log: index=$idx2")
      
      r2 <- lockService.apply(entry2)
      _ <- IO.println(s"   Result: $r2")
      
      // Step 4: Client-1 releases lock
      _ <- IO.println("\nStep 4: Client-1 Releasing Lock")
      _ <- IO.println("─" * 40)
      
      cmd3 = LockCommand.Release("resource-a", "client-1")
      idx3 <- logIndexRef.updateAndGet(_ + 1)
      entry3 = Log.command(idx3, 1, LockCommand.encode(cmd3))
      _ <- logRef.update(_ :+ entry3)
      
      r3 <- lockService.apply(entry3)
      _ <- IO.println(s"   Result: $r3")
      
      // Step 5: Client-2 retries and succeeds
      _ <- IO.println("\nStep 5: Client-2 Retrying (Success)")
      _ <- IO.println("─" * 40)
      
      cmd4 = LockCommand.Acquire("resource-a", "client-2", 30.seconds)
      idx4 <- logIndexRef.updateAndGet(_ + 1)
      entry4 = Log.command(idx4, 1, LockCommand.encode(cmd4))
      _ <- logRef.update(_ :+ entry4)
      
      r4 <- lockService.apply(entry4)
      _ <- IO.println(s"   Result: $r4")
      
      owner2 <- lockService.getOwner("resource-a")
      _ <- IO.println(s"   Lock owner: ${owner2.getOrElse("none")}")
      
      // Summary
      log <- logRef.get
      _ <- IO.println("\n" + "═" * 60)
      _ <- IO.println(s"  RAFT Log: ${log.size} entries")
      _ <- IO.println("  RAFT Library Components Used:")
      _ <- IO.println("  • RaftLogic.onMessage - consensus transitions")
      _ <- IO.println("  • NodeState (Follower/Candidate/Leader)")
      _ <- IO.println("  • RaftMessage (ElectionTimeout, RequestVoteResponse)")
      _ <- IO.println("  • Log - replicated lock commands")
      _ <- IO.println("  • StateMachine[F, LockResult] - lock state")
      _ <- IO.println("═" * 60)
    yield ()

