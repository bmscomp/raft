# Chapter 10: Case Study — Distributed Key-Value Store

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

Every operation the client can perform must be serializable into a byte array for storage in the Raft log. We define an ADT (algebraic data type) for the three command types:

```scala
enum KVCommand:
  case Put(key: String, value: String)
  case Delete(key: String)
  case Get(key: String)

object KVCommand:
  def encode(cmd: KVCommand): Array[Byte] = cmd match
    case Put(k, v) => s"PUT\t$k\t$v".getBytes
    case Delete(k) => s"DEL\t$k".getBytes
    case Get(k)    => s"GET\t$k".getBytes

  def decode(data: Array[Byte]): Either[String, KVCommand] =
    new String(data).split("\t").toList match
      case "PUT" :: k :: v :: Nil => Right(Put(k, v))
      case "DEL" :: k :: Nil      => Right(Delete(k))
      case "GET" :: k :: Nil      => Right(Get(k))
      case other                  => Left(s"Invalid command: $other")
```

We use tab-delimited strings for simplicity and readability. In a production system, you'd use Protocol Buffers, MessagePack, or another binary codec for:
- **Schema evolution**: adding new command types without breaking existing entries
- **Size efficiency**: binary encodings are typically 5-10× smaller
- **Type safety**: binary codecs catch serialization errors at compile time

> **Note — Why is Get in the command protocol?** For strict linearizability, reads can go through the Raft log — this guarantees they see the latest committed state. However, this adds full replication latency to every read. In Step 4 below, we'll add ReadIndex and lease-based reads as faster alternatives. Including `Get` in the command protocol gives you the option of routing reads through the log when maximum consistency is needed (at the cost of latency).

## Step 2: Implement the State Machine

The state machine applies committed commands to an in-memory `Map`. It implements the `StateMachine[F, String]` trait from Chapter 6:

```scala
import cats.effect.*
import raft.spi.StateMachine
import raft.state.Log

class KVStateMachine[F[_]: Sync](
  store: Ref[F, Map[String, String]]
) extends StateMachine[F, String]:

  def apply(log: Log): F[String] =
    KVCommand.decode(log.data) match
      case Right(KVCommand.Put(k, v)) =>
        store.update(_.updated(k, v)).as("OK")
      case Right(KVCommand.Delete(k)) =>
        store.update(_ - k).as("OK")
      case Right(KVCommand.Get(k)) =>
        store.get.map(m => m.getOrElse(k, "(not found)"))
      case Left(err) =>
        Sync[F].pure(s"ERROR: $err")

  def snapshot: F[Array[Byte]] =
    store.get.map { m =>
      m.map { case (k, v) => s"$k=$v" }.mkString("\n").getBytes
    }

  def restore(data: Array[Byte]): F[Unit] =
    val entries = new String(data).split("\n").flatMap { line =>
      line.split("=", 2) match
        case Array(k, v) => Some(k -> v)
        case _           => None
    }.toMap
    store.set(entries)
```

Let's examine why each method works correctly:

**`apply`**: The Cats Effect `Ref` provides atomic updates. Even though the state machine is called from a concurrent event loop, `store.update` is thread-safe. The return value (`"OK"`, the value, or an error) is sent back to the client.

**`snapshot`**: Serializes the entire map to a newline-delimited string. This is a **point-in-time capture** — the Ref's `get` returns a consistent snapshot of the map at the moment it's called. The library ensures no entries are being applied concurrently.

**`restore`**: Replaces the map atomically with the deserialized snapshot data. After restore, subsequent `apply` calls continue from the entries after the snapshot's last included index.

**Determinism**: This implementation is deterministic — `apply` doesn't use randomness, system clocks, or external I/O. Given the same sequence of log entries, every node produces the same map state. This is the absolute requirement for replicated state machines (Chapter 7).

> **Note — Performance considerations for production:** An in-memory `Map` works well for moderate data sizes. For data sets exceeding available RAM, consider using an embedded database (RocksDB, LevelDB, LMDB) as the backing store. The `StateMachine` interface is the same — only `apply`, `snapshot`, and `restore` change. RocksDB's native snapshot support makes the `snapshot` method efficient even for large data sets.

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

## Step 4: Adding Linearizable Reads

