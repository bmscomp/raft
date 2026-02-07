# Chapter 13: Case Study — Distributed Lock Service

*Mutual exclusion across distributed processes is one of the hardest problems in distributed systems. This case study builds a lock service with TTL-based leases, ownership tracking, automatic expiration, and reentrant acquisition — the same primitives that power Google's Chubby and Apache ZooKeeper. The key insight: a Raft-replicated state machine turns lock management from a distributed problem into a sequential one.*

---

## The Problem

Multiple processes running on different machines need **exclusive access** to a shared resource. Think of:

- A database schema migration that should only run on one node at a time
- A scheduled job (cron) that shouldn't execute concurrently across replicas
- A cache rebuild that's expensive and shouldn't be duplicated
- A coordination point where exactly one process must own a leadership role

In a single-process world, you'd use a mutex or semaphore. But across machines, there's no shared memory and no reliable signaling. Worse, processes can crash, networks can partition, and clocks can drift.

Naive distributed locks (like Redis-based locks) have fundamental safety gaps that are well-documented in Martin Kleppmann's critique of Redlock (*"How to do distributed locking"*, 2016). The core issue: without consensus, there's no way to guarantee that two clients don't both believe they hold the lock simultaneously.

With Raft, the lock state is replicated to a majority before any acquisition is confirmed. During a network partition, only the side with a majority can grant locks. On the minority side, lock operations stall — they don't return stale or split-brain results. This is the correctness guarantee you need for production lock services.

### Requirements

| Requirement | Description | Why It Matters |
|-------------|-------------|---------------|
| **Mutual exclusion** | At most one holder per lock at any time | The core safety property |
| **Deadlock prevention** | Locks auto-expire via TTL if the holder crashes | Without this, a crashed holder blocks everyone forever |
| **Reentrance** | Same client can re-acquire its own lock (extending the lease) | Prevents self-deadlock in recursive or retry scenarios |
| **Fault tolerance** | Lock state survives individual server failures | Locks are useless if they're lost on crash |

## Design

The lock service is a Raft state machine where each named lock is either free or held:

```scala
case class LockInfo(
  owner: String,      // client ID holding the lock
  expiresAt: Long     // epoch milliseconds when the lock auto-expires
)
```

Three commands drive the state machine:

```scala
enum LockCommand:
  case Acquire(lockId: String, clientId: String, ttl: Duration)
  case Release(lockId: String, clientId: String)
  case Heartbeat(lockId: String, clientId: String)
```

The **Heartbeat** command is a lease renewal mechanism. Clients send heartbeats periodically (at intervals shorter than the TTL) to keep their lock alive. If a client crashes or is partitioned away, its heartbeats stop, and the lock expires after the TTL. This is the same pattern used by ZooKeeper session keepalives and Chubby's lease mechanism.

## Step 1: Command Encoding

```scala
object LockCommand:
  def encode(cmd: LockCommand): Array[Byte] =
    val str = cmd match
      case Acquire(lock, client, ttl) =>
        s"ACQUIRE:$lock:$client:${ttl.toMillis}"
      case Release(lock, client) =>
        s"RELEASE:$lock:$client"
      case Heartbeat(lock, client) =>
        s"HEARTBEAT:$lock:$client"
    str.getBytes

  def decode(data: Array[Byte]): Either[String, LockCommand] =
    new String(data).split(":").toList match
      case "ACQUIRE" :: lock :: client :: ttlMs :: Nil =>
        Right(Acquire(lock, client, ttlMs.toLong.millis))
      case "RELEASE" :: lock :: client :: Nil =>
        Right(Release(lock, client))
      case "HEARTBEAT" :: lock :: client :: Nil =>
        Right(Heartbeat(lock, client))
      case other => Left(s"Invalid lock command: $other")
```

## Step 2: The Lock State Machine

```scala
class LockStateMachine[F[_]: Sync: Clock](
  locks: Ref[F, Map[String, LockInfo]]
) extends StateMachine[F, LockResult]:

  def apply(log: Log): F[LockResult] =
    LockCommand.decode(log.data) match
      case Right(Acquire(lockId, clientId, ttl)) =>
        acquireLock(lockId, clientId, ttl)
      case Right(Release(lockId, clientId)) =>
        releaseLock(lockId, clientId)
      case Right(Heartbeat(lockId, clientId)) =>
        renewLock(lockId, clientId)
      case Left(err) =>
        Sync[F].pure(LockResult.Error(err))
```

### Lock Acquisition: Four Cases

The acquisition logic handles four scenarios in priority order:

```scala
private def acquireLock(
  lockId: String, clientId: String, ttl: Duration
): F[LockResult] =
  for
    now <- Clock[F].realTime
    result <- locks.modify { current =>
      current.get(lockId) match
        // Case 1: No lock exists → grant immediately
        case None =>
          val info = LockInfo(clientId, now.toMillis + ttl.toMillis)
          (current.updated(lockId, info), LockResult.Acquired)

        // Case 2: Lock expired → grant (previous holder timed out)
        case Some(lock) if lock.expiresAt <= now.toMillis =>
          val info = LockInfo(clientId, now.toMillis + ttl.toMillis)
          (current.updated(lockId, info), LockResult.Acquired)

        // Case 3: Same owner → renew (reentrant acquisition)
        case Some(lock) if lock.owner == clientId =>
          val info = LockInfo(clientId, now.toMillis + ttl.toMillis)
          (current.updated(lockId, info), LockResult.Renewed)

        // Case 4: Different owner, lock still valid → deny
        case Some(lock) =>
          (current, LockResult.Denied(s"Lock held by ${lock.owner}"))
    }
  yield result
```

