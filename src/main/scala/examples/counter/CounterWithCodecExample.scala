package examples.counter

import cats.effect.*
import cats.syntax.all.*
import raft.state.*
import raft.message.*
import raft.spi.StateMachine

import java.nio.charset.StandardCharsets

/**
 * Example 3: Distributed Counter with Custom Message Codec
 * 
 * Shows how to implement MessageCodec[M] for a custom wire format.
 */

// === Counter Commands ===

enum CounterCommand:
  case Increment(amount: Long)
  case Decrement(amount: Long)
  case Reset

object CounterCommand:
  def encode(cmd: CounterCommand): Array[Byte] =
    val str = cmd match
      case CounterCommand.Increment(n) => s"INC:$n"
      case CounterCommand.Decrement(n) => s"DEC:$n"
      case CounterCommand.Reset        => "RESET"
    str.getBytes(StandardCharsets.UTF_8)
  
  def decode(data: Array[Byte]): Either[String, CounterCommand] =
    val str = new String(data, StandardCharsets.UTF_8)
    str.split(":").toList match
      case "INC" :: n :: Nil   => Right(CounterCommand.Increment(n.toLong))
      case "DEC" :: n :: Nil   => Right(CounterCommand.Decrement(n.toLong))
      case "RESET" :: Nil      => Right(CounterCommand.Reset)
      case _                   => Left(s"Invalid command: $str")

// === Counter State Machine ===

class CounterStateMachine[F[_]: Sync](
  counter: Ref[F, Long]
) extends StateMachine[F, Long]:
  
  def apply(log: Log): F[Long] =
    CounterCommand.decode(log.data) match
      case Right(CounterCommand.Increment(n)) =>
        counter.updateAndGet(_ + n)
      
      case Right(CounterCommand.Decrement(n)) =>
        counter.updateAndGet(_ - n)
      
      case Right(CounterCommand.Reset) =>
        counter.set(0L).as(0L)
      
      case Left(err) =>
        Sync[F].raiseError(new RuntimeException(err))
  
  def get: F[Long] = counter.get
  
  def snapshot: F[Array[Byte]] =
    counter.get.map(n => n.toString.getBytes)
  
  def restore(data: Array[Byte]): F[Unit] =
    counter.set(new String(data).toLong)

object CounterStateMachine:
  def apply[F[_]: Sync]: F[CounterStateMachine[F]] =
    Ref.of[F, Long](0L).map(new CounterStateMachine[F](_))

// === Custom Wire Format ===

final case class TextWireMessage(content: String)

/**
 * Custom MessageCodec implementation.
 */
object TextMessageCodec extends SimpleMessageCodec[TextWireMessage]:
  
  def encode(msg: RaftMessage): TextWireMessage =
    val content = msg match
      case RaftMessage.AppendEntriesRequest(term, leader, prev, prevTerm, entries, commit) =>
        s"AE_REQ|$term|${leader.value}|$prev|$prevTerm|${entries.size}|$commit"
      
      case RaftMessage.AppendEntriesResponse(term, success, matchIdx) =>
        s"AE_RESP|$term|$success|$matchIdx"
      
      case RaftMessage.RequestVoteRequest(term, candidate, lastIdx, lastTerm, preVote) =>
        s"RV_REQ|$term|${candidate.value}|$lastIdx|$lastTerm|$preVote"
      
      case RaftMessage.RequestVoteResponse(term, granted, preVote) =>
        s"RV_RESP|$term|$granted|$preVote"
      
      case RaftMessage.ElectionTimeout =>
        "ELECTION_TIMEOUT"
      
      case RaftMessage.HeartbeatTimeout =>
        "HEARTBEAT_TIMEOUT"
      
      case other =>
        s"UNKNOWN|${other.toString}"
    
    TextWireMessage(content)
  
  def decode(raw: TextWireMessage): Either[CodecError, RaftMessage] =
    raw.content.split("\\|").toList match
      case "AE_REQ" :: term :: leader :: prev :: prevTerm :: entries :: commit :: Nil =>
        Right(RaftMessage.AppendEntriesRequest(
          term.toLong,
          NodeId(leader),
          prev.toLong,
          prevTerm.toLong,
          Seq.empty,
          commit.toLong
        ))
      
      case "AE_RESP" :: term :: success :: matchIdx :: Nil =>
        Right(RaftMessage.AppendEntriesResponse(
          term.toLong,
          success.toBoolean,
          matchIdx.toLong
        ))
      
      case "RV_REQ" :: term :: candidate :: lastIdx :: lastTerm :: preVote :: Nil =>
        Right(RaftMessage.RequestVoteRequest(
          term.toLong,
          NodeId(candidate),
          lastIdx.toLong,
          lastTerm.toLong,
          preVote.toBoolean
        ))
      
      case "RV_RESP" :: term :: granted :: preVote :: Nil =>
        Right(RaftMessage.RequestVoteResponse(
          term.toLong,
          granted.toBoolean,
          preVote.toBoolean
        ))
      
      case "ELECTION_TIMEOUT" :: Nil =>
        Right(RaftMessage.ElectionTimeout)
      
      case "HEARTBEAT_TIMEOUT" :: Nil =>
        Right(RaftMessage.HeartbeatTimeout)
      
      case _ =>
        Left(CodecError.ParseError(s"Unknown message: ${raw.content}"))

