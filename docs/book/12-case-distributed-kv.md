# Chapter 12: Case Study — Distributed Key-Value Store

*The most common application of consensus is a replicated data store that survives server failures and serves consistent reads. This case study builds a complete distributed key-value store from scratch, step by step — from defining the command protocol through linearizable reads and snapshot-based recovery. By the end, you'll have a pattern you can apply to any stateful distributed system.*

---

## The Problem

You need a key-value store with these guarantees:

- **Durability**: data survives individual server failures. If a server crashes and restarts, it recovers its state without data loss.
- **Consistency**: every read returns the most recently written value (linearizability). No client ever sees stale data after a successful write.
- **Availability**: the system serves requests as long as a **majority** of servers are running. A 3-node cluster tolerates 1 failure; a 5-node cluster tolerates 2.

Why can't you just use Redis or any other single-server store? Because a single server is a single point of failure. If it crashes, your service is down. If its disk fails, your data is gone.

Primary-replica setups (like Redis with AOF replication) are better, but they have a fundamental gap: replication is **asynchronous**. If the primary crashes after accepting a write but before replicating it to a replica, that write is lost. This is called the "acknowledged but lost" problem, and it violates linearizability.

With Raft, writes are replicated to a **majority before being acknowledged**. If any server in the majority survives, the write survives. This is the core guarantee that makes consensus-based stores fundamentally different from primary-replica setups.

