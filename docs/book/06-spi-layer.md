# Chapter 6: The SPI Layer

*The Service Provider Interface (SPI) is the boundary between the pure Raft protocol logic and the messy real world of networks, disks, and clocks. This chapter explains each interface, its contract, the subtle requirements for correctness, and how to implement it. Getting these implementations right is essential — a correct protocol with a buggy transport or a non-durable store is a system waiting to fail.*

---

## What Is an SPI?

The term "Service Provider Interface" comes from the Java ecosystem (dating to the JDK 1.3 era), but the concept is universal: instead of the library depending on specific implementations (e.g., gRPC, RocksDB), it depends on **abstract interfaces** that you implement to match your infrastructure. This inverts the traditional dependency direction:

```
Traditional:   Library ──depends on──→ gRPC, RocksDB, etc.
SPI approach:  Library ──depends on──→ abstract traits ←──implemented by── You
```

There are five SPIs in this library:

```
┌──────────────────────────────────────────────────────────┐
│                     RaftNode[F]                          │
│                                                          │
│    RaftLogic (Pure) ────→ Effects ────→ SPI Calls        │
│                                                          │
├───────────────┬──────────────┬────────────┬──────────────┤
│  Transport    │   LogStore   │StableStore │ StateMachine │
│               │              │            │              │
│  send()       │  append()    │ setTerm()  │ apply()      │
│  broadcast()  │  get()       │ setVote()  │ snapshot()   │
│  sendBatch()  │  truncate()  │ getTerm()  │ restore()    │
│  receive      │  getRange()  │ getVote()  │              │
├───────────────┴──────────────┴────────────┴──────────────┤
│                     TimerService                         │
│  resetElectionTimer  · resetHeartbeatTimer  · cancelAll  │
│  electionTimeouts (stream)  · heartbeatTicks (stream)    │
└──────────────────────────────────────────────────────────┘
```

Each SPI is parameterized by `F[_]`, the effect type from Cats Effect (typically `IO`). This means SPI implementations can express asynchronous, concurrent, and potentially failing operations in a type-safe way.

Let's go through each one in detail.

## Transport: Node-to-Node Communication

The transport handles all communication between Raft nodes. It's the most infrastructure-specific SPI — what you implement depends entirely on your deployment model (in-process actors? gRPC over the network? TCP? HTTP/2?).

### The Raw Transport Interface

The base interface is generic over a wire format `Wire` — this could be `Array[Byte]`, a Protobuf message, a JSON string, or anything else:

```scala
trait Transport[F[_], Wire]:
  def send(to: NodeId, msg: Wire): F[Unit]
  def sendBatch(to: NodeId, msgs: Seq[Wire]): F[Unit]
  def broadcast(msg: Wire): F[Unit]
  def receive: Stream[F, (NodeId, Wire)]
  def localAddress: F[String]
  def shutdown: F[Unit]
```

Let's unpack each method:

- **`send(to, msg)`**: deliver a single message to a specific peer. If the peer is unreachable, this should complete without throwing — message loss is expected in distributed systems, and the Raft protocol handles retries internally (through the heartbeat/AppendEntries cycle).

