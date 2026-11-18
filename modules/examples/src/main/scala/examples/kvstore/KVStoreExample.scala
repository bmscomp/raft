package examples.kvstore

import cats.effect.*
import cats.syntax.all.*
import raft.state.*
import raft.spi.StateMachine

import java.nio.charset.StandardCharsets
import fs2.Stream

/** Example 1: Distributed Key-Value Store
  *
  * A simple in-memory key-value store replicated across RAFT nodes.
  * Demonstrates how to implement a StateMachine for your own data.
  */
/** Available key-value commands for the distributed store.
  *
  * Each command is serialized to a byte array for inclusion in the RAFT log and
  * deserialized on every replica when the entry is applied.
  */

enum KVCommand:
  case Put(key: String, value: String)
  case Delete(key: String)
  case Get(key: String)
  case CAS(key: String, expected: String, newValue: String)

object KVCommand:
  private val SEP = "\t"

  def encode(cmd: KVCommand): Array[Byte] = cmd match
    case KVCommand.Put(k, v) =>
      s"PUT$SEP$k$SEP$v".getBytes(StandardCharsets.UTF_8)
    case KVCommand.Delete(k) => s"DEL$SEP$k".getBytes(StandardCharsets.UTF_8)
    case KVCommand.Get(k)    => s"GET$SEP$k".getBytes(StandardCharsets.UTF_8)
    case KVCommand.CAS(k, expected, newVal) =>
      s"CAS$SEP$k$SEP$expected$SEP$newVal".getBytes(StandardCharsets.UTF_8)

  def decode(data: Array[Byte]): Either[String, KVCommand] =
    val str = new String(data, StandardCharsets.UTF_8)
    str.split(SEP).toList match
      case "PUT" :: key :: value :: Nil => Right(KVCommand.Put(key, value))
      case "DEL" :: key :: Nil          => Right(KVCommand.Delete(key))
      case "GET" :: key :: Nil          => Right(KVCommand.Get(key))
      case "CAS" :: key :: expected :: newVal :: Nil =>
        Right(KVCommand.CAS(key, expected, newVal))
      case _ => Left(s"Invalid command: $str")

/** Result types returned by the key-value state machine after command
  * application.
  */

enum KVResult:
  case Ok
  case Value(v: Option[String])
  case CasOk
  case CasFailed(currentValue: Option[String])
  case Error(msg: String)

/** In-memory key-value state machine implementing [[raft.spi.StateMachine]].
  *
  * Stores a `Map[String, String]` in a `Ref` and applies `KVCommand` operations
  * from committed RAFT log entries. Supports snapshot and restore for log
  * compaction.
  *
  * @param store
  *   atomic reference to the underlying key-value map
  */

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

      case Right(KVCommand.CAS(key, expected, newValue)) =>
        store.modify { m =>
          m.get(key) match
            case Some(v) if v == expected =>
              (m.updated(key, newValue), KVResult.CasOk)
            case current =>
              (m, KVResult.CasFailed(current))
        }

      case Left(err) =>
        Sync[F].pure(KVResult.Error(err))

  def snapshot: F[Stream[F, Byte]] =
    store.get.map { m =>
      Stream
        .emits(
          m.toSeq
            .sortBy(_._1)
            .map { case (k, v) => s"$k=$v" }
            .mkString("\n")
            .getBytes(StandardCharsets.UTF_8)
        )
        .covary[F]
    }

  def restore(data: Stream[F, Byte]): F[Unit] =
    data.compile.to(Array).flatMap { bytes =>
      val str = new String(bytes, StandardCharsets.UTF_8)
      val entries =
        if str.isEmpty then Map.empty[String, String]
        else
          str
            .split("\n")
            .flatMap { line =>
              line.split("=", 2) match
                case Array(k, v) => Some(k -> v)
                case _           => None
            }
            .toMap
      store.set(entries)
    }

  // Additional method for reads (not in trait)
  def get(key: String): F[Option[String]] =
    store.get.map(_.get(key))

