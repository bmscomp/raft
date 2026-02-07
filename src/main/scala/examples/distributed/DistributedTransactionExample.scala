package examples.distributed

import cats.effect.*
import cats.syntax.all.*
import java.util.UUID
import scala.concurrent.duration.*

import raft.state.*
import raft.spi.StateMachine

/** Distributed transactions with RAFT.
  *
  * This example demonstrates how to implement distributed transactions using
  * RAFT consensus. We use a replicated log to ensure all nodes agree on the
  * transaction order and outcome.
  *
  * RAFT provides:
  *   - Total ordering of all transactions across the cluster
  *   - Durability: committed transactions survive failures
  *   - Consistency: all nodes apply transactions in the same order
  *   - Atomicity: transaction is either fully applied or not at all
  *
  * The transaction model supports:
  *   - '''READ''': Read current values (can be served by any node with lease)
  *   - '''WRITE''': Modify multiple keys atomically
  *   - '''COMPARE-AND-SET''': Conditional writes with optimistic concurrency
  *
  * The flow:
  *   1. Client sends transaction to leader
  *   1. Leader validates preconditions (for CAS operations)
  *   1. Transaction is appended to RAFT log
  *   1. Once committed (majority acknowledged), transaction is applied
  *   1. Leader responds to client with result
  *
  * Consistency guarantees:
  *   - ''Serializable isolation'': transactions appear to execute sequentially
  *   - ''Linearizable writes'': once committed, visible to all subsequent reads
  *   - ''Read-your-writes'': client sees its own committed transactions
  *
  * @see
  *   [[raft.spi.StateMachine]] for the state machine SPI
  */