The basic implementation routes reads through the Raft log (`Put("user:1", "Alice")` goes through replication), which adds full replication latency to every read. For read-heavy workloads, this is unacceptable. Here are two faster alternatives from Chapter 3:

### ReadIndex: One Network Round-Trip

ReadIndex confirms the leader's authority with a heartbeat round before serving the read:

```scala
// The leader confirms it's still the leader
val readTrans = RaftLogic.handleReadIndex(leaderState, config, clusterSize)

readTrans.effects.collectFirst {
  case ConfirmLeadership(commitIndex) =>
    // Runtime: send heartbeat to all followers
    //          wait for majority acknowledgment
    //          then serve the read from state applied through commitIndex
    commitIndex
}
```

After receiving majority acknowledgment, the runtime reads from the state machine. The read is linearizable because:
1. The heartbeat confirms no new leader has been elected
2. The commitIndex ensures the read sees all committed writes

### Lease-Based Reads: Zero Network Round-Trips

If bounded clock skew is acceptable, lease-based reads are even faster:

```scala
val config = RaftConfig(
  localId = n1,
  leaderLeaseDuration = 100.millis  // conservative for ~10ms clock skew
)

val leaseTrans = RaftLogic.handleLeaseRead(leaderState, config)

leaseTrans.effects.collectFirst {
  case LeaseReadReady(commitIndex) =>
    // Lease is valid — serve the read immediately from local state
    // No network communication at all!
    commitIndex
}
```

Here's a comparison of the three read strategies for a KV store:

| Strategy | Per-Read Latency | Consistency | When to Use |
|----------|-----------------|-------------|-------------|
| Log reads | ~5-20ms (full replication) | Linearizable | Maximum safety, low read volume |
| ReadIndex | ~1-5ms (one heartbeat RTT) | Linearizable | General purpose, most deployments |
| Lease-based | ~0ms (local lookup) | Linearizable* | Read-heavy workloads, trusted clocks |

\* *Requires bounded clock skew between nodes*

## Step 5: Snapshot and Recovery

As the log grows, you periodically take snapshots to bound storage use and accelerate node recovery. Here's the complete lifecycle:

### Taking a Snapshot

```scala
// Your runtime periodically triggers a snapshot (e.g., every 10,000 entries)
val snapshotData: IO[Array[Byte]] = fsm.snapshot
// Returns bytes representing: "user:1=Alice\nuser:3=Charlie"
// (user:2 is absent because it was deleted)
```

### Recovering from a Snapshot

When a new node joins (or a crashed node restarts with an empty log), it receives the snapshot via `InstallSnapshot` from the leader:

```scala
val recoveredFsm = for
  store <- Ref.of[IO, Map[String, String]](Map.empty)
  fsm   =  new KVStateMachine[IO](store)
  _     <- fsm.restore(snapshotBytes)
yield fsm
// fsm now has {user:1 → Alice, user:3 → Charlie}
// Ready to receive AppendEntries from the leader for entries after the snapshot
```

The beauty of this approach: the recovering node doesn't need to replay millions of log entries from the beginning. It loads the snapshot (a single bulk file), restores the state machine, and then only needs to replay entries after the snapshot's last included index. Recovery time drops from minutes to seconds (or less).

## Architecture Summary

Here's the complete data flow from client request to response:

```
Client Request: PUT user:1 Alice
        │
        ▼
   ┌───────────┐
   │  Leader   │   1. Encode: KVCommand.Put("user:1", "Alice") → bytes
   │ (Node-1)  │   2. Create: Log.command(idx=1, term=1, data=bytes)
   │           │   3. Append to local log (durable write)
   │           │   4. Send AppendEntries to all followers
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
   │   return "OK"    │ → leader sends "OK" to client
   └──────────────────┘
```

## Run the Example

```bash
sbt "runMain examples.kvstore.KVStoreExample"
```

The example creates a complete in-memory cluster, sends multiple PUT/GET/DELETE commands through the Raft log, and demonstrates linearizable reads using both ReadIndex and lease-based approaches.

---

*Next: [Chapter 11 — Case Study: Distributed Lock Service](11-case-distributed-lock.md) builds a mutual exclusion service with TTL-based leases — a different kind of state machine that manages ephemeral state.*
