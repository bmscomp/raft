# Chapter 12: Case Study — Distributed Transactions

*Multi-key atomic operations are the hardest problem in distributed data stores. This case study builds a transactional store with optimistic concurrency control that guarantees **serializable isolation** — the strongest consistency level — by leveraging Raft's total ordering. The same pattern powers the transaction engines in CockroachDB, TiKV, and Spanner.*

---

## The Problem

A key-value store (Chapter 9) lets you read and write individual keys atomically. But real applications need **multi-key atomic operations**:

- **Bank transfer**: debit account A and credit account B — both updates must succeed or neither does
- **Conditional update**: increment a counter only if a feature flag is enabled
- **Swap**: exchange the values of two keys atomically
- **Inventory check**: reserve an item only if the stock level is sufficient

Without transactions, concurrent operations on multiple keys can leave the system in an **inconsistent state**. Here's the classic example:

```
Time  Client-1                         Client-2
─────────────────────────────────────────────────────
  1   Read balance(A) = 500
  2                                    Read balance(A) = 500
  3                                    Read balance(B) = 300
  4   Read balance(B) = 300
  5   Write balance(A) = 400 }         
  6   Write balance(B) = 400 } transfer $100
  7                                    Write balance(A) = 400 }
  8                                    Write balance(B) = 400 } transfer $100

Result: A=400, B=400 (total $800 instead of $800)
Wait — the total is still $800? Let's check: before, A=500+B=300=$800.
After, A=400+B=400=$800. Seems fine?

No. Two transfers of $100 happened, but A only decreased by $100. One transfer was lost.
The correct result should be A=300, B=500 (two transfers: 500→400→300, 300→400→500).
```

The root cause is a **write-write conflict** — both clients read the same initial value and then overwrote each other's changes. This is the classic "lost update" anomaly.

## Design: Transactions on a Replicated Log

The key insight: Raft's log already provides **total ordering**. Every log entry has a unique index, and entries are applied in index order on every node. If we encode each transaction as a **single log entry**, the state machine applies transactions one at a time, sequentially. This gives us **serializable isolation** — the gold standard of transaction isolation levels — essentially for free.

```
┌───────────────────────────────────────────────────────────────┐
│                    Transaction Flow                           │
│                                                               │
│  Client                                                       │
│    │ submit(Transaction)                                      │
│    ▼                                                          │
│  Leader                                                       │
│    │ encode → single Log entry                                │
│    │ replicate via Raft (same as any other write)             │
│    ▼                                                          │
│  Committed (replicated to majority)                           │
│    │                                                          │
│    ▼                                                          │
│  StateMachine.apply(log)                                      │
│    │ decode transaction                                       │
│    │ validate preconditions (CAS checks against live state).  │
│    │ if all checks pass → apply all operations → Committed    │
│    │ if any check fails → reject entire txn → ConflictDetected│
│    │ if decode error → Aborted                                │ 
└──────────────────────────────────────────────────────────────┘
```

This design is often called **OCC** (Optimistic Concurrency Control). The "optimistic" part means: the client prepares the transaction based on its view of the state, submits it, and hopes no conflicting transaction committed in the meantime. If a conflict is detected at apply time, the transaction is rejected and the client retries. This is in contrast to **pessimistic** concurrency control (locking), which prevents conflicts by blocking concurrent access.

> **Note — Why OCC works well with Raft:** Raft already serializes all writes through a single leader. In this sequential execution model, conflict detection at apply time is simple and cheap — there's no need for lock tables, deadlock detection, or two-phase commit. The trade-off is that under high contention, many transactions may conflict and need to retry. For most workloads, though, contention is low and OCC performs excellently.

## Step 1: Transaction Types

```scala
opaque type TxnId = String
object TxnId:
  def apply(id: String): TxnId = id
  def generate: TxnId = UUID.randomUUID().toString.take(8)

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
  case ConflictDetected(txnId: TxnId, key: String,
    expected: Option[String], actual: Option[String])
```

The `CompareAndSet` operation is the heart of conflict detection. It says: "I expect key K to have value V. If it does, update it to V'. If it doesn't, reject the entire transaction." This is a standard **CAS** (Compare-And-Swap) primitive, lifted from hardware-level atomics to distributed state.

