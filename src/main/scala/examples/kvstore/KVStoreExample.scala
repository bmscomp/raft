package examples.kvstore

import cats.effect.*
import cats.effect.std.{Console, Queue}
import cats.syntax.all.*
import raft.state.*
import raft.spi.StateMachine

import java.nio.charset.StandardCharsets

/**
 * Example 1: Distributed Key-Value Store
 * 
 * A simple in-memory key-value store replicated across RAFT nodes.
 * Demonstrates how to implement a StateMachine for your own data.
 */

// === Commands ===

enum KVCommand:
  case Put(key: String, value: String)
  case Delete(key: String)
  case Get(key: String)  // Read-only, handled separately

object KVCommand:
  private val SEP = "\t"
  
  def encode(cmd: KVCommand): Array[Byte] = cmd match
    case KVCommand.Put(k, v) => s"PUT$SEP$k$SEP$v".getBytes(StandardCharsets.UTF_8)
    case KVCommand.Delete(k) => s"DEL$SEP$k".getBytes(StandardCharsets.UTF_8)
    case KVCommand.Get(k)    => s"GET$SEP$k".getBytes(StandardCharsets.UTF_8)
  
  def decode(data: Array[Byte]): Either[String, KVCommand] =
    val str = new String(data, StandardCharsets.UTF_8)
    str.split(SEP).toList match
      case "PUT" :: key :: value :: Nil => Right(KVCommand.Put(key, value))
      case "DEL" :: key :: Nil          => Right(KVCommand.Delete(key))
      case "GET" :: key :: Nil          => Right(KVCommand.Get(key))
      case _                            => Left(s"Invalid command: $str")

// === Result ===

enum KVResult:
  case Ok
  case Value(v: Option[String])
  case Error(msg: String)

// === State Machine Implementation ===

class KVStateMachine[F[_]: Sync](
  store: Ref[F, Map[String, String]]
) extends StateMachine[F, KVResult]:
  
  def apply(log: Log): F[KVResult] =
    KVCommand.decode(log.data) match
      case Right(KVCommand.Put(key, value)) =>
        store.update(_.updated(key, value)).as(KVResult.Ok)
      
      case Right(KVCommand.Delete(key)) =>
        store.update(_ - key).as(KVResult.Ok)
      
      case Right(KVCommand.Get(key)) =>
        store.get.map(m => KVResult.Value(m.get(key)))
      
      case Left(err) =>
        Sync[F].pure(KVResult.Error(err))
  
  def snapshot: F[Array[Byte]] =
    store.get.map { m =>
      m.map { case (k, v) => s"$k=$v" }.mkString("\n").getBytes
    }
  
  def restore(data: Array[Byte]): F[Unit] =
    val entries = new String(data).split("\n").flatMap { line =>
      line.split("=") match
        case Array(k, v) => Some(k -> v)
        case _           => None
    }.toMap
    store.set(entries)
  
  // Additional method for reads (not in trait)
  def get(key: String): F[Option[String]] =
    store.get.map(_.get(key))

object KVStateMachine:
  def apply[F[_]: Sync]: F[KVStateMachine[F]] =
    Ref.of[F, Map[String, String]](Map.empty).map(new KVStateMachine[F](_))

// === Example Usage with RAFT Library Integration ===

import raft.state.NodeState.*
import raft.message.RaftMessage.*
import raft.logic.*
import raft.effect.Effect.*
import scala.concurrent.duration.*

