# Chapter 9: Case Study — Distributed Key-Value Store

*The most common application of consensus: a replicated data store that survives server failures and serves consistent reads. This case study builds a complete KV store from scratch using the library's StateMachine SPI.*

---

## The Problem

You need a key-value store with these guarantees:

- **Durability**: data survives individual server failures
- **Consistency**: every read returns the most recently written value
- **Availability**: the system serves requests as long as a majority of servers are up

A single Redis instance provides none of these — if it crashes, your data may be lost and your service is down. A naive primary-replica setup can lose data if the primary fails before replication completes.

With Raft, you get **linearizability**: every write is replicated to a majority before it's acknowledged, and reads can be served from the current commit index.

## Design

```
┌──────────────────────────────────────────────────────────┐
│                   Client API                             │
│   put(key, value) → OK                                   │
│   get(key)        → value | not found                    │
│   delete(key)     → OK                                   │
├──────────────────────────────────────────────────────────┤
│                 Command Encoding                         │
│   KVCommand → Array[Byte] → Log entry → Raft replication │
├──────────────────────────────────────────────────────────┤
│                KVStateMachine                            │
│   StateMachine[F, String]                                │
│   apply(log) → decode command → mutate state → return    │
│   snapshot()  → serialize current map                    │
│   restore()   → deserialize snapshot into map            │
└──────────────────────────────────────────────────────────┘
```

## Step 1: Define the Command Protocol

Every operation the client can perform must be serializable into a byte array for the Raft log:

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
      case other                  => Left(s"Invalid: $other")
```

**Design decision**: we use tab-delimited strings for simplicity. In production, you'd use Protocol Buffers, MessagePack, or a binary codec for efficiency.

**Why Get is in the log**: for strict linearizability, reads can go through the log. For lower latency, use ReadIndex or lease-based reads (see below).

## Step 2: Implement the State Machine

The state machine applies committed commands to an in-memory map:

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

**Key property**: `apply` is **deterministic**. Given the same log entry, every node produces the same state. This is the foundation of replicated state machines.

## Step 3: Wire It Together

```scala
import cats.effect.IO

val program: IO[Unit] = for
  fsm <- Ref.of[IO, Map[String, String]](Map.empty)
           .map(new KVStateMachine[IO](_))

  // Simulate committed log entries
  entries = List(
    Log.command(1, 1, KVCommand.encode(KVCommand.Put("user:1", "Alice"))),
    Log.command(2, 1, KVCommand.encode(KVCommand.Put("user:2", "Bob"))),
    Log.command(3, 1, KVCommand.encode(KVCommand.Delete("user:2")))
  )

  // Apply each committed entry in order
  _ <- entries.traverse(e =>
    fsm.apply(e).flatMap(r => IO.println(s"idx=${e.index}: $r"))
  )
  // idx=1: OK
  // idx=2: OK
  // idx=3: OK

  // Query the state
  _ <- fsm.apply(Log.command(4, 1, KVCommand.encode(KVCommand.Get("user:1"))))
       .flatMap(r => IO.println(s"user:1 = $r"))
  // user:1 = Alice

  _ <- fsm.apply(Log.command(5, 1, KVCommand.encode(KVCommand.Get("user:2"))))
       .flatMap(r => IO.println(s"user:2 = $r"))
  // user:2 = (not found)
yield ()
```

## Step 4: Adding Linearizable Reads

The basic implementation routes reads through the Raft log, which adds replication latency. For faster reads, use ReadIndex:

```scala
// Leader confirms it's still leader, then serves the read
val readTrans = RaftLogic.handleReadIndex(leaderState, config, clusterSize)

readTrans.effects.collectFirst {
  case ConfirmLeadership(commitIndex) =>
    // Runtime: send heartbeat to all followers
    // Wait for majority acknowledgment
    // Then serve any read with data applied through commitIndex
    commitIndex
}
```

Or for the lowest latency, use lease-based reads:

```scala
val config = RaftConfig(
  localId = n1,
  leaderLeaseDuration = 100.millis
)

val leaseTrans = RaftLogic.handleLeaseRead(leaderState, config)

leaseTrans.effects.collectFirst {
  case LeaseReadReady(commitIndex) =>
    // Lease is valid — serve read immediately, no network round-trip
    commitIndex
}
```

| Read Strategy | Latency | Consistency | Requirement |
|--------------|---------|-------------|-------------|
| Through log | Full replication RTT | Linearizable | None |
| ReadIndex | One heartbeat RTT | Linearizable | None |
| Lease-based | Zero | Linearizable | Bounded clock skew |

## Step 5: Snapshot and Recovery

As the log grows, you periodically take snapshots:

```scala
// Take a snapshot of the current state
val snapshotData: IO[Array[Byte]] = fsm.snapshot
// Returns: "user:1=Alice\nuser:3=Charlie"

// On a new or recovering node:
val recoveredFsm = for
  store <- Ref.of[IO, Map[String, String]](Map.empty)
  fsm   =  new KVStateMachine[IO](store)
  _     <- fsm.restore(snapshotData)
yield fsm
// fsm now has {user:1 → Alice, user:3 → Charlie}
```

## Architecture Summary

```
Client Request: PUT user:1 Alice
        │
        ▼
   ┌─────────┐
   │  Leader  │  1. Encode command
   │          │  2. Create Log entry
   │          │  3. Append to local log
   │          │  4. Replicate to followers
   └────┬─────┘
        │
   ┌────┴─────────────────┐
   │ AppendEntriesRequest  │ → Follower 1 (accepts, matchIndex=1)
   │ entries=[{PUT user:1}]│ → Follower 2 (accepts, matchIndex=1)
   └───────────────────────┘
        │
        ▼ Majority replicated → COMMIT
        │
   ┌────┴─────────┐
   │ All nodes     │
   │ apply(entry)  │ → store.update(_.updated("user:1", "Alice"))
   │ → "OK"        │
   └───────────────┘
```

## Run the Example

```bash
sbt "runMain examples.kvstore.KVStoreExample"
```

---

*Next: [Chapter 10 — Case Study: Distributed Lock Service](10-case-distributed-lock.md) builds a mutual exclusion service with TTL-based leases.*