- **`sendBatch(to, msgs)`**: deliver multiple messages to a single peer as a batch. This exists as an optimization — some transports (e.g., TCP with Nagle's algorithm disabled, or gRPC streaming) can send multiple messages more efficiently than individual `send` calls. A simple implementation can just loop over `msgs` and call `send` for each one.

- **`broadcast(msg)`**: deliver a message to **all** peers. Used for election requests and heartbeats. The implementation should send to all known peers; it does **not** need to send to the local node.

- **`receive`**: an FS2 `Stream` that produces incoming messages as `(senderId, wireMessage)` pairs. The runtime consumes this stream in its event loop.

- **`localAddress`**: returns the local node's network address. Used for logging and diagnostics; not required for protocol correctness.

- **`shutdown`**: cleanly close connections and release resources.

### The RaftTransport Interface

For convenience, the library also defines a transport that works directly with `RaftMessage` values instead of raw wire bytes:

```scala
trait RaftTransport[F[_]]:
  def send(to: NodeId, msg: RaftMessage): F[Unit]
  def sendBatch(to: NodeId, msgs: Seq[RaftMessage]): F[Unit]
  def broadcast(msg: RaftMessage): F[Unit]
  def receive: Stream[F, (NodeId, RaftMessage)]
  def localAddress: F[String]
  def shutdown: F[Unit]
```

### Bridging Raw ↔ Raft with Codecs

You can convert a raw `Transport[F, Wire]` into a `RaftTransport[F]` using a codec:

```scala
// If your codec is a pure function (encode/decode don't perform I/O)
val raftTransport = Transport.withCodec(rawTransport, myCodec)

// If your codec needs I/O (e.g., schema lookup, compression)
val raftTransport = Transport.withEffectfulCodec(rawTransport, myEffectfulCodec)
```

The library provides a JSON codec out of the box (`JsonCodec`), which is useful for debugging and testing — you can inspect messages as JSON strings — but you'll likely want a binary codec (Protobuf, MessagePack, etc.) for production throughput.

### Implementation Example: gRPC

Here's a sketch of what a production transport might look like:

```scala
class GrpcTransport[F[_]: Async](
  localId: NodeId,
  peers: Map[NodeId, ManagedChannel]
) extends Transport[F, Array[Byte]]:

  def send(to: NodeId, msg: Array[Byte]): F[Unit] =
    peers.get(to) match
      case Some(channel) => sendViaGrpc(channel, msg)
      case None          => Async[F].unit  // unknown peer, silently drop

  def sendBatch(to: NodeId, msgs: Seq[Array[Byte]]): F[Unit] =
    msgs.traverse_(send(to, _))  // or optimize with streaming RPCs

  def broadcast(msg: Array[Byte]): F[Unit] =
    peers.values.toList.traverse_(ch => sendViaGrpc(ch, msg))

  def receive: Stream[F, (NodeId, Array[Byte])] =
    Stream.fromQueueUnterminated(incomingQueue)

  // ...
```

> **Note — Transport reliability:** The transport does **not** need to guarantee message delivery. Lost messages are fine — Raft is designed to tolerate them (the leader will retry via heartbeats). The transport does not need to guarantee ordering either — Raft messages carry term and index information that allows the receiver to handle reordering. However, the transport should ideally be **best-effort**: try to deliver messages promptly, but don't hang forever if a peer is unreachable.

### For Testing: `InMemTransport`

The library includes a complete in-memory transport that delivers messages instantly between nodes in the same process:

```scala
val cluster: IO[Map[NodeId, InMemTransport[IO]]] =
  InMemTransport.createCluster[IO](List(n1, n2, n3))
```

This is what the test suite uses. Messages are delivered through bounded concurrent queues — no sockets, no serialization. You can also simulate message loss, delays, and partitions by wrapping the in-memory transport.

## LogStore: Persistent Log Storage

The log store provides durable, ordered storage for the replicated log. It's split into separate reader and writer traits for flexibility (some architectures might have multiple concurrent readers but a single writer).

### LogReader

```scala
trait LogReader[F[_]]:
  def get(index: LogIndex): F[Option[Log]]
  def getRange(start: LogIndex, end: LogIndex): F[Vector[Log]]
  def lastIndex: F[LogIndex]
  def termAt(index: LogIndex): F[Option[Term]]
  def isEmpty: F[Boolean]
```

- **`get(index)`**: retrieve a single log entry by index. Returns `None` if the index has been compacted away or is beyond the end of the log.
- **`getRange(start, end)`**: retrieve a contiguous range of entries (inclusive). Returns entries in index order. Used by the leader to build `AppendEntries` requests for lagging followers.
- **`lastIndex`**: return the index of the last entry in the log. If the log is empty, returns 0.
- **`termAt(index)`**: return the term of the entry at the given index. This is used for the `prevLogTerm` field in `AppendEntries` and for the election restriction check.
- **`isEmpty`**: return `true` if the log contains no entries.

### LogWriter

```scala
trait LogWriter[F[_]]:
  def append(entries: Seq[Log]): F[Unit]
  def truncateFrom(index: LogIndex): F[Unit]
  def clear: F[Unit]
```

- **`append(entries)`**: add entries to the end of the log. This must be **atomic** (all entries are appended or none) and **durable** (entries survive process restarts).
- **`truncateFrom(index)`**: remove all entries at or after the given index. This happens during log conflict resolution — when a follower's log diverges from the leader's, conflicting entries are truncated before appending the correct ones.
- **`clear`**: remove all entries. Used when restoring from a snapshot (the snapshot replaces the entire log).

### LogStore (Combined)

```scala
trait LogStore[F[_]] extends LogReader[F] with LogWriter[F]
```

### The Durability Contract

This is critical: **`append` must durably persist entries before completing**. In practice, this means calling `fsync()` (or the equivalent) on the underlying file. If `append` returns and then the process crashes, the appended entries **must** still be there when the process restarts.

Why? Because the leader might tell a client "your write succeeded" immediately after the entry is replicated to a majority. If any server in that majority subsequently loses the entry due to a non-durable write, a committed entry could be lost — violating Raft's safety guarantees.

> **Note — Batching fsync:** Calling `fsync()` after every single entry is slow. Production implementations typically batch multiple entries into a single `fsync()` — this is safe as long as the batch is synced before any of the entries are acknowledged to the protocol logic. The library's `BatchAppend` effect supports this pattern.

### Production Implementation Sketch: RocksDB

```scala
class RocksDBLogStore[F[_]: Sync](db: RocksDB) extends LogStore[F]:
  def append(entries: Seq[Log]): F[Unit] = Sync[F].delay {
    val batch = new WriteBatch()
    entries.foreach { e =>
      batch.put(indexToBytes(e.index), serializeLog(e))
    }
    db.write(new WriteOptions().setSync(true), batch)
    // setSync(true) ensures the write is durable (WAL fsync)
  }

  def get(index: LogIndex): F[Option[Log]] = Sync[F].delay {
    Option(db.get(indexToBytes(index))).map(deserializeLog)
  }

  def truncateFrom(index: LogIndex): F[Unit] = Sync[F].delay {
    val batch = new WriteBatch()
    val iter = db.newIterator()
    iter.seek(indexToBytes(index))
    while (iter.isValid) {
      batch.delete(iter.key())
      iter.next()
    }
    db.write(new WriteOptions().setSync(true), batch)
    iter.close()
  }

  // ...
```

### For Testing: `InMemLogStore`

```scala
val store: IO[InMemLogStore[IO]] = InMemLogStore[IO]()
```

The in-memory implementation stores entries in a `Ref[F, Vector[Log]]`. It doesn't provide durability (obviously), but it's perfect for protocol-level testing where you care about correctness of the algorithm, not persistence.

## StableStore: Hard State Persistence

The stable store persists the **hard state**: the node's current term and who it voted for. These two values are the minimum information a node needs to recover correctly after a crash.

```scala
trait StableStore[F[_]]:
  def currentTerm: F[Term]
  def setCurrentTerm(term: Term): F[Unit]
  def votedFor: F[Option[NodeId]]
  def setVotedFor(nodeId: Option[NodeId]): F[Unit]
```

### Why These Two Values Matter

**Current term**: If a node forgets its current term after a restart, it might accept messages from an old term, potentially re-electing a deposed leader or accepting stale entries.

**Voted for**: If a node forgets who it voted for, it might vote **twice** in the same term — once before the crash, once after restart. This could allow two candidates to each get a majority, violating the one-leader-per-term invariant. This is one of the most commonly misunderstood safety requirements in Raft implementations.

### The Durability Contract

Like `LogStore.append`, **all writes to the stable store must be durable (fsync'd) before the effect completes**. The node must not respond to any RPC until its hard state is safely on disk.

In practice, the hard state is just two values — a `Long` and an `Option[String]`. This makes the store extremely simple: two keys in a key-value store, two entries in a SQLite database, or even two bytes in a file. The important thing is the fsync, not the data structure.

> **Note — Combining LogStore and StableStore:** In a production deployment, you might implement both `LogStore` and `StableStore` on the same underlying storage engine (e.g., RocksDB with two column families, or SQLite with two tables). This allows you to batch the hard state update and log append into a single atomic, durable write — which is both safer and faster.

### For Testing: `InMemStableStore`

```scala
val stable: IO[InMemStableStore[IO]] = InMemStableStore[IO]()
```

## StateMachine: Your Application Logic

The state machine is where Raft meets your domain. Everything up to this point — elections, log replication, commit tracking — exists to ensure that every node applies the **same commands in the same order**. The state machine is *what* gets applied.

```scala
trait StateMachine[F[_], R]:
  def apply(log: Log): F[R]
  def snapshot: F[Array[Byte]]
  def restore(data: Array[Byte]): F[Unit]
```

The type parameter `R` is the **result type** — what the client sees after their command is applied. For a key-value store, `R` might be `Option[String]` (the value for GET operations, None for SET). For a counter, it might be `Long` (the new count).

### The Determinism Contract

This is the most important contract in the entire SPI layer:

**`apply(log)` must be a deterministic function.** Given the same log entry, it must produce the same result and the same state machine mutation on every node, every time.

This means:
- **No randomness**: don't use `Random`
- **No system clock**: don't use `System.currentTimeMillis()` or `Clock.realTime`
- **No external I/O**: don't call other services, databases, or APIs
- **No floating-point non-determinism**: be careful with `Double` operations that might differ across architectures (this is rare in practice, but worth noting)

Why does this matter? Because if two nodes apply the same entry and get different results, the replicated state machines diverge — silently and permanently. The whole point of consensus is lost.

> **Note — Non-deterministic requirements:** If your application needs time or randomness, the standard approach is to include those values in the **log entry data**. For example, instead of calling `System.currentTimeMillis()` inside `apply`, the client includes the timestamp when it creates the command: `SET x=1 AT 1706900000`. This way, every node uses the same timestamp.

### Implementation Contract

- **`apply(log)`** is called exactly once per committed entry, in log index order. The runtime guarantees this ordering; the implementation doesn't need to handle out-of-order entries.
- **`snapshot()`** captures the complete state machine state at the current point. This is used for log compaction (Chapter 3). The returned byte array must be a self-contained representation that `restore` can use to rebuild the state machine.
- **`restore(data)`** replaces the current state machine state with a previously captured snapshot. After `restore`, subsequent `apply` calls continue from the log entries after the snapshot's last included index.

### Example: A Key-Value Store FSM

```scala
class KvStoreFSM[F[_]: Sync](
  ref: Ref[F, Map[String, String]]
) extends StateMachine[F, Option[String]]:

  def apply(log: Log): F[Option[String]] =
    val command = parseCommand(new String(log.data))
    command match
      case Get(key) =>
        ref.get.map(_.get(key))
      case Set(key, value) =>
        ref.update(_ + (key -> value)).as(Some(value))
      case Delete(key) =>
        ref.update(_ - key).as(None)

  def snapshot: F[Array[Byte]] =
    ref.get.map(state => serialize(state))

  def restore(data: Array[Byte]): F[Unit] =
    ref.set(deserialize(data))
```

### Example: A Counter FSM

```scala
class CounterFSM[F[_]: Sync](ref: Ref[F, Long]) extends StateMachine[F, Long]:
  def apply(log: Log): F[Long] =
    val delta = new String(log.data).toLong
    ref.updateAndGet(_ + delta)

  def snapshot: F[Array[Byte]] =
    ref.get.map(v => v.toString.getBytes)

  def restore(data: Array[Byte]): F[Unit] =
    ref.set(new String(data).toLong)
```

## TimerService: Election and Heartbeat Scheduling

The timer service manages the two clocks that drive Raft's liveness:

```scala
trait TimerService[F[_]]:
  def resetElectionTimer: F[Unit]
  def resetHeartbeatTimer: F[Unit]
  def cancelAll: F[Unit]
  def electionTimeouts: Stream[F, Unit]
  def heartbeatTicks: Stream[F, Unit]
```

- **`resetElectionTimer`**: restart the election countdown from scratch. Called whenever the follower hears from a valid leader. The timeout duration is randomly chosen between `electionTimeoutMin` and `electionTimeoutMax` each time it's reset.
- **`resetHeartbeatTimer`**: restart the heartbeat countdown. Called after the leader sends heartbeats.
- **`cancelAll`**: stop all timers. Called during shutdown.
- **`electionTimeouts`**: a stream that emits a unit value each time the election timer fires.
- **`heartbeatTicks`**: a stream that emits a unit value each time the heartbeat timer fires.

The library provides `DefaultTimerService`, which uses `fs2.Stream.sleep` with randomized intervals. For most use cases, this is sufficient. A custom implementation might add jitter, adjust timeouts based on network conditions, or integrate with an existing scheduling system.

> **Note — Randomized timeouts are critical.** The election timer **must** use a different random duration each time it's reset. Deterministic timeouts would cause all followers to start elections at the same time after a leader failure, leading to persistent split votes and liveness failures. This is directly related to the FLP impossibility result discussed in Chapter 1.

## Factory Pattern: Resource-Safe Creation

Each SPI has an optional factory trait for resource-managed creation using Cats Effect's `Resource`:

```scala
trait LogStoreFactory[F[_]]:
  def create(path: String): Resource[F, LogStore[F]]

trait TransportFactory[F[_], Wire]:
  def create(config: TransportConfig): Resource[F, Transport[F, Wire]]
```

`Resource` ensures that when the Raft node shuts down, all SPIs are cleanly closed — database connections are released, file handles are closed, network listeners are stopped:

```scala
val program: Resource[IO, Unit] =
  for
    transport <- transportFactory.create(transportConfig)
    logStore  <- logStoreFactory.create("/data/raft-log")
    stable    <- stableStoreFactory.create("/data/raft-meta")
    // ... build and run the Raft node ...
  yield ()
  // Everything is automatically cleaned up when `program` completes
```

## Summary

| SPI | Purpose | Contract | In-Memory Implementation |
|-----|---------|----------|-------------------------|
| **Transport** | Node-to-node messaging | Best-effort delivery, no ordering guarantees needed | `InMemTransport` |
| **LogStore** | Replicated log persistence | Atomic and durable `append`, ordered `getRange` | `InMemLogStore` |
| **StableStore** | Term + vote persistence | Durable writes (fsync) for `setCurrentTerm` and `setVotedFor` | `InMemStableStore` |
| **StateMachine** | Command application | Deterministic `apply`, consistent `snapshot`/`restore` | You implement |
| **TimerService** | Election and heartbeat scheduling | Randomized election timeouts, regular heartbeat intervals | `DefaultTimerService` |

---

*Next: [Chapter 7 — Getting Started](07-getting-started.md) walks through building your first application with the library — from project setup to a running three-node cluster.*
