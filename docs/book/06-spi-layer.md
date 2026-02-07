# Chapter 6: The SPI Layer

*The Service Provider Interface (SPI) is how you connect the pure Raft logic to your infrastructure. This chapter details each interface, its contract, and how to implement it.*

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────┐
│                   RaftNode[F]                        │
│                                                      │
│   RaftLogic (Pure) ────> Effects ────> SPI Calls     │
│                                                      │
├─────────────┬───────────┬───────────┬────────────────┤
│  Transport  │ LogStore  │StableStore│ StateMachine   │
│             │           │           │                │
│  send()     │ append()  │ setTerm() │ apply()        │
│  broadcast()│ get()     │ setVote() │ snapshot()     │
│  sendBatch()│ truncate()│           │ restore()      │
│  receive    │ getRange()│           │                │
└─────────────┴───────────┴───────────┴────────────────┘
```

## Transport

The transport handles all communication between nodes.

### Raw Transport

The base interface works with a generic wire format:

```scala
trait Transport[F[_], Wire]:
  def send(to: NodeId, msg: Wire): F[Unit]
  def sendBatch(to: NodeId, msgs: Seq[Wire]): F[Unit]
  def broadcast(msg: Wire): F[Unit]
  def receive: Stream[F, (NodeId, Wire)]
  def localAddress: F[String]
  def shutdown: F[Unit]
```

### RaftTransport

The high-level transport works directly with `RaftMessage`:

```scala
trait RaftTransport[F[_]]:
  def send(to: NodeId, msg: RaftMessage): F[Unit]
  def sendBatch(to: NodeId, msgs: Seq[RaftMessage]): F[Unit]
  def broadcast(msg: RaftMessage): F[Unit]
  def receive: Stream[F, (NodeId, RaftMessage)]
  def localAddress: F[String]
  def shutdown: F[Unit]
```

### Bridging with Codecs

The `Transport` companion provides adapters that wrap a raw transport with a codec:

```scala
// Synchronous codec (pure encode/decode)
val raftTransport = Transport.withCodec(rawTransport, myCodec)

// Effectful codec (encode/decode may perform I/O)
val raftTransport = Transport.withEffectfulCodec(rawTransport, myEffectfulCodec)
```

### Implementing a Transport

For production, you'd implement `Transport[F, Array[Byte]]` using gRPC, Netty, or HTTP:

```scala
class GrpcTransport[F[_]: Async](
  localId: NodeId,
  peers: Map[NodeId, ManagedChannel]
) extends Transport[F, Array[Byte]]:

  def send(to: NodeId, msg: Array[Byte]): F[Unit] =
    peers.get(to) match
      case Some(channel) => sendViaGrpc(channel, msg)
      case None          => Async[F].unit  // unknown peer

  def sendBatch(to: NodeId, msgs: Seq[Array[Byte]]): F[Unit] =
    msgs.traverse_(send(to, _))  // or optimize with streaming

  def broadcast(msg: Array[Byte]): F[Unit] =
    peers.values.toList.traverse_(ch => sendViaGrpc(ch, msg))

  def receive: Stream[F, (NodeId, Array[Byte])] =
    Stream.fromQueueUnterminated(incomingQueue)

  // ...
```

### For Testing: `InMemTransport`

The library provides a fully wired in-memory transport:

```scala
// Create a 3-node cluster with instant message delivery
val cluster: IO[Map[NodeId, InMemTransport[IO]]] =
  InMemTransport.createCluster[IO](List(n1, n2, n3))
```

## LogStore

Persistent storage for the replicated log. Split into reader and writer interfaces for flexibility.

### LogReader

```scala
trait LogReader[F[_]]:
  def get(index: LogIndex): F[Option[Log]]
  def getRange(start: LogIndex, end: LogIndex): F[Vector[Log]]
  def lastIndex: F[LogIndex]
  def termAt(index: LogIndex): F[Option[Term]]
  def isEmpty: F[Boolean]