// === Example Usage with RAFT Library Integration ===

import raft.state.NodeState.*
import raft.message.RaftMessage.*
import raft.logic.*
import raft.effect.Effect.*
import scala.concurrent.duration.*

object CounterWithCodecExample extends IOApp.Simple:
  
  def run: IO[Unit] =
    for
      _ <- IO.println("═" * 60)
      _ <- IO.println("  Distributed Counter with RAFT Library + Custom Codec")
      _ <- IO.println("═" * 60)
      _ <- IO.println("\nThis example uses RaftLogic + MessageCodec.\n")
      
      counter <- CounterStateMachine[IO]
      
      // Create RAFT node state
      nodeId = NodeId("counter-node-1")
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
      _ <- IO.println(s"   Effects produced: ${trans1.effects.size}")
      
      // Simulate votes
      voteResp = RequestVoteResponse(term = 1, voteGranted = true)
      state2 <- stateRef.get
      trans2 = RaftLogic.onMessage(state2, voteResp, config, 0, 0, 3, _ => None)
      trans3 = RaftLogic.onMessage(trans2.state, voteResp, config, 0, 0, 3, _ => None)
      _ <- stateRef.set(trans3.state)
      _ <- IO.println(s"   After votes: ${trans3.state}")
      
      // Step 2: Increment counter through RAFT log
      _ <- IO.println("\nStep 2: Incrementing Counter via RAFT")
      _ <- IO.println("─" * 40)
      
      cmd1 = CounterCommand.Increment(10)
      idx1 <- logIndexRef.updateAndGet(_ + 1)
      entry1 = Log.command(idx1, 1, CounterCommand.encode(cmd1))
      _ <- logRef.update(_ :+ entry1)
      n1 <- counter.apply(entry1)
      _ <- IO.println(s"   Appended: $cmd1 at index $idx1 -> value=$n1")
      
      cmd2 = CounterCommand.Increment(5)
      idx2 <- logIndexRef.updateAndGet(_ + 1)
      entry2 = Log.command(idx2, 1, CounterCommand.encode(cmd2))
      _ <- logRef.update(_ :+ entry2)
      n2 <- counter.apply(entry2)
      _ <- IO.println(s"   Appended: $cmd2 at index $idx2 -> value=$n2")
      
      // Step 3: Decrement counter
      _ <- IO.println("\nStep 3: Decrementing Counter via RAFT")
      _ <- IO.println("─" * 40)
      
      cmd3 = CounterCommand.Decrement(3)
      idx3 <- logIndexRef.updateAndGet(_ + 1)
      entry3 = Log.command(idx3, 1, CounterCommand.encode(cmd3))
      _ <- logRef.update(_ :+ entry3)
      n3 <- counter.apply(entry3)
      _ <- IO.println(s"   Appended: $cmd3 at index $idx3 -> value=$n3")
      
      value <- counter.get
      _ <- IO.println(s"   Final counter value: $value")
      
      // Step 4: Demonstrate custom MessageCodec
      _ <- IO.println("\nStep 4: Custom MessageCodec Demo")
      _ <- IO.println("─" * 40)
      
      _ <- demonstrateCodec
      
      // Summary
      log <- logRef.get
      _ <- IO.println("\n" + "═" * 60)
      _ <- IO.println(s"  RAFT Log: ${log.size} entries")
      _ <- IO.println("  RAFT Library Components Used:")
      _ <- IO.println("  • RaftLogic.onMessage - consensus transitions")
      _ <- IO.println("  • NodeState (Follower/Candidate/Leader)")
      _ <- IO.println("  • RaftMessage + MessageCodec - serialization")
      _ <- IO.println("  • Log - replicated counter commands")
      _ <- IO.println("  • StateMachine[F, Long] - counter state")
      _ <- IO.println("═" * 60)
    yield ()
  
  private def demonstrateCodec: IO[Unit] = IO {
    val voteReq = RaftMessage.RequestVoteRequest(
      term = 5,
      candidateId = NodeId("node-1"),
      lastLogIndex = 100,
      lastLogTerm = 4,
      isPreVote = true
    )
    
    val encoded = TextMessageCodec.encode(voteReq)
    println(s"   Encoded: ${encoded.content}")
    
    val decoded = TextMessageCodec.decode(encoded)
    println(s"   Decoded: $decoded")
  }