The `Increment` operation is a convenience for the common counter pattern. Unlike `CompareAndSet`, it's commutative — two concurrent `Increment(k, 1)` operations never conflict, because they don't depend on a specific expected value. Including commutative operations alongside CAS gives the best of both worlds: conflict detection when you need it, and conflict-free updates when you don't.

## Step 2: Transaction Serialization

Each transaction is encoded as a single log entry. This is critical — if a transaction were split across multiple entries, there'd be no atomicity guarantee (other entries could slip in between):

```scala
object Transaction:
  private val SEP = "\t"
  private val OP_SEP = "\n"

  def encode(txn: Transaction): Array[Byte] =
    val ops = txn.operations.map {
      case Put(k, v)              => s"PUT$SEP$k$SEP$v"
      case Delete(k)              => s"DEL$SEP$k"
      case CompareAndSet(k, e, n) => s"CAS$SEP$k$SEP${e.getOrElse("NULL")}$SEP$n"
      case Increment(k, d)        => s"INC$SEP$k$SEP$d"
    }.mkString(OP_SEP)
    s"TXN$SEP${txn.id}$SEP${txn.timestamp}$SEP$ops".getBytes

  def decode(data: Array[Byte]): Either[String, Transaction] = ...
```

> **Note — Entry size limits:** Since each transaction is a single log entry, very large transactions (thousands of operations) can produce large entries. Most Raft implementations have a practical entry size limit (etcd defaults to 1.5 MB). If your transactions can be large, consider splitting them into independent groups or using a two-phase approach where the transaction is stored externally and the log entry contains only a reference.

## Step 3: The Transactional State Machine

The state machine maintains four pieces of state:
- **`state`**: the current key-value map (the "database")
- **`versions`**: a per-key version counter for auditing
- **`txnLog`**: a history of transaction results
- **`globalVersion`**: a monotonically increasing transaction counter

```scala
class TransactionalStore[F[_]: Sync](
  state: Ref[F, Map[String, String]],
  versions: Ref[F, Map[String, Long]],
  txnLog: Ref[F, List[TxnResult]],
  globalVersion: Ref[F, Long]
) extends StateMachine[F, TxnResult]:

  def apply(log: Log): F[TxnResult] =
    Transaction.decode(log.data) match
      case Left(err) =>
        Sync[F].pure(TxnResult.Aborted(TxnId("?"), s"Decode error: $err"))
      case Right(txn) =>
        for
          current  <- state.get
          conflict =  validatePreconditions(txn, current)
          result   <- conflict match
            case Some(err) =>
              txnLog.update(_ :+ err).as(err)
            case None =>
              for
                _  <- applyOperations(txn.operations)
                v  <- globalVersion.updateAndGet(_ + 1)
                _  <- updateVersions(txn.operations, v)
                ok =  TxnResult.Committed(txn.id, v)
                _  <- txnLog.update(_ :+ ok)
              yield ok
        yield result
```

The flow is: **validate first, apply second.** If any precondition fails, nothing is written — the entire transaction is rejected atomically.

### Precondition Validation

The `CompareAndSet` operation performs the conflict check:

```scala
private def validatePreconditions(
  txn: Transaction,
  currentState: Map[String, String]
): Option[TxnResult] =
  txn.operations.collectFirst {
    case TxnOperation.CompareAndSet(key, expected, _)
      if currentState.get(key) != expected =>
        TxnResult.ConflictDetected(
          txn.id, key, expected, currentState.get(key)
        )
  }
```

The `collectFirst` short-circuits: as soon as one CAS check fails, the entire transaction is rejected. The conflict result includes both the expected and actual values, giving the client enough information to construct a retry.

### Operation Application

If all preconditions pass, operations are applied in order:

```scala
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
        val current = s.getOrElse(k, "0").toLong
        s.updated(k, (current + delta).toString)
      }
  }
```

## Step 4: Using Transactions

### Basic Write Transaction

```scala
val putTxn = Transaction(
  id = TxnId.generate,
  operations = List(
    TxnOperation.Put("user:1", "Alice"),
    TxnOperation.Put("user:2", "Bob")
  )
)

// Encode → append to Raft log → commit → apply
val entry = Log.command(1, 1, Transaction.encode(putTxn))
val result = store.apply(entry)
// → Committed(txnId="abc123", version=1)
```

Both puts succeed atomically. If the Raft commit fails (e.g., leader loses majority), neither put takes effect.