> **Note — Real-world systems built on this pattern:** etcd (Kubernetes' configuration store), CockroachDB, TiKV (the storage layer of TiDB), and Consul all use Raft-replicated key-value stores as their foundation. The pattern we build in this chapter is a simplified version of what powers these production systems.

## Design Overview

The architecture has three layers:

```
┌──────────────────────────────────────────────────────────────┐
│                       Client API                             │
│   put(key, value) → "OK"                                     │
│   get(key)        → value | "(not found)"                    │
│   delete(key)     → "OK"                                     │
├──────────────────────────────────────────────────────────────┤
│                    Command Encoding                          │
│   KVCommand ──encode──→ Array[Byte] ──→ Log entry            │
│   Array[Byte] ──decode──→ KVCommand                          │
│                                                              │
│   Commands are opaque bytes to the Raft layer — it replicates│
│   them without inspecting the contents.                      │
├──────────────────────────────────────────────────────────────┤
│                   KVStateMachine                             │
│   implements StateMachine[F, String]                         │
│                                                              │
│   apply(log) → decode command → mutate Ref[Map] → return     │
│   snapshot()  → serialize Map to bytes                       │
│   restore()   → deserialize bytes to Map                     │
└──────────────────────────────────────────────────────────────┘
```

The key insight is the clean separation of concerns. The Raft layer handles replication and ordering. The command encoding layer handles serialization. The state machine handles domain logic. Each layer knows nothing about the others' internals.

## Step 1: Define the Command Protocol

Every operation the client can perform must be serializable into bytes for storage in the Raft log. Following `examples.kvstore.KVStoreExample`, we define both the command ADT (including atomic Compare-And-Swap) and a typed response ADT:

```scala
enum KVCommand:
  case Put(key: String, value: String)
  case Delete(key: String)
  case Get(key: String)
  case CAS(key: String, expected: String, newValue: String)

enum KVResult:
  case Ok
  case Value(v: Option[String])
  case CasOk
  case CasFailed(currentValue: Option[String])
  case Error(msg: String)

object KVCommand:
  private val SEP = "\t"

  def encode(cmd: KVCommand): Array[Byte] = cmd match
    case Put(k, v)       => s"PUT$SEP$k$SEP$v".getBytes("UTF-8")
    case Delete(k)       => s"DEL$SEP$k".getBytes("UTF-8")
    case Get(k)          => s"GET$SEP$k".getBytes("UTF-8")
    case CAS(k, exp, nv) => s"CAS$SEP$k$SEP$exp$SEP$nv".getBytes("UTF-8")

  def decode(data: Array[Byte]): Either[String, KVCommand] =
    new String(data, "UTF-8").split(SEP).toList match
      case "PUT" :: k :: v :: Nil          => Right(Put(k, v))
      case "DEL" :: k :: Nil               => Right(Delete(k))
      case "GET" :: k :: Nil               => Right(Get(k))
      case "CAS" :: k :: exp :: nv :: Nil  => Right(CAS(k, exp, nv))
      case other                           => Left(s"Invalid command: $other")
```

We use tab-delimited UTF-8 strings for readability. In production (as demonstrated by `WireCodec` in `ShardedBankLedgerExample`), use a length-prefixed binary codec or Protobuf for schema evolution, compact framing, and arbitrary binary keys/values.

> **Note — Why include `CAS` and `Get`?** Because Raft serializes every log entry in a single total order, `CAS(key, expected, newValue)` becomes an **atomic, linearizable compare-and-swap** across the entire cluster with zero distributed locks — the primitive behind leader leases, configuration versioning, and optimistic concurrency control. Including `Get` allows routing reads through the log when maximum simplicity is desired, while Step 4 adds faster `ReadIndex` and `LeaseRead` paths.

## Step 2: Implement the Streaming State Machine

The state machine applies committed commands to an atomic `Ref[F, Map[String, String]]` and implements the streaming `StateMachine[F, KVResult]` trait from [Chapter 7](07-spi-layer.md):

```scala
import java.nio.charset.StandardCharsets
import cats.effect.*
import cats.syntax.all.*
import fs2.Stream
import raft.spi.StateMachine
import raft.state.Log

class KVStateMachine[F[_]: Sync](
  store: Ref[F, Map[String, String]]
) extends StateMachine[F, KVResult]:

  def apply(log: Log): F[KVResult] =
    KVCommand.decode(log.data) match
      case Right(KVCommand.Put(k, v)) =>
        store.update(_.updated(k, v)).as(KVResult.Ok)
      case Right(KVCommand.Delete(k)) =>
        store.update(_ - k).as(KVResult.Ok)
      case Right(KVCommand.Get(k)) =>
        store.get.map(m => KVResult.Value(m.get(k)))
      case Right(KVCommand.CAS(k, expected, newValue)) =>
        store.modify { m =>
          m.get(k) match
            case Some(v) if v == expected => (m.updated(k, newValue), KVResult.CasOk)
            case current                  => (m, KVResult.CasFailed(current))
        }
      case Left(err) =>
        Sync[F].pure(KVResult.Error(err))

  def snapshot: F[Stream[F, Byte]] =
    store.get.map { m =>
      val bytes = m.toSeq
        .sortBy(_._1)
        .map { case (k, v) => s"$k=$v" }
        .mkString("\n")
        .getBytes(StandardCharsets.UTF_8)
      Stream.emits(bytes).covary[F]
    }

  def restore(data: Stream[F, Byte]): F[Unit] =
    data.compile.to(Array).flatMap { bytes =>
      val str = new String(bytes, StandardCharsets.UTF_8)
      val entries =
        if str.isEmpty then Map.empty[String, String]
        else
          str.split("\n").flatMap { line =>
            line.split("=", 2) match
              case Array(k, v) => Some(k -> v)
              case _           => None
          }.toMap
      store.set(entries)
    }

  def get(key: String): F[Option[String]] = store.get.map(_.get(key))
```

Let's examine why each method works correctly:

**`apply`**: Returns a strongly typed `F[KVResult]`. When hosted inside `RaftNode[IO, KVResult]`, calling `leader.proposeAndApply(Bytes.copyFrom(KVCommand.encode(cmd)))` suspends until quorum commit *and* local application, returning `Applied[KVResult](logId, value)` directly to the caller — no side-channel mutable maps needed to correlate CAS outcomes!

**`snapshot`**: Sorts keys deterministically (`sortBy(_._1)`) and emits an `fs2.Stream[F, Byte]` so `CompactionManager` and `MmapWalEngine` can stream `"RSNP"` v2 checksummed snapshots directly to disk and peers without forcing large datasets into contiguous heap arrays.

**`restore`**: Replaces the map atomically from the streamed bytes. Subsequent `apply` calls continue from `snapshot.lastIncludedIndex + 1`.

**Determinism**: `apply` uses no randomness, wall clocks, or external I/O. Given the same committed log prefix, every replica produces the exact same `Map[String, String]`.

> **Note — Exactly-Once Client Retries:** What if a client submits `CAS("counter", "1", "2")`, the leader commits it, and then crashes *before* returning `CasOk`? If the client blindly retries on the new leader, the second execution sees `"2" != "1"` and erroneously receives `CasFailed`. Production KV stores solve this by tagging mutating commands with `(clientId, seqNum)` and storing a bounded per-client response table inside the `StateMachine` (and its snapshots!) — a pattern demonstrated end-to-end in `ShardedBankLedgerExample` ([Chapter 15](15-case-distributed-transactions.md)).

## Step 3: Wire It Together and See It Work

Let's run the state machine with some simulated committed entries:

```scala
import cats.effect.IO

val program: IO[Unit] = for
  // Create the state machine
  fsm <- Ref.of[IO, Map[String, String]](Map.empty)
           .map(new KVStateMachine[IO](_))

  // Simulate committed log entries (these would come from Raft in production)
  entries = List(
    Log.command(1, 1, KVCommand.encode(KVCommand.Put("user:1", "Alice"))),
    Log.command(2, 1, KVCommand.encode(KVCommand.Put("user:2", "Bob"))),
    Log.command(3, 1, KVCommand.encode(KVCommand.Put("user:3", "Charlie"))),
    Log.command(4, 1, KVCommand.encode(KVCommand.Delete("user:2")))
  )

  // Apply each committed entry in log index order
  _ <- entries.traverse(e =>
    fsm.apply(e).flatMap(r => IO.println(s"idx=${e.index}: $r"))
  )
  // Output:
  //   idx=1: OK
  //   idx=2: OK
  //   idx=3: OK
  //   idx=4: OK

  // Query the current state
  _ <- fsm.apply(Log.command(5, 1, KVCommand.encode(KVCommand.Get("user:1"))))
       .flatMap(r => IO.println(s"user:1 = $r"))
  // user:1 = Alice

  _ <- fsm.apply(Log.command(6, 1, KVCommand.encode(KVCommand.Get("user:2"))))
       .flatMap(r => IO.println(s"user:2 = $r"))
  // user:2 = (not found)  ← was deleted in entry 4
yield ()
```

## Step 4: Adding Linearizable Reads (`ReadIndex` and `LeaseRead`)

Routing reads through the Raft log adds a full WAL `msync` + replication round-trip to every query. Both `RaftNode[IO, KVResult]` and `RawNode` expose the two faster §6.4 alternatives from [Chapter 3](03-raft-advanced-theory.md):

### ReadIndex: One Network Round-Trip (Clock-Free Linearizability)

On `RaftNode[IO, KVResult]`, `node.readIndex` executes a quorum heartbeat round and suspends until `lastApplied >= confirmedCommitIndex` before returning:

```scala
def linearizableGet(
  leader: RaftNode[IO, KVResult],
  fsm: KVStateMachine[IO],
  key: String
): IO[Option[String]] =
  for
    _     <- leader.readIndex      // 1-RTT quorum confirmation + wait until lastApplied >= readIndex
    value <- fsm.get(key)          // safe to read local state machine!
  yield value
```

At the pure `RaftLogic` level, submitting `ReadIndexRequest(requestId)` emits `ConfirmLeadership(term, roundId)` plus per-peer heartbeats anchored at each follower's `(prevLogIndex, prevLogTerm)` — and once a quorum responds, emits `ReadIndexReady(requestId, commitIndex)`.

### Lease-Based Reads: Zero Network Round-Trips (Monotonic Lease + Fallback)

When `checkQuorumEnabled = true` and `leaseReadEnabled = true` (with `leaderLeaseDuration` sized safely below `electionTimeoutMin - maxClockDrift`), `leader.leaseRead` checks the leader's monotonic lease deadline locally and returns `Option[Long]`:

```scala
def fastLinearizableGet(
  leader: RaftNode[IO, KVResult],
  fsm: KVStateMachine[IO],
  key: String
): IO[Option[String]] =
  leader.leaseRead.flatMap {
    case Some(_) => fsm.get(key)                         // 0 RTTs! Lease active
    case None    => leader.readIndex *> fsm.get(key)     // Safe 1-RTT quorum fallback
  }
```

Here's a comparison of the three read strategies for a KV store:

| Strategy | Per-Read Latency | Consistency | When to Use |
|----------|-----------------|-------------|-------------|
| Log reads (`proposeAndApply(Get)`) | ~5–20ms (WAL + replication) | Strict linearizability | Maximum simplicity, low read volume |
| `ReadIndex` (`node.readIndex`) | ~1–5ms (1 heartbeat RTT, 0 disk I/O) | Strict linearizability | Default for general production deployments |
| `LeaseRead` (`node.leaseRead`) | ~0ms (local monotonic lookup) | Linearizable* | Read-heavy workloads with bounded clock drift |

\* *Requires bounded monotonic clock drift between hosts (`leaderLeaseDuration < electionTimeoutMin - maxClockDrift`).*

## Step 5: Streaming Snapshot and Recovery

As the log grows, `CompactionManager` periodically captures a streaming snapshot to bound WAL size and accelerate follower recovery:

```scala
// Capture streaming snapshot from KVStateMachine[IO]
val snapshotStream: IO[fs2.Stream[IO, Byte]] = fsm.snapshot

// Restore on a newly joined or restarted node
val recoveredFsm: IO[KVStateMachine[IO]] =
  for
    stream <- snapshotStream
    fsm2   <- KVStateMachine[IO]
    _      <- fsm2.restore(stream)
  yield fsm2
```

When a lagging replica starts with an empty log, the leader streams `"RSNP"` v2 chunks via `InstallSnapshotRequest`, the follower buffers them safely on disk via `FilePendingSnapshot` without OOM risk, restores `KVStateMachine`, and replays only entries strictly after `snapshot.lastIncludedIndex`.

## Architecture Summary

Here's the complete data flow from client request to typed `Applied[KVResult]` response:

```
Client Request: PUT user:1 Alice
        │
        ▼
   ┌───────────┐
   │  Leader   │   1. Encode: KVCommand.Put("user:1", "Alice") → Bytes
   │ (Node-1)  │   2. Create: Log.command(idx=1, term=1, data=bytes)
   │           │   3. Append to local WAL (durable msync)
   │           │   4. Dispatch AppendEntries outside Mutex[F] to followers
   └─────┬─────┘
         │
   ┌─────┴───────────────────┐
   │  AppendEntriesRequest   │
   │  entries=[{PUT user:1}] │──→ Follower-2 (accepts, matchIndex=1)
   │                         │──→ Follower-3 (accepts, matchIndex=1)
   └─────────────────────────┘
         │
         ▼  Majority replicated (3/3) → commitIndex advances to 1
         │
   ┌─────┴────────────┐
   │   All nodes      │
   │   apply(entry)   │ → store.update(_.updated("user:1", "Alice"))
   │   Applied(Ok)    │ → completes caller's Deferred[Applied[KVResult]]
   └──────────────────┘
```

## Run the Example

```bash
sbt "examples/runMain examples.kvstore.KVStoreExample"
```

`KVStoreExample` defines the full `KVCommand` (`Put`, `Delete`, `Get`, `CAS`) and `KVStateMachine[IO]`, drives a two-phase **Pre-Vote → Real Vote** leader election via `RaftLogic.onMessage(..., clusterOpt = Some(cluster))`, appends `Put` and `Delete` log entries, and applies them sequentially to demonstrate replicated state transitions. To see `ReadIndex` and `LeaseRead` tested under live partitions, also run `sbt "examples/runMain examples.protocol.ReadIndexAndLeaseExample"`.

---

*Next: [Chapter 13 — Case Study: Distributed Lock Service](13-case-distributed-lock.md) builds a mutual exclusion service with TTL-based leases — a different kind of state machine that manages ephemeral state.*
