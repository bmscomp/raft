# Chapter 12: Case Study — Distributed Transactions

*Multi-key atomic operations with optimistic concurrency control. This case study builds a transactional store that guarantees serializable isolation — the strongest consistency level — using Raft for total ordering.*

---

## The Problem

A key-value store is useful, but real applications need **multi-key atomic operations**:

- Transfer $100 from account A to account B (both updates must succeed or neither does)
- Increment a counter only if a configuration flag is set
- Swap the values of two keys

Without transactions, concurrent operations can leave the system in an inconsistent state:

```
Client-1: Read balance(A)=500, Read balance(B)=300
Client-2: Read balance(A)=500, Read balance(B)=300

Client-1: Write balance(A)=400, Write balance(B)=400  (transfer 100)
Client-2: Write balance(A)=400, Write balance(B)=400  (transfer 100)

Result: A=400, B=400 — one transfer was lost!
```

## Design: Transactions on a Replicated Log

The key insight is that Raft's log already provides **total ordering**. If we encode each transaction as a single log entry, the state machine applies transactions one at a time, in order. This gives us **serializable isolation** for free.

```
┌─────────────────────────────────────────────────────────┐
│                Transaction Flow                          │
│                                                          │
│  Client                                                  │
│    │ submit(Transaction)                                 │
│    ▼                                                     │
│  Leader                                                  │
│    │ encode → Log entry                                  │
│    │ replicate via Raft                                   │
│    ▼                                                     │
│  Committed!                                              │
│    │                                                     │
│    ▼                                                     │
│  StateMachine.apply(log)                                 │
│    │ decode transaction                                  │
│    │ validate preconditions (CAS checks)                 │
│    │ if valid → apply operations → Committed              │
│    │ if conflict → ConflictDetected                       │
│    │ if error → Aborted                                   │
└─────────────────────────────────────────────────────────┘
```

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

## Step 2: Transaction Serialization

Each transaction is encoded as a single log entry:

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

## Step 3: The Transactional State Machine

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

### Precondition Validation

The `CompareAndSet` operation enables **optimistic concurrency control**:

```scala
private def validatePreconditions(
  txn: Transaction,
  currentState: Map[String, String]
): Option[TxnResult] =
  txn.operations.collectFirst {
    case TxnOperation.CompareAndSet(key, expected, _)
      if currentState.get(key) != expected =>
        TxnResult.ConflictDetected(txn.id, key, expected, currentState.get(key))
  }
```

If any CAS check fails, the entire transaction is rejected. The client can retry with updated values.

### Operation Application

```scala
private def applyOperations(ops: List[TxnOperation]): F[Unit] =
  ops.traverse_ {
    case TxnOperation.Put(k, v) =>
      state.update(_.updated(k, v))
    case TxnOperation.Delete(k) =>
      state.update(_ - k)
    case TxnOperation.CompareAndSet(_, _, newValue) =>
      // CAS already validated — just write the new value
      Sync[F].unit  // the put was already done inline
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
// Committed(txnId="abc123", version=1)
```

### Atomic Transfer with CAS

```scala
val transferTxn = Transaction(
  id = TxnId.generate,
  operations = List(
    // Read-modify-write with conflict detection
    TxnOperation.CompareAndSet("balance:A", Some("500"), "400"),
    TxnOperation.CompareAndSet("balance:B", Some("300"), "400")
  )
)
```

If another transaction modified `balance:A` between the read and the commit, CAS detects the conflict:

```scala
val result = store.apply(entry)
// ConflictDetected(txnId="def456", key="balance:A",
//   expected=Some("500"), actual=Some("450"))
```

### Conditional Updates

```scala
val conditionalTxn = Transaction(
  id = TxnId.generate,
  operations = List(
    TxnOperation.CompareAndSet("feature:dark-mode", Some("enabled"), "enabled"),
    TxnOperation.Put("theme", "dark"),
    TxnOperation.Put("background", "#1a1a2e")
  )
)
// Only applies if dark mode is currently enabled
```

## Consistency Guarantees

Because transactions are committed through Raft's replicated log:

| Guarantee | How It's Achieved |
|-----------|-------------------|
| **Atomicity** | Entire transaction is a single log entry |
| **Serializable isolation** | Raft's total ordering |
| **Linearizable writes** | Committed = replicated to majority |
| **Read-your-writes** | Client sees committed results immediately |
| **Conflict detection** | CAS preconditions checked at apply time |

## Error Handling and Retry

```scala
def submitWithRetry(
  store: TransactionalStore[IO],
  buildTxn: Map[String, String] => Transaction,
  maxRetries: Int = 3
): IO[TxnResult] =
  def attempt(remaining: Int): IO[TxnResult] =
    for
      currentState <- store.getAll
      txn          =  buildTxn(currentState)
      entry        =  Log.command(0, 1, Transaction.encode(txn))
      result       <- store.apply(entry)
      finalResult  <- result match
        case c: TxnResult.ConflictDetected if remaining > 0 =>
          IO.println(s"Conflict on ${c.key}, retrying...") *>
            IO.sleep(10.millis) *>
            attempt(remaining - 1)
        case other => IO.pure(other)
    yield finalResult

  attempt(maxRetries)
```

## Run the Examples

```bash
# Basic transactions with CAS
sbt "runMain examples.distributed.RaftTransactionExample"

# Full distributed transaction simulation
sbt "runMain examples.distributed.DistributedTransactionExample"
```

---

*This concludes the case study series. You now have the tools and patterns to build consistent, fault-tolerant distributed systems using the Functional RAFT Library.*
