package examples.distributed

import cats.effect.*
import cats.effect.std.Queue
import cats.syntax.all.*
import java.util.UUID
import scala.concurrent.duration.*

import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.logic.*
import raft.effect.Effect.*
import raft.spi.StateMachine

/** Distributed transactions with RAFT using full library integration.
  *
  * This example demonstrates distributed transactions using the RAFT library's
  * core components: `RaftLogic` for pure state transitions, effects for I/O,
  * and a `TransactionalStore` state machine.
  *
  * Architecture:
  * {{{Client → RaftNode (Leader) → RaftLogic → Effects → Replication → TransactionalStore.apply()}}}
  *
  * RAFT library components used:
  *   - [[raft.logic.RaftLogic]]: pure state transitions for consensus
  *   - [[raft.state.NodeState]]: Follower/Candidate/Leader states
  *   - [[raft.message.RaftMessage]]: protocol messages (AppendEntries,
  *     RequestVote)
  *   - [[raft.effect.Effect]]: side effects (SendMessage, AppendLogs,
  *     CommitEntries)
  *   - [[raft.state.RaftConfig]]: node configuration
  *   - [[raft.spi.StateMachine]]: transaction execution
  */
object RaftTransactionExample extends IOApp.Simple:

  /** Transaction-related domain types, identical to the standalone transaction
    * example.
    */

  case class TxnId(value: String)
  object TxnId:
    def generate: TxnId = TxnId(UUID.randomUUID().toString.take(8))

  enum TxnOperation:
    case Put(key: String, value: String)
    case Delete(key: String)
    case CompareAndSet(key: String, expected: Option[String], newValue: String)
    case Increment(key: String, delta: Long)

  case class Transaction(
      id: TxnId,
      operations: List[TxnOperation],
      timestamp: Long = System.currentTimeMillis()
  )

  enum TxnResult:
    case Committed(txnId: TxnId, version: Long)
    case Aborted(txnId: TxnId, reason: String)
    case ConflictDetected(
        txnId: TxnId,
        key: String,
        expected: Option[String],
        actual: Option[String]
    )

  object Transaction:
    private val SEP = "\t"
    private val OP_SEP = "\n"

    def encode(txn: Transaction): Array[Byte] =
      val ops = txn.operations
        .map {
          case TxnOperation.Put(k, v)              => s"PUT$SEP$k$SEP$v"
          case TxnOperation.Delete(k)              => s"DEL$SEP$k"
          case TxnOperation.CompareAndSet(k, e, n) =>
            s"CAS$SEP$k$SEP${e.getOrElse("NULL")}$SEP$n"
          case TxnOperation.Increment(k, d) => s"INC$SEP$k$SEP$d"
        }
        .mkString(OP_SEP)
      s"TXN$SEP${txn.id.value}$SEP${txn.timestamp}$SEP$ops".getBytes

    def decode(data: Array[Byte]): Either[String, Transaction] =
      new String(data).split(SEP, 4).toList match
        case "TXN" :: id :: ts :: ops :: Nil =>
          parseOps(ops).map(Transaction(TxnId(id), _, ts.toLong))
        case "TXN" :: id :: ts :: Nil =>
          Right(Transaction(TxnId(id), Nil, ts.toLong))
        case _ => Left("Invalid format")

    private def parseOps(str: String): Either[String, List[TxnOperation]] =
      if str.isEmpty then Right(Nil)
      else
        str.split(OP_SEP).toList.traverse { op =>
          op.split(SEP).toList match
            case "PUT" :: k :: v :: Nil      => Right(TxnOperation.Put(k, v))
            case "DEL" :: k :: Nil           => Right(TxnOperation.Delete(k))
            case "CAS" :: k :: e :: n :: Nil =>
              Right(
                TxnOperation
                  .CompareAndSet(k, if e == "NULL" then None else Some(e), n)
              )
            case "INC" :: k :: d :: Nil =>
              Right(TxnOperation.Increment(k, d.toLong))
            case _ => Left(s"Invalid op: $op")
        }

  /** Transactional state machine implementing the [[raft.spi.StateMachine]]
    * SPI.
    *
    * Validates CAS preconditions before applying operations atomically.
    * Produces `TxnResult.Committed` on success or `TxnResult.ConflictDetected`
    * when an optimistic concurrency check fails.
    *
    * @param state
    *   in-memory key-value store
    * @param txnLog
    *   ordered history of transaction results
    * @param version
    *   monotonically increasing version counter
    */

  class TransactionalStore[F[_]: Sync](
      state: Ref[F, Map[String, String]],
      txnLog: Ref[F, List[TxnResult]],
      version: Ref[F, Long]
  ) extends StateMachine[F, TxnResult]:

    def apply(log: Log): F[TxnResult] =
      Transaction.decode(log.data) match
        case Left(err)  => Sync[F].pure(TxnResult.Aborted(TxnId("?"), err))
        case Right(txn) => execute(txn)

    private def execute(txn: Transaction): F[TxnResult] =
      for
        current <- state.get
        result <- validate(txn, current) match
          case Some(conflict) => txnLog.update(_ :+ conflict).as(conflict)
          case None           =>
            for
              v <- version.updateAndGet(_ + 1)
              _ <- applyOps(txn.operations)
              r = TxnResult.Committed(txn.id, v)
              _ <- txnLog.update(_ :+ r)
            yield r
      yield result

    private def validate(
        txn: Transaction,
        current: Map[String, String]
    ): Option[TxnResult] =
      txn.operations.collectFirst {
        case TxnOperation.CompareAndSet(k, exp, _) if current.get(k) != exp =>
          TxnResult.ConflictDetected(txn.id, k, exp, current.get(k))
      }

    private def applyOps(ops: List[TxnOperation]): F[Unit] =
      ops.traverse_ {
        case TxnOperation.Put(k, v) => state.update(_.updated(k, v))
        case TxnOperation.Delete(k) => state.update(_ - k)
        case TxnOperation.CompareAndSet(k, _, v) =>
          state.update(_.updated(k, v))
        case TxnOperation.Increment(k, d) =>
          state.update { s =>
            val cur = s.get(k).flatMap(_.toLongOption).getOrElse(0L)
            s.updated(k, (cur + d).toString)
          }
      }

    def get(key: String): F[Option[String]] = state.get.map(_.get(key))
    def getAll: F[Map[String, String]] = state.get
    def getLog: F[List[TxnResult]] = txnLog.get
    def snapshot: F[Array[Byte]] =
      state.get.map(_.map((k, v) => s"$k=$v").mkString("\n").getBytes)
    def restore(data: Array[Byte]): F[Unit] = state.set(
      new String(data)
        .split("\n")
        .flatMap(_.split("=", 2) match {
          case Array(k, v) => Some(k -> v)
          case _           => None
        })
        .toMap
    )

  object TransactionalStore:
    def apply[F[_]: Sync]: F[TransactionalStore[F]] =
      for
        s <- Ref.of[F, Map[String, String]](Map.empty)
        l <- Ref.of[F, List[TxnResult]](Nil)
        v <- Ref.of[F, Long](0L)
      yield new TransactionalStore[F](s, l, v)

  /** RAFT node with transaction support.
    *
    * Uses [[raft.logic.RaftLogic]] for consensus and a `TransactionalStore` for
    * executing transaction operations. In production this would be replaced by
    * the full [[raft.RaftNode]] runtime.
    *
    * @param nodeId
    *   unique identifier for this node
    * @param config
    *   RAFT configuration (timeouts, pre-vote, etc.)
    * @param stateRef
    *   mutable reference to the current `NodeState`
    * @param logRef
    *   mutable reference to the replicated log
    * @param commitIndexRef
    *   mutable reference to the highest committed index
    * @param stateMachine
    *   transactional state machine for command execution
    * @param messageQueue
    *   outgoing message buffer
    * @param clusterSize
    *   total number of nodes in the cluster
    */

  class RaftTransactionNode[F[_]: Async](
      val nodeId: NodeId,
      config: RaftConfig,
      val stateRef: Ref[F, NodeState],
      logRef: Ref[F, Vector[Log]],
      commitIndexRef: Ref[F, Long],
      stateMachine: TransactionalStore[F],
      messageQueue: Queue[F, (NodeId, RaftMessage)],
      clusterSize: Int
  ):

    /** Submit a transaction (leader only). Returns commit result. */
    def submitTransaction(txn: Transaction): F[Either[String, TxnResult]] =
      for
        state <- stateRef.get
        result <- state match
          case l: Leader =>
            for
              logs <- logRef.get
              newIndex = if logs.isEmpty then 1L else logs.last.index + 1
              entry = Log.command(newIndex, l.term, Transaction.encode(txn))
              _ <- logRef.update(_ :+ entry)
              _ <- Async[F].delay(
                println(
                  s"   📝 [${nodeId.value}] Appended txn ${txn.id.value} at index $newIndex"
                )
              )

              // Simulate replication by broadcasting AppendEntries
              appendReq = AppendEntriesRequest(
                term = l.term,
                leaderId = nodeId,
                prevLogIndex = newIndex - 1,
                prevLogTerm = logs.lastOption.map(_.term).getOrElse(0L),
                entries = Seq(entry),
                leaderCommit = l.commitIndex
              )
              _ <- broadcastToFollowers(appendReq)
            yield Right(TxnResult.Committed(txn.id, newIndex))
          case _ =>
            Async[F].pure(Left("Not leader - redirect to leader"))
      yield result

    /** Process incoming RAFT message using RaftLogic. */
    def processMessage(from: NodeId, msg: RaftMessage): F[Unit] =
      for
        state <- stateRef.get
        logs <- logRef.get

        lastIdx = logs.lastOption.map(_.index).getOrElse(0L)
        lastTerm = logs.lastOption.map(_.term).getOrElse(0L)
        getTermAt = (idx: Long) => logs.find(_.index == idx).map(_.term)

        // Use RaftLogic for pure state transition
        transition = RaftLogic.onMessage(
          state,
          msg,
          config,
          lastIdx,
          lastTerm,
          clusterSize,
          getTermAt
        )

        _ <- stateRef.set(transition.state)
        _ <- transition.effects.traverse_(executeEffect)
      yield ()

    /** Execute effects produced by RaftLogic. */
    private def executeEffect(effect: raft.effect.Effect): F[Unit] =
      effect match
        case SendMessage(to, msg) =>
          messageQueue.offer((to, msg))

        case Broadcast(msg) =>
          Async[F].delay(
            println(
              s"   📡 [${nodeId.value}] Broadcasting ${msg.getClass.getSimpleName}"
            )
          )

        case AppendLogs(entries) =>
          logRef.update(_ ++ entries) *>
            Async[F].delay(
              println(
                s"   📥 [${nodeId.value}] Appended ${entries.size} entries"
              )
            )

        case CommitEntries(upToIndex) =>
          for
            logs <- logRef.get
            currentCommit <- commitIndexRef.get
            toApply = logs.filter(l =>
              l.index > currentCommit && l.index <= upToIndex
            )
            _ <- toApply.traverse_ { entry =>
              stateMachine.apply(entry) *>
                Async[F].delay(
                  println(
                    s"   ✅ [${nodeId.value}] Applied entry ${entry.index}"
                  )
                )
            }
            _ <- commitIndexRef.set(upToIndex)
          yield ()

        case BecomeLeader =>
          Async[F].delay(println(s"   🎉 [${nodeId.value}] BECAME LEADER!"))

        case _ => Async[F].unit

    private def broadcastToFollowers(msg: RaftMessage): F[Unit] =
      Async[F].delay(
        println(s"   📡 [${nodeId.value}] Replicating to followers...")
      )

    def getState: F[NodeState] = stateRef.get
    def getLog: F[Vector[Log]] = logRef.get
    def getCommitIndex: F[Long] = commitIndexRef.get

  object RaftTransactionNode:
    def apply[F[_]: Async](
        nodeId: NodeId,
        clusterSize: Int,
        stateMachine: TransactionalStore[F]
    ): F[RaftTransactionNode[F]] =
      for
        state <- Ref.of[F, NodeState](Follower(0, None, None))
        log <- Ref.of[F, Vector[Log]](Vector.empty)
        commit <- Ref.of[F, Long](0L)
        queue <- Queue.unbounded[F, (NodeId, RaftMessage)]
        config = RaftConfig(nodeId, 150.millis, 300.millis, 50.millis)
      yield new RaftTransactionNode[F](
        nodeId,
        config,
        state,
        log,
        commit,
        stateMachine,
        queue,
        clusterSize
      )

  def run: IO[Unit] =
    for
      _ <- IO.println("═" * 70)
      _ <- IO.println("  DISTRIBUTED TRANSACTIONS WITH RAFT LIBRARY")
      _ <- IO.println("═" * 70)
      _ <- IO.println("\nThis example uses the actual RAFT library components:")
      _ <- IO.println("  • RaftLogic for pure state transitions")
      _ <- IO.println("  • NodeState (Follower/Candidate/Leader)")
      _ <- IO.println("  • RaftMessage (AppendEntries, RequestVote)")
      _ <- IO.println("  • Effect system for side effects")
      _ <- IO.println("  • StateMachine SPI for transaction execution\n")
      _ <- runDemo
    yield ()

  private def runDemo: IO[Unit] =
    for
      // Create state machines for each node
      fsm1 <- TransactionalStore[IO]
      fsm2 <- TransactionalStore[IO]
      fsm3 <- TransactionalStore[IO]

      // Create RAFT nodes with transaction support
      node1 <- RaftTransactionNode[IO](NodeId("node-1"), 3, fsm1)
      node2 <- RaftTransactionNode[IO](NodeId("node-2"), 3, fsm2)
      node3 <- RaftTransactionNode[IO](NodeId("node-3"), 3, fsm3)

      _ <- IO.println("Step 1: Elect a leader using RaftLogic")
      _ <- IO.println("─" * 50)

      // Trigger election on node-1
      _ <- node1.processMessage(NodeId("node-1"), ElectionTimeout)
      state1 <- node1.getState
      _ <- IO.println(s"   node-1 state after timeout: $state1")

      // Nodes 2 and 3 receive vote request and respond
      config1 = RaftConfig(NodeId("node-1"), 150.millis, 300.millis, 50.millis)
      voteReq = RequestVoteRequest(
        term = 1,
        candidateId = NodeId("node-1"),
        lastLogIndex = 0,
        lastLogTerm = 0
      )

      _ <- node2.processMessage(NodeId("node-1"), voteReq)
      _ <- node3.processMessage(NodeId("node-1"), voteReq)

      state2 <- node2.getState
      state3 <- node3.getState
      _ <- IO.println(s"   node-2 state: $state2")
      _ <- IO.println(s"   node-3 state: $state3")

      // Simulate node-1 receiving votes and becoming leader
      voteResp = RequestVoteResponse(term = 1, voteGranted = true)
      _ <- node1.processMessage(NodeId("node-2"), voteResp)
      _ <- node1.processMessage(NodeId("node-3"), voteResp)

      leaderState <- node1.getState
      _ <- IO.println(s"   node-1 after votes: $leaderState\n")

      // ─────────────────────────────────────────────────────────────────────
      _ <- IO.println("Step 2: Submit transactions through the leader")
      _ <- IO.println("─" * 50)

      // Make node-1 the leader for transaction submission
      _ <- node1.stateRef.set(Leader(term = 1, Map.empty, Map.empty, 0L))

      // Transaction 1: Bank transfer
      txn1 = Transaction(
        id = TxnId.generate,
        operations = List(
          TxnOperation.Put("account:alice", "1000"),
          TxnOperation.Put("account:bob", "500"),
          TxnOperation.Put("audit:init", "accounts created")
        )
      )

      _ <- IO.println(
        s"\n   Submitting: Create accounts (txn ${txn1.id.value})"
      )
      result1 <- node1.submitTransaction(txn1)
      _ <- IO.println(s"   Result: $result1")

      // Transaction 2: Transfer
      txn2 = Transaction(
        id = TxnId.generate,
        operations = List(
          TxnOperation.Put("account:alice", "800"),
          TxnOperation.Put("account:bob", "700"),
          TxnOperation.Put("audit:transfer", "alice->bob:200")
        )
      )

      _ <- IO.println(s"\n   Submitting: Transfer 200 (txn ${txn2.id.value})")
      result2 <- node1.submitTransaction(txn2)
      _ <- IO.println(s"   Result: $result2")

      // ─────────────────────────────────────────────────────────────────────
      _ <- IO.println("\nStep 3: Verify RAFT log replication")
      _ <- IO.println("─" * 50)

      log1 <- node1.getLog
      _ <- IO.println(s"   Leader log has ${log1.size} entries:")
      _ <- log1.traverse_ { entry =>
        Transaction.decode(entry.data) match
          case Right(txn) =>
            IO.println(
              s"      Index ${entry.index}: txn ${txn.id.value} with ${txn.operations.size} ops"
            )
          case Left(_) => IO.unit
      }

      // ─────────────────────────────────────────────────────────────────────
      _ <- IO.println("\nStep 4: Apply committed entries to state machine")
      _ <- IO.println("─" * 50)

      // Manually trigger commit (in production, this happens via majority ack)
      _ <- log1.traverse_(entry => fsm1.apply(entry))

      finalState <- fsm1.getAll
      _ <- IO.println(s"   State machine has ${finalState.size} keys:")
      _ <- finalState.toList.sorted.traverse_ { case (k, v) =>
        IO.println(s"      $k = $v")
      }

      txnLog <- fsm1.getLog
      _ <- IO.println(s"\n   Transaction log:")
      _ <- txnLog.traverse_ {
        case TxnResult.Committed(id, v) =>
          IO.println(s"      ✓ ${id.value} committed at version $v")
        case TxnResult.ConflictDetected(id, k, _, _) =>
          IO.println(s"      ⚠ ${id.value} conflict on $k")
        case TxnResult.Aborted(id, r) =>
          IO.println(s"      ✗ ${id.value} aborted: $r")
      }

      // ─────────────────────────────────────────────────────────────────────
      _ <- IO.println("\n" + "═" * 70)
      _ <- IO.println("  RAFT LIBRARY COMPONENTS USED")
      _ <- IO.println("═" * 70)
      _ <- IO.println("""
   ┌─────────────────────────────────────────────────────────────────┐
   │ Component              │ File                    │ Usage       │
   ├─────────────────────────────────────────────────────────────────┤
   │ RaftLogic.onMessage    │ logic/RaftLogic.scala   │ Consensus   │
   │ NodeState (enum)       │ state/NodeState.scala   │ States      │
   │ RaftMessage (enum)     │ message/RaftMessage.scala│ Protocol   │
   │ Effect (enum)          │ effect/Effect.scala     │ Side effects│
   │ RaftConfig             │ logic/RaftConfig.scala  │ Config      │
   │ StateMachine[F, R]     │ spi/StateMachine.scala  │ Transactions│
   │ Log                    │ state/Log.scala         │ Entries     │
   └─────────────────────────────────────────────────────────────────┘
""")
      _ <- IO.println("═" * 70)
      _ <- IO.println(
        "\n✅ RAFT-based distributed transaction example complete!"
      )
    yield ()