```

### LogWriter

```scala
trait LogWriter[F[_]]:
  def append(entries: Seq[Log]): F[Unit]
  def truncateFrom(index: LogIndex): F[Unit]
  def clear: F[Unit]
```

### LogStore (Combined)

```scala
trait LogStore[F[_]] extends LogReader[F] with LogWriter[F]
```

### Implementation Contract

- `append` must be **atomic** — all entries are written or none
- `append` must be **durable** — entries survive process restarts
- `truncateFrom` removes all entries at or after the given index
- `getRange` returns entries in index order

### For Testing: `InMemLogStore`

```scala
val store: IO[InMemLogStore[IO]] = InMemLogStore[IO]()
```

### Production Implementation Sketch

```scala
class RocksDBLogStore[F[_]: Sync](db: RocksDB) extends LogStore[F]:
  def append(entries: Seq[Log]): F[Unit] = Sync[F].delay {
    val batch = new WriteBatch()
    entries.foreach { e =>
      batch.put(indexToBytes(e.index), serializeLog(e))
    }
    db.write(new WriteOptions().setSync(true), batch)
  }
  // ...
```

## StableStore

Durable storage for the **hard state**: the current term and the node this server voted for.

```scala
trait StableStore[F[_]]:
  def currentTerm: F[Term]
  def setCurrentTerm(term: Term): F[Unit]
  def votedFor: F[Option[NodeId]]
  def setVotedFor(nodeId: Option[NodeId]): F[Unit]
```

### Implementation Contract

- Writes must be **fsync'd** — data must be on disk before the effect completes
- `currentTerm` and `votedFor` must survive process restarts
- These are called on every term change and vote, so they must be fast

In practice, these two values can be stored as two keys in a simple key-value store (e.g., a single file, a SQLite database, or a RocksDB column family).

### For Testing: `InMemStableStore`

```scala
val stable: IO[InMemStableStore[IO]] = InMemStableStore[IO]()
```

## StateMachine

Your application logic. This is where Raft meets your domain:

```scala
trait StateMachine[F[_], R]:
  def apply(log: Log): F[R]
  def snapshot: F[Array[Byte]]
  def restore(data: Array[Byte]): F[Unit]
```

### Implementation Contract

- `apply` is called **exactly once** per committed entry, in **log index order**
- `apply` must be **deterministic** — same entry → same result on every node
- `snapshot` captures the complete state machine state at the current point
- `restore` replaces the state machine with a previously captured snapshot

### Example: A Counter

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

## TimerService

Manages election and heartbeat timers:

```scala
trait TimerService[F[_]]:
  def resetElectionTimer: F[Unit]
  def resetHeartbeatTimer: F[Unit]
  def cancelAll: F[Unit]
  def electionTimeouts: Stream[F, Unit]
  def heartbeatTicks: Stream[F, Unit]
```

The library includes `DefaultTimerService` which uses `fs2.Stream.sleep` with randomized intervals.

## Factory Pattern

Each SPI has an optional factory for resource-managed creation:

```scala
trait LogStoreFactory[F[_]]:
  def create(path: String): Resource[F, LogStore[F]]

trait TransportFactory[F[_], Wire]:
  def create(config: TransportConfig): Resource[F, Transport[F, Wire]]
```

This integrates with Cats Effect's `Resource` for safe lifecycle management:

```scala
val program: Resource[IO, Unit] =
  for
    transport <- transportFactory.create(transportConfig)
    logStore  <- logStoreFactory.create("/data/raft-log")
    // ... use them ...
  yield ()  // automatically cleaned up when done
```

## Summary

| SPI | Purpose | In-Memory Impl |
|-----|---------|---------------|
| `Transport` | Node-to-node messaging | `InMemTransport` |
| `LogStore` | Log persistence | `InMemLogStore` |
| `StableStore` | Term + vote persistence | `InMemStableStore` |
| `StateMachine` | Command application | You implement |
| `TimerService` | Election/heartbeat timers | `DefaultTimerService` |

---

*Next: [Chapter 7 — Getting Started](07-getting-started.md) walks through building your first application with the library.*