Notice that Case 3 provides **reentrance** — if a client tries to acquire a lock it already holds, the lock is simply renewed rather than denied. This prevents self-deadlocks that can occur when a client's retry logic re-sends an acquire request.

### Lock Release

```scala
private def releaseLock(lockId: String, clientId: String): F[LockResult] =
  locks.modify { current =>
    current.get(lockId) match
      case Some(lock) if lock.owner == clientId =>
        (current - lockId, LockResult.Released)
      case Some(_) =>
        (current, LockResult.Denied("Not the lock owner"))
      case None =>
        (current, LockResult.NotFound)
  }
```

Only the lock's owner can release it. Attempts by other clients are denied. Releasing a non-existent lock returns `NotFound` — this is idempotent, which is important because release commands might be retried.

> **Note — Determinism and Clock usage:** You might notice that `acquireLock` calls `Clock[F].realTime`, which reads the system clock — a non-deterministic operation. Strictly speaking, this violates the determinism contract from Chapter 6. In production, you'd encode the timestamp in the log entry data (set by the leader at submission time), so that all nodes use the same timestamp when applying the entry. We use `Clock` here for simplicity, but be aware of this subtlety in production designs.

## Step 3: Using the Lock

```scala
val lockDemo: IO[Unit] = for
  lockFsm <- LockStateMachine[IO]

  // Client-1 acquires a lock on the database migration
  r1 <- lockFsm.apply(Log.command(1, 1,
    LockCommand.encode(Acquire("db-migration", "client-1", 30.seconds))
  ))
  _ <- IO.println(s"Acquire: $r1")
  // → Acquire: Acquired

  // Client-2 tries the same lock → denied
  r2 <- lockFsm.apply(Log.command(2, 1,
    LockCommand.encode(Acquire("db-migration", "client-2", 30.seconds))
  ))
  _ <- IO.println(s"Client-2 attempt: $r2")
  // → Client-2 attempt: Denied(Lock held by client-1)

  // Client-1 sends a heartbeat to extend the lease
  r3 <- lockFsm.apply(Log.command(3, 1,
    LockCommand.encode(Heartbeat("db-migration", "client-1"))
  ))
  _ <- IO.println(s"Heartbeat: $r3")
  // → Heartbeat: Renewed

  // Client-1 releases the lock
  r4 <- lockFsm.apply(Log.command(4, 1,
    LockCommand.encode(Release("db-migration", "client-1"))
  ))
  _ <- IO.println(s"Release: $r4")
  // → Release: Released

  // Now Client-2 can acquire
  r5 <- lockFsm.apply(Log.command(5, 1,
    LockCommand.encode(Acquire("db-migration", "client-2", 30.seconds))
  ))
  _ <- IO.println(s"Client-2 retry: $r5")
  // → Client-2 retry: Acquired
yield ()
```

## Handling Failures

### Lock Holder Crashes

If a client crashes without releasing its lock, **nothing breaks** — the TTL ensures automatic expiration. After the TTL elapses, the next `Acquire` command finds the lock expired (Case 2 above) and grants it to the new requester. No manual intervention is needed.

In practice, TTLs should be set to a multiple of the expected heartbeat interval. For example, if clients send heartbeats every 5 seconds, a TTL of 30 seconds gives the client 6 missed heartbeats before the lock expires — enough to handle short network hiccups without false expirations.

### Leader Failure During Acquisition

If the Raft leader fails after the client sends an Acquire but before it's committed:
- If the Acquire was replicated to a majority → the new leader has it, and it will be committed
- If it wasn't replicated → the Acquire is lost, and the client should retry

Raft's safety guarantees ensure that a committed Acquire is never lost. The client should use the standard Raft retry pattern: submit to the leader, wait for acknowledgment, retry with the new leader if the old leader becomes unavailable.

### Split-Brain Prevention

During a network partition, only the side with a majority of Raft nodes can commit commands. The minority side **cannot grant or release locks**. This means:

- A lock held by a client on the majority side remains valid
- A lock held by a client on the minority side will eventually expire (heartbeats can't commit)
- No two clients ever hold the same lock simultaneously, even during a partition

This is the fundamental advantage over non-consensus lock services like standalone Redis.

## Comparison with Production Lock Services

| Aspect | This Library | Chubby (Google) | ZooKeeper |
|--------|-------------|-----------------|-----------|
| **Consensus** | Raft | Paxos | Zab (Paxos variant) |
| **Lock model** | TTL leases + explicit release | Session-based leases | Ephemeral znodes |
| **Failure detection** | TTL expiration | Session keepalive | Session timeout |
| **Read consistency** | Linearizable via ReadIndex | Linearizable | Sequential (default) |
| **Implementation size** | ~80 lines of Scala | Proprietary (not open source) | ~100K lines of Java |
| **Watch/notify** | Not built-in (can be added) | Events on lock state changes | Watches on znodes |

## Run the Example

```bash
sbt "runMain examples.lock.DistributedLockExample"
```

---

*Next: [Chapter 14 — Case Study: Replicated Counter](14-case-distributed-counter.md) goes beyond a single state machine and simulates a complete 3-node Raft cluster with in-memory networking, showing how election, replication, and commit work end-to-end.*