### Atomic Transfer with CAS

This is the solution to the lost-update problem from the beginning of this chapter:

```scala
val transferTxn = Transaction(
  id = TxnId.generate,
  operations = List(
    // Both checks must pass for the transaction to commit
    TxnOperation.CompareAndSet("balance:A", Some("500"), "400"),
    TxnOperation.CompareAndSet("balance:B", Some("300"), "400")
  )
)
```

If another transaction modified `balance:A` between the client's read and this transaction's commit, the CAS detects the conflict:

```scala
val result = store.apply(entry)
// → ConflictDetected(txnId="def456", key="balance:A",
//     expected=Some("500"), actual=Some("450"))
```

The client now knows exactly what happened — some other transaction changed `balance:A` to 450. The client re-reads the current values, recomputes the transfer, and retries.

### Conditional Updates

CAS enables powerful conditional logic:

```scala
val conditionalTxn = Transaction(
  id = TxnId.generate,
  operations = List(
    // Guard: only proceed if dark mode is currently enabled
    TxnOperation.CompareAndSet("feature:dark-mode", Some("enabled"), "enabled"),
    // If the guard passes, apply these changes
    TxnOperation.Put("theme", "dark"),
    TxnOperation.Put("background", "#1a1a2e")
  )
)
// The CAS on "feature:dark-mode" acts as a read-check: if the value isn't
// "enabled", the entire transaction is rejected. The "new value" is the same
// as the expected value — it's a no-op write that still triggers validation.
```

## Consistency Guarantees

Because transactions are committed through Raft's replicated log, we get strong guarantees:

| Property | How It's Achieved |
|----------|------------------|
| **Atomicity** | Entire transaction is a single log entry — all-or-nothing |
| **Serializable isolation** | Raft's total ordering means transactions are applied one at a time, in log order |
| **Linearizable writes** | A committed transaction is replicated to a majority |
| **Read-your-writes** | After receiving `Committed`, the client's next read sees the new values |
| **Conflict detection** | CAS preconditions checked at apply time against current state |
| **Durability** | Committed entries survive any minority of node failures |

> **Note — Serializability vs. Linearizability:** These terms are often confused. **Serializability** means transactions appear to execute in some serial order. **Linearizability** means each individual operation appears to take effect at some point between its invocation and response. Our store provides **strict serializability** — the strongest possible consistency guarantee — because Raft's log defines both the serial order (serializability) and a real-time ordering (linearizability).

## Error Handling and Retry

Conflict detection with retry is the standard pattern for OCC:

```scala
def submitWithRetry(
  store: TransactionalStore[IO],
  buildTxn: Map[String, String] => Transaction,
  maxRetries: Int = 3
): IO[TxnResult] =
  def attempt(remaining: Int): IO[TxnResult] =
    for
      currentState <- store.getAll         // read current state
      txn          =  buildTxn(currentState) // build txn based on current values
      entry        =  Log.command(0, 1, Transaction.encode(txn))
      result       <- store.apply(entry)
      finalResult  <- result match
        case c: TxnResult.ConflictDetected if remaining > 0 =>
          IO.println(s"Conflict on ${c.key}, retrying (${remaining} left)...") *>
            IO.sleep(10.millis) *>   // brief backoff before retry
            attempt(remaining - 1)
        case other => IO.pure(other)
    yield finalResult

  attempt(maxRetries)
```

The `buildTxn` function takes the current state and builds a transaction based on the latest values. On conflict, the function is called again with the updated state, producing a new transaction with the correct expected values.

> **Note — Exponential backoff:** The `10.millis` sleep is simple but naive. In production, use exponential backoff with jitter to avoid thundering herd problems when many clients conflict on the same keys simultaneously. A good formula: `min(maxBackoff, initialBackoff * 2^attempt) * (0.5 + random(0.5))`.

## Run the Examples

```bash
# Basic transactions with CAS conflict detection
sbt "runMain examples.distributed.RaftTransactionExample"

# Full distributed transaction simulation across a 3-node cluster
sbt "runMain examples.distributed.DistributedTransactionExample"
```

---

*Next: [Chapter 13 — State of the Art: Raft Libraries Compared](13-state-of-the-art.md) surveys the major Raft implementations across Go, Rust, Java, and Scala, comparing architectures, feature sets, and trade-offs.*
