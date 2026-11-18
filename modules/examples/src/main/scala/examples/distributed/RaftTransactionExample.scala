package examples.distributed

import cats.effect.*
import cats.effect.std.Queue
import cats.syntax.all.*
import java.util.UUID
import scala.concurrent.duration.*
import fs2.Stream

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
    def snapshot: F[Stream[F, Byte]] =
      for
        s <- state.get
        v <- version.get
      yield
        val header = s"VER\t$v"
        val rows = s.toSeq.sortBy(_._1).map((k, vl) => s"KV\t$k\t$vl")
        Stream
          .emits(
            (header +: rows)
              .mkString("\n")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8)
          )
          .covary[F]

    def restore(data: Stream[F, Byte]): F[Unit] =
      data.compile.to(Array).flatMap { bytes =>
        val str = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
        if str.isEmpty then state.set(Map.empty) *> version.set(0L)
        else
          val lines = str.split("\n").toList
          val restoredVer = lines
            .collectFirst {
              case l if l.startsWith("VER\t") => l.drop(4).toLongOption
            }
            .flatten
            .getOrElse(0L)
          val kvs = lines.flatMap { line =>
            line.split("\t", 3) match
              case Array("KV", k, vl) => Some(k -> vl)
              case _                  => None
          }.toMap
          state.set(kvs) *> version.set(restoredVer)
      }

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
      cluster: ClusterConfig,
      val stateRef: Ref[F, NodeState],
      logRef: Ref[F, Vector[Log]],
      commitIndexRef: Ref[F, Long],
      stateMachine: TransactionalStore[F],
      mailboxes: Map[NodeId, Queue[F, (NodeId, RaftMessage)]]
  ):
    private val clusterSize = cluster.voters.size

    /** Submit a transaction (leader only) and broadcast AppendEntries to
      * followers.
      */
    def submitTransaction(txn: Transaction): F[Either[String, Long]] =
      for
        state <- stateRef.get
        result <- state match
          case l: Leader =>
            for
              logs <- logRef.get
              prevIdx = logs.lastOption.map(_.index).getOrElse(0L)
              prevTerm = logs.lastOption.map(_.term).getOrElse(0L)
              newIndex = prevIdx + 1L
              entry = Log.command(newIndex, l.term, Transaction.encode(txn))
              _ <- logRef.update(_ :+ entry)
              _ <- Async[F].delay(
                println(
                  s"   📝 [${nodeId.value}] Appended txn ${txn.id.value} at index $newIndex"
                )
              )

              appendReq = AppendEntriesRequest(
                term = l.term,
                leaderId = nodeId,
                prevLogIndex = prevIdx,
                prevLogTerm = prevTerm,
                entries = Seq(entry),
                leaderCommit = l.commitIndex
              )
              _ <- broadcastToFollowers(appendReq)
            yield Right(newIndex)
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
          getTermAt,
          sender = Some(from),
          clusterOpt = Some(cluster)
        )

        _ <- stateRef.set(transition.state)
        _ <- transition.effects.traverse_(executeEffect)
      yield ()

    /** Execute effects produced by RaftLogic. */
    private def executeEffect(effect: raft.effect.Effect): F[Unit] =
      effect match
        case SendMessage(to, msg) =>
          mailboxes.get(to).traverse_(_.offer((nodeId, msg)))

        case Broadcast(msg) =>
          broadcastToFollowers(msg)

        case AppendLogs(entries) =>
          logRef.update(_ ++ entries) *>
            Async[F].delay(
              println(
                s"   📥 [${nodeId.value}] Appended ${entries.size} entries"
              )
            )

        case TruncateLog(fromIndex) =>
          logRef.update(_.filter(_.index < fromIndex))

        case CommitEntries(upToIndex) =>
          for
            logs <- logRef.get
            currentCommit <- commitIndexRef.get
            targetCommit = math.max(currentCommit, upToIndex.value)
            toApply = logs.filter(l =>
              l.index > currentCommit && l.index <= targetCommit
            )
            _ <- toApply.traverse_ { entry =>
              stateMachine.apply(entry) *>
                Async[F].delay(
                  println(
                    s"   ✅ [${nodeId.value}] Applied entry ${entry.index}"
                  )
                )
            }
            _ <- commitIndexRef.set(targetCommit)
          yield ()

        case BecomeLeader =>
          Async[F].delay(println(s"   🎉 [${nodeId.value}] BECAME LEADER!"))

        case _ => Async[F].unit

    private def broadcastToFollowers(msg: RaftMessage): F[Unit] =
      mailboxes.toList.traverse_ { case (peerId, q) =>
        if peerId != nodeId then q.offer((nodeId, msg)) else Async[F].unit
      }

    def getState: F[NodeState] = stateRef.get
    def getLog: F[Vector[Log]] = logRef.get
    def getCommitIndex: F[Long] = commitIndexRef.get

  object RaftTransactionNode:
    def apply[F[_]: Async](
        nodeId: NodeId,
        cluster: ClusterConfig,
        stateMachine: TransactionalStore[F],
        mailboxes: Map[NodeId, Queue[F, (NodeId, RaftMessage)]]
    ): F[RaftTransactionNode[F]] =
      for
        state <- Ref.of[F, NodeState](Follower(0, None, None))
        log <- Ref.of[F, Vector[Log]](Vector.empty)
        commit <- Ref.of[F, Long](0L)
        config = RaftConfig(nodeId, 150.millis, 300.millis, 50.millis)
      yield new RaftTransactionNode[F](
        nodeId,
        config,
        cluster,
        state,
        log,
        commit,
        stateMachine,
        mailboxes
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
      _ <- runDemoStates.void
    yield ()

  private def pumpBus(
      nodes: List[RaftTransactionNode[IO]],
      mailboxes: Map[NodeId, Queue[IO, (NodeId, RaftMessage)]]
  ): IO[Unit] =
    val nodeMap = nodes.map(n => n.nodeId -> n).toMap
    def loop: IO[Unit] =
      nodes
        .traverse { n =>
          mailboxes(n.nodeId).tryTake.flatMap {
            case Some((from, msg)) =>
              nodeMap(n.nodeId).processMessage(from, msg).as(true)
            case None => IO.pure(false)
          }
        }
        .flatMap { progressed =>
          if progressed.exists(identity) then loop else IO.unit
        }
    loop

  def runDemoStates: IO[List[Map[String, String]]] =
    for
      // Create state machines for each node
      fsm1 <- TransactionalStore[IO]
      fsm2 <- TransactionalStore[IO]
      fsm3 <- TransactionalStore[IO]

      ids = List(NodeId("node-1"), NodeId("node-2"), NodeId("node-3"))
      cluster = ClusterConfig(ids.toSet)
      mailboxes <- ids
        .traverse(id => Queue.unbounded[IO, (NodeId, RaftMessage)].map(id -> _))
        .map(_.toMap)

      // Create RAFT nodes with transaction support
      node1 <- RaftTransactionNode[IO](ids(0), cluster, fsm1, mailboxes)
      node2 <- RaftTransactionNode[IO](ids(1), cluster, fsm2, mailboxes)
      node3 <- RaftTransactionNode[IO](ids(2), cluster, fsm3, mailboxes)
      allNodes = List(node1, node2, node3)

      _ <- IO.println("Step 1: Elect a leader using RaftLogic")
      _ <- IO.println("─" * 50)

      // Trigger election on node-1 and pump Pre-Vote + Vote rounds across the 3 nodes
      _ <- node1.processMessage(ids(0), ElectionTimeout)
      state1 <- node1.getState
      _ <- IO.println(s"   node-1 state after timeout: $state1")

      _ <- pumpBus(allNodes, mailboxes)

      leaderState <- node1.getState
      state2 <- node2.getState
      state3 <- node3.getState
      _ <- IO.println(s"   node-1 after votes: $leaderState")
      _ <- IO.println(s"   node-2 state:       $state2")
      _ <- IO.println(s"   node-3 state:       $state3\n")

      // ─────────────────────────────────────────────────────────────────────
      _ <- IO.println("Step 2: Submit & replicate transactions through leader")
      _ <- IO.println("─" * 50)

      // Transaction 1: Create accounts
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
      _ <- pumpBus(allNodes, mailboxes)
      _ <- IO.println(s"   Submitted index: $result1")

      // Transaction 2: Conditional CAS Transfer
      txn2 = Transaction(
        id = TxnId.generate,
        operations = List(
          TxnOperation.CompareAndSet("account:alice", Some("1000"), "800"),
          TxnOperation.CompareAndSet("account:bob", Some("500"), "700"),
          TxnOperation.Put("audit:transfer", "alice->bob:200")
        )
      )

      _ <- IO.println(s"\n   Submitting: Transfer 200 (txn ${txn2.id.value})")
      result2 <- node1.submitTransaction(txn2)
      _ <- pumpBus(allNodes, mailboxes)
      _ <- IO.println(s"   Submitted index: $result2")

      // Broadcast commit-carrying heartbeat so followers commit & apply up to commitIndex
      _ <- node1.processMessage(ids(0), HeartbeatTimeout)
      _ <- pumpBus(allNodes, mailboxes)

      // ─────────────────────────────────────────────────────────────────────
      _ <- IO.println(
        "\nStep 3: Verify RAFT log replication across all 3 nodes"
      )
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
      _ <- IO.println("\nStep 4: Verify converged state machine on all 3 nodes")
      _ <- IO.println("─" * 50)

      finalState1 <- fsm1.getAll
      finalState2 <- fsm2.getAll
      finalState3 <- fsm3.getAll
      _ <- IO.println(s"   State machine has ${finalState1.size} keys:")
      _ <- finalState1.toList.sorted.traverse_ { case (k, v) =>
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
    yield List(finalState1, finalState2, finalState3)