object KVStateMachine:
  def apply[F[_]: Sync]: F[KVStateMachine[F]] =
    Ref.of[F, Map[String, String]](Map.empty).map(new KVStateMachine[F](_))

import raft.state.NodeState.*
import raft.message.RaftMessage.*
import raft.logic.*
import scala.concurrent.duration.*

object KVStoreExample extends IOApp.Simple:

  def run: IO[Unit] = runScenario.void

  def runScenario: IO[Map[String, Option[String]]] =
    for
      _ <- IO.println("═" * 60)
      _ <- IO.println("  Distributed Key-Value Store with RAFT Library")
      _ <- IO.println("═" * 60)
      _ <- IO.println("\nThis example uses RaftLogic for consensus.\n")

      // Create state machine
      fsm <- KVStateMachine[IO]

      // Create RAFT node state
      nodeId = NodeId("kv-node-1")
      peer2 = NodeId("kv-node-2")
      peer3 = NodeId("kv-node-3")
      cluster = ClusterConfig(Set(nodeId, peer2, peer3))
      config = RaftConfig(nodeId, 150.millis, 300.millis, 50.millis)
      stateRef <- Ref.of[IO, NodeState](Follower(0, None, None))
      logRef <- Ref.of[IO, Vector[Log]](Vector.empty)

      // Step 1: Simulate two-phase Pre-Vote -> Real Vote leader election using RaftLogic
      _ <- IO.println("Step 1: Leader Election via RaftLogic")
      _ <- IO.println("─" * 40)

      state1 <- stateRef.get
      transition1 = RaftLogic.onMessage(
        state1,
        ElectionTimeout,
        config,
        0,
        0,
        3,
        _ => None,
        clusterOpt = Some(cluster)
      )
      _ <- IO.println(s"   After ElectionTimeout: ${transition1.state}")
      _ <- IO.println(
        s"   Effects: ${transition1.effects.map(_.getClass.getSimpleName)}"
      )

      // Phase 1b: Pre-Vote grant promotes PreCandidate(0) -> Candidate(1)
      preVoteGrant = RequestVoteResponse(
        term = 1,
        voteGranted = true,
        isPreVote = true
      )
      candidateTrans = RaftLogic.onMessage(
        transition1.state,
        preVoteGrant,
        config,
        0,
        0,
        3,
        _ => None,
        sender = Some(peer2),
        clusterOpt = Some(cluster)
      )
      _ <- IO.println(s"   After Pre-Vote quorum: ${candidateTrans.state}")

      // Phase 2: Real vote grant promotes Candidate(1) -> Leader(1)
      voteGrant = RequestVoteResponse(
        term = 1,
        voteGranted = true,
        isPreVote = false
      )
      leaderTrans = RaftLogic.onMessage(
        candidateTrans.state,
        voteGrant,
        config,
        0,
        0,
        3,
        _ => None,
        sender = Some(peer2),
        clusterOpt = Some(cluster)
      )
      _ <- stateRef.set(leaderTrans.state)
      _ <- IO.println(s"   After Vote quorum:     ${leaderTrans.state}")

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
      cfgTimeout <- fsm.get("config:timeout")
      _ <- IO.println("\n" + "═" * 60)
      _ <- IO.println("  RAFT Library Components Used:")
      _ <- IO.println("  • RaftLogic.onMessage - consensus transitions")
      _ <- IO.println("  • NodeState (Follower/Candidate/Leader)")
      _ <- IO.println("  • RaftMessage (ElectionTimeout, RequestVoteResponse)")
      _ <- IO.println("  • Log - replicated log entries")
      _ <- IO.println("  • StateMachine[F, KVResult] - application state")
      _ <- IO.println("═" * 60)
    yield Map(
      "user:1" -> user1,
      "user:2" -> user2After,
      "config:timeout" -> cfgTimeout
    )