object KVStoreExample extends IOApp.Simple:
  
  def run: IO[Unit] =
    for
      _ <- IO.println("═" * 60)
      _ <- IO.println("  Distributed Key-Value Store with RAFT Library")
      _ <- IO.println("═" * 60)
      _ <- IO.println("\nThis example uses RaftLogic for consensus.\n")
      
      // Create state machine
      fsm <- KVStateMachine[IO]
      
      // Create RAFT node state
      nodeId = NodeId("kv-node-1")
      config = RaftConfig(nodeId, 150.millis, 300.millis, 50.millis)
      stateRef <- Ref.of[IO, NodeState](Follower(0, None, None))
      logRef <- Ref.of[IO, Vector[Log]](Vector.empty)
      
      // Step 1: Simulate leader election using RaftLogic
      _ <- IO.println("Step 1: Leader Election via RaftLogic")
      _ <- IO.println("─" * 40)
      
      state1 <- stateRef.get
      transition1 = RaftLogic.onMessage(state1, ElectionTimeout, config, 0, 0, 3, _ => None)
      _ <- stateRef.set(transition1.state)
      _ <- IO.println(s"   After ElectionTimeout: ${transition1.state}")
      _ <- IO.println(s"   Effects: ${transition1.effects.map(_.getClass.getSimpleName)}")
      
      // Simulate receiving votes
      voteResp = RequestVoteResponse(term = 1, voteGranted = true)
      state2 <- stateRef.get
      trans2 = RaftLogic.onMessage(state2, voteResp, config, 0, 0, 3, _ => None)
      _ <- stateRef.set(trans2.state)
      trans3 = RaftLogic.onMessage(trans2.state, voteResp, config, 0, 0, 3, _ => None)
      _ <- stateRef.set(trans3.state)
      _ <- IO.println(s"   After votes: ${trans3.state}")
      
      // Make it leader for demo
      _ <- stateRef.set(Leader(term = 1, Map.empty, Map.empty, 0L))
      
      // Step 2: Apply commands through RAFT log
      _ <- IO.println("\nStep 2: Appending Commands to RAFT Log")
      _ <- IO.println("─" * 40)
      
      commands = List(
        KVCommand.Put("user:1", "Alice"),
        KVCommand.Put("user:2", "Bob"),
        KVCommand.Put("config:timeout", "30s")
      )
      
      _ <- commands.zipWithIndex.traverse_ { case (cmd, idx) =>
        val entry = Log.command(idx + 1, 1, KVCommand.encode(cmd))
        logRef.update(_ :+ entry) *>
        IO.println(s"   Appended: $cmd at index ${idx + 1}")
      }
      
      log <- logRef.get
      _ <- IO.println(s"   RAFT log has ${log.size} entries")
      
      // Step 3: Commit and apply to state machine
      _ <- IO.println("\nStep 3: Committing and Applying to StateMachine")
      _ <- IO.println("─" * 40)
      
      _ <- log.traverse_ { entry =>
        fsm.apply(entry).flatMap {
          case KVResult.Ok => IO.println(s"   Applied index ${entry.index} ✓")
          case other => IO.println(s"   Applied index ${entry.index}: $other")
        }
      }
      
      // Step 4: Query state
      _ <- IO.println("\nStep 4: Querying Replicated State")
      _ <- IO.println("─" * 40)
      
      user1 <- fsm.get("user:1")
      user2 <- fsm.get("user:2")
      _ <- IO.println(s"   user:1 = ${user1.getOrElse("?")}")
      _ <- IO.println(s"   user:2 = ${user2.getOrElse("?")}")
      
      // Step 5: Delete command through RAFT
      _ <- IO.println("\nStep 5: Delete via RAFT Consensus")
      _ <- IO.println("─" * 40)
      
      delCmd = KVCommand.Delete("user:2")
      delEntry = Log.command(4, 1, KVCommand.encode(delCmd))
      _ <- logRef.update(_ :+ delEntry)
      _ <- fsm.apply(delEntry)
      _ <- IO.println(s"   Deleted user:2")
      
      user2After <- fsm.get("user:2")
      _ <- IO.println(s"   user:2 = ${user2After.getOrElse("(deleted)")}")
      
      // Summary
      _ <- IO.println("\n" + "═" * 60)
      _ <- IO.println("  RAFT Library Components Used:")
      _ <- IO.println("  • RaftLogic.onMessage - consensus transitions")
      _ <- IO.println("  • NodeState (Follower/Candidate/Leader)")
      _ <- IO.println("  • RaftMessage (ElectionTimeout, RequestVoteResponse)")
      _ <- IO.println("  • Log - replicated log entries")
      _ <- IO.println("  • StateMachine[F, KVResult] - application state")
      _ <- IO.println("═" * 60)
    yield ()