object DistributedTransactionExample extends IOApp.Simple:

  /** Unique transaction identifier used for tracking and idempotency. */
  case class TxnId(value: String)
  object TxnId:
    def generate: TxnId = TxnId(UUID.randomUUID().toString.take(8))

  /** A single operation within a transaction. */
  enum TxnOperation:
    /** Set a key to a value unconditionally. */
    case Put(key: String, value: String)

    /** Delete a key. */
    case Delete(key: String)

    /** Conditional set: only succeeds if current value matches expected. */
    case CompareAndSet(key: String, expected: Option[String], newValue: String)

    /** Increment a numeric key atomically. */
    case Increment(key: String, delta: Long)

  /** A complete transaction with multiple operations. */
  case class Transaction(
      id: TxnId,
      operations: List[TxnOperation],
      timestamp: Long = System.currentTimeMillis()
  )

  /** Result of transaction execution. */
  enum TxnResult:
    case Committed(txnId: TxnId, version: Long)
    case Aborted(txnId: TxnId, reason: String)
    case ConflictDetected(
        txnId: TxnId,
        key: String,
        expected: Option[String],
        actual: Option[String]
    )

  /** Serialization codec for encoding/decoding transactions to/from RAFT log
    * entries.
    */

  object Transaction:
    private val FIELD_SEP = "\t" // Tab as field separator (safe for values)
    private val OP_SEP = "\n" // Newline as operation separator

    /** Encode transaction for RAFT log entry. */
    def encode(txn: Transaction): Array[Byte] =
      val ops = txn.operations
        .map {
          case TxnOperation.Put(k, v) => s"PUT$FIELD_SEP$k$FIELD_SEP$v"
          case TxnOperation.Delete(k) => s"DEL$FIELD_SEP$k"
          case TxnOperation.CompareAndSet(k, e, n) =>
            s"CAS$FIELD_SEP$k$FIELD_SEP${e.getOrElse("NULL")}$FIELD_SEP$n"
          case TxnOperation.Increment(k, d) => s"INC$FIELD_SEP$k$FIELD_SEP$d"
        }
        .mkString(OP_SEP)
      s"TXN$FIELD_SEP${txn.id.value}$FIELD_SEP${txn.timestamp}$FIELD_SEP$ops".getBytes

    /** Decode transaction from RAFT log entry. */
    def decode(data: Array[Byte]): Either[String, Transaction] =
      val str = new String(data)
      str.split(FIELD_SEP, 4).toList match
        case "TXN" :: id :: ts :: opsStr :: Nil =>
          parseOperations(opsStr).map { ops =>
            Transaction(TxnId(id), ops, ts.toLong)
          }
        case "TXN" :: id :: ts :: Nil =>
          Right(Transaction(TxnId(id), Nil, ts.toLong))
        case _ => Left(s"Invalid transaction format")

    private def parseOperations(
        str: String
    ): Either[String, List[TxnOperation]] =
      if str.isEmpty then Right(Nil)
      else
        str.split(OP_SEP).toList.traverse { opStr =>
          opStr.split(FIELD_SEP).toList match
            case "PUT" :: k :: v :: Nil      => Right(TxnOperation.Put(k, v))
            case "DEL" :: k :: Nil           => Right(TxnOperation.Delete(k))
            case "CAS" :: k :: e :: n :: Nil =>
              val expected = if e == "NULL" then None else Some(e)
              Right(TxnOperation.CompareAndSet(k, expected, n))
            case "INC" :: k :: d :: Nil =>
              Right(TxnOperation.Increment(k, d.toLong))
            case _ => Left(s"Invalid operation: $opStr")
        }

  /** State machine that executes transactions atomically.
    *
    * Each transaction is validated and applied as an atomic unit. Failed
    * preconditions (CAS mismatches) abort the entire transaction.
    */
  class TransactionalStore[F[_]: Sync](
      state: Ref[F, Map[String, String]],
      versions: Ref[F, Map[String, Long]],
      txnLog: Ref[F, List[TxnResult]],
      globalVersion: Ref[F, Long]
  ) extends StateMachine[F, TxnResult]:

    def apply(log: Log): F[TxnResult] =
      Transaction.decode(log.data) match
        case Left(err) =>
          Sync[F].pure(
            TxnResult.Aborted(TxnId("unknown"), s"Decode error: $err")
          )

        case Right(txn) =>
          executeTransaction(txn, log.index)

    /** Execute transaction with precondition checking. */
    private def executeTransaction(
        txn: Transaction,
        logIndex: Long
    ): F[TxnResult] =
      for
        // Phase 1: Validate all preconditions
        currentState <- state.get
        validationResult = validatePreconditions(txn, currentState)

        result <- validationResult match
          case Left(conflict) =>
            // Abort: precondition failed
            val result = conflict
            txnLog.update(_ :+ result).as(result)

          case Right(()) =>
            // Phase 2: Apply all operations atomically
            for
              newVersion <- globalVersion.updateAndGet(_ + 1)
              _ <- applyOperations(txn.operations)
              _ <- updateVersions(txn.operations, newVersion)
              result = TxnResult.Committed(txn.id, newVersion)
              _ <- txnLog.update(_ :+ result)
            yield result
      yield result

    /** Check all CAS preconditions before applying. */
    private def validatePreconditions(
        txn: Transaction,
        currentState: Map[String, String]
    ): Either[TxnResult, Unit] =
      txn.operations
        .collectFirst {
          case TxnOperation.CompareAndSet(key, expected, _)
              if currentState.get(key) != expected =>
            TxnResult.ConflictDetected(
              txn.id,
              key,
              expected,
              currentState.get(key)
            )
        }
        .toLeft(())

    /** Apply all operations to state. */
    private def applyOperations(ops: List[TxnOperation]): F[Unit] =
      ops.traverse_ {
        case TxnOperation.Put(k, v) =>
          state.update(_.updated(k, v))

        case TxnOperation.Delete(k) =>
          state.update(_ - k)

        case TxnOperation.CompareAndSet(k, _, newValue) =>
          state.update(_.updated(k, newValue))

        case TxnOperation.Increment(k, delta) =>
          state.update { s =>
            val current = s.get(k).flatMap(_.toLongOption).getOrElse(0L)
            s.updated(k, (current + delta).toString)
          }
      }

    /** Update version numbers for modified keys. */
    private def updateVersions(
        ops: List[TxnOperation],
        version: Long
    ): F[Unit] =
      val keys = ops.map {
        case TxnOperation.Put(k, _)              => k
        case TxnOperation.Delete(k)              => k
        case TxnOperation.CompareAndSet(k, _, _) => k
        case TxnOperation.Increment(k, _)        => k
      }
      versions.update(v =>
        keys.foldLeft(v)((acc, k) => acc.updated(k, version))
      )

    // Query methods (for reads - can be served without RAFT)
    def get(key: String): F[Option[String]] = state.get.map(_.get(key))
    def getVersion(key: String): F[Option[Long]] = versions.get.map(_.get(key))
    def getAll: F[Map[String, String]] = state.get
    def getTransactionLog: F[List[TxnResult]] = txnLog.get

    def snapshot: F[Array[Byte]] =
      state.get.map { m =>
        m.map { case (k, v) => s"$k=$v" }.mkString("\n").getBytes
      }

    def restore(data: Array[Byte]): F[Unit] =
      val entries = new String(data)
        .split("\n")
        .flatMap { line =>
          line.split("=", 2) match
            case Array(k, v) => Some(k -> v)
            case _           => None
        }
        .toMap
      state.set(entries)

  object TransactionalStore:
    def apply[F[_]: Sync]: F[TransactionalStore[F]] =
      for
        state <- Ref.of[F, Map[String, String]](Map.empty)
        versions <- Ref.of[F, Map[String, Long]](Map.empty)
        txnLog <- Ref.of[F, List[TxnResult]](Nil)
        version <- Ref.of[F, Long](0L)
      yield new TransactionalStore[F](state, versions, txnLog, version)

  def run: IO[Unit] =
    for
      _ <- IO.println("═" * 70)
      _ <- IO.println("  DISTRIBUTED TRANSACTIONS WITH RAFT")
      _ <- IO.println("═" * 70)
      _ <- IO.println("")
      _ <- runDemo
    yield ()

  private def runDemo: IO[Unit] =
    for
      store <- TransactionalStore[IO]

      // ─────────────────────────────────────────────────────────────────────
      _ <- IO.println("Example 1: Simple Multi-Key Write Transaction")
      _ <- IO.println("─" * 50)
      _ <- IO.println("Transfer $100 from account A to account B atomically.\n")

      txn1 = Transaction(
        id = TxnId.generate,
        operations = List(
          TxnOperation.Put("account:A", "900"), // Debit A
          TxnOperation.Put("account:B", "1100"), // Credit B
          TxnOperation.Put("txn:lastTransfer", "A->B:100")
        )
      )

      _ <- IO.println(s"   Transaction ${txn1.id.value}:")
      _ <- IO.println(s"   - Set account:A = 900")
      _ <- IO.println(s"   - Set account:B = 1100")
      _ <- IO.println(s"   - Set txn:lastTransfer = A->B:100")

      // Simulate RAFT committing this transaction
      log1 = Log.command(1, 1, Transaction.encode(txn1))
      result1 <- store.apply(log1)

      _ <- IO.println(s"\n   Result: $result1")

      accountA <- store.get("account:A")
      accountB <- store.get("account:B")
      _ <- IO.println(
        s"   State: A=${accountA.getOrElse("?")} B=${accountB.getOrElse("?")}\n"
      )

      // ─────────────────────────────────────────────────────────────────────
      _ <- IO.println("Example 2: Compare-And-Set (Optimistic Locking)")
      _ <- IO.println("─" * 50)
      _ <- IO.println("Update account A only if it has expected value.\n")

      // This should succeed - expected value matches
      txn2 = Transaction(
        id = TxnId.generate,
        operations = List(
          TxnOperation.CompareAndSet("account:A", Some("900"), "850")
        )
      )

      _ <- IO.println(s"   Transaction ${txn2.id.value}:")
      _ <- IO.println(s"   - CAS account:A: 900 -> 850")

      log2 = Log.command(2, 1, Transaction.encode(txn2))
      result2 <- store.apply(log2)

      _ <- IO.println(s"   Result: $result2")

      accountA2 <- store.get("account:A")
      _ <- IO.println(s"   account:A = ${accountA2.getOrElse("?")}\n")

      // ─────────────────────────────────────────────────────────────────────
      _ <- IO.println("Example 3: CAS Conflict Detection")
      _ <- IO.println("─" * 50)
      _ <- IO.println("Try to update with stale expected value → conflict!\n")

      // This should FAIL - expected value is stale
      txn3 = Transaction(
        id = TxnId.generate,
        operations = List(
          TxnOperation.CompareAndSet(
            "account:A",
            Some("900"),
            "800"
          ) // 900 is stale!
        )
      )

      _ <- IO.println(s"   Transaction ${txn3.id.value}:")
      _ <- IO.println(s"   - CAS account:A: 900 -> 800 (but current is 850!)")

      log3 = Log.command(3, 1, Transaction.encode(txn3))
      result3 <- store.apply(log3)

      _ <- IO.println(s"   Result: $result3")

      accountA3 <- store.get("account:A")
      _ <- IO.println(
        s"   account:A = ${accountA3.getOrElse("?")} (unchanged)\n"
      )

      // ─────────────────────────────────────────────────────────────────────
      _ <- IO.println("Example 4: Atomic Counter Increment")
      _ <- IO.println("─" * 50)
      _ <- IO.println(
        "Increment a counter atomically (no read-modify-write race).\n"
      )

      // Initialize and increment counter
      txn4a = Transaction(
        id = TxnId.generate,
        operations = List(TxnOperation.Put("counter:visits", "0"))
      )
      log4a = Log.command(4, 1, Transaction.encode(txn4a))
      _ <- store.apply(log4a)

      txn4b = Transaction(
        id = TxnId.generate,
        operations = List(TxnOperation.Increment("counter:visits", 1))
      )
      log4b = Log.command(5, 1, Transaction.encode(txn4b))
      _ <- store.apply(log4b)

      txn4c = Transaction(
        id = TxnId.generate,
        operations = List(TxnOperation.Increment("counter:visits", 5))
      )
      log4c = Log.command(6, 1, Transaction.encode(txn4c))
      _ <- store.apply(log4c)

      counter <- store.get("counter:visits")
      _ <- IO.println(s"   After 3 transactions (init=0, +1, +5):")
      _ <- IO.println(s"   counter:visits = ${counter.getOrElse("?")}\n")

      // ─────────────────────────────────────────────────────────────────────
      _ <- IO.println("Example 5: Multi-Operation Atomic Transaction")
      _ <- IO.println("─" * 50)
      _ <- IO.println(
        "Complex transaction: create user + set permissions + log event.\n"
      )

      txn5 = Transaction(
        id = TxnId.generate,
        operations = List(
          TxnOperation
            .Put("user:alice", "{name:Alice,email:alice@example.com}"),
          TxnOperation.Put("perms:alice", "read,write,admin"),
          TxnOperation.Put("audit:last", "created user alice"),
          TxnOperation.Increment("stats:userCount", 1)
        )
      )

      _ <- IO.println(s"   Transaction ${txn5.id.value}:")
      _ <- IO.println(s"   - Create user:alice")
      _ <- IO.println(s"   - Set perms:alice = read,write,admin")
      _ <- IO.println(s"   - Log audit:last = created user alice")
      _ <- IO.println(s"   - Increment stats:userCount")

      log5 = Log.command(7, 1, Transaction.encode(txn5))
      result5 <- store.apply(log5)

      _ <- IO.println(s"\n   Result: $result5")

      allState <- store.getAll
      _ <- IO.println(s"\n   Final state (${allState.size} keys):")
      _ <- allState.toList.sorted.traverse_ { case (k, v) =>
        IO.println(s"      $k = $v")
      }

      // ─────────────────────────────────────────────────────────────────────
      _ <- IO.println("\n" + "═" * 70)
      _ <- IO.println("  TRANSACTION LOG (all committed/aborted transactions)")
      _ <- IO.println("═" * 70)

      txnLog <- store.getTransactionLog
      _ <- txnLog.traverse_ { result =>
        result match
          case TxnResult.Committed(id, ver) =>
            IO.println(s"   ✓ ${id.value} COMMITTED at version $ver")
          case TxnResult.Aborted(id, reason) =>
            IO.println(s"   ✗ ${id.value} ABORTED: $reason")
          case TxnResult.ConflictDetected(id, key, exp, act) =>
            IO.println(
              s"   ⚠ ${id.value} CONFLICT on $key: expected=$exp, actual=$act"
            )
      }

      _ <- IO.println("\n" + "═" * 70)
      _ <- IO.println("  HOW THIS WORKS WITH RAFT")
      _ <- IO.println("═" * 70)
      _ <- IO.println("""
   1. CLIENT sends Transaction to LEADER
   
   2. LEADER validates transaction locally (optional pre-check)
   
   3. LEADER appends Transaction.encode(txn) to RAFT log
   
   4. RAFT replicates to FOLLOWERS (log matching ensures consistency)
   
   5. Once MAJORITY acknowledges:
      - commitIndex advances
      - TransactionalStore.apply(log) executes on all nodes
      - All nodes get identical state!
   
   6. LEADER responds to CLIENT with TxnResult
   
   KEY GUARANTEES:
   - Serializable: all transactions ordered by RAFT log index
   - Atomic: transaction applies fully or not at all
   - Durable: committed transactions survive failures
   - Consistent: all nodes apply same transactions in same order
""")
      _ <- IO.println("═" * 70)
      _ <- IO.println("\n✅ Distributed transaction example complete!")
    yield ()
