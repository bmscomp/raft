# Chapter 10: Case Study — Distributed Lock Service

*Mutual exclusion across distributed processes. This case study builds a lock service with TTL-based leases, ownership tracking, and automatic expiration — the same primitives used by Chubby and ZooKeeper.*

---

## The Problem

Multiple processes need exclusive access to a shared resource — a database migration, a cron job, a cache rebuild. In a single-machine world, you'd use a mutex. Across machines, you need a **distributed lock**.

Requirements:

| Requirement | Description |
|-------------|-------------|
| **Mutual exclusion** | At most one holder per lock at any time |
| **Deadlock prevention** | Locks auto-expire if the holder crashes |
| **Reentrance** | Same client can re-acquire its own lock |
| **Fault tolerance** | Lock state survives individual server failures |

## Design

The lock service is a Raft state machine where each lock consists of:

```scala
case class LockInfo(
  owner: String,      // client ID holding the lock
  expiresAt: Long     // epoch milliseconds when the lock expires
)
```

Three commands drive the state machine:

```scala
enum LockCommand:
  case Acquire(lockId: String, clientId: String, ttl: Duration)
  case Release(lockId: String, clientId: String)
  case Heartbeat(lockId: String, clientId: String)
```

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
      case other => Left(s"Invalid: $other")
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

### Lock Acquisition Logic

```scala
private def acquireLock(
  lockId: String, clientId: String, ttl: Duration
): F[LockResult] =
  for
    now <- Clock[F].realTime
    result <- locks.modify { current =>
      current.get(lockId) match
        // No lock exists → acquire
        case None =>
          val info = LockInfo(clientId, now.toMillis + ttl.toMillis)
          (current.updated(lockId, info), LockResult.Acquired)

        // Lock expired → acquire
        case Some(lock) if lock.expiresAt <= now.toMillis =>
          val info = LockInfo(clientId, now.toMillis + ttl.toMillis)
          (current.updated(lockId, info), LockResult.Acquired)

        // Same owner → renew (reentrant)
        case Some(lock) if lock.owner == clientId =>
          val info = LockInfo(clientId, now.toMillis + ttl.toMillis)
          (current.updated(lockId, info), LockResult.Renewed)

        // Different owner, still valid → deny
        case Some(lock) =>
          (current, LockResult.Denied(s"Lock held by ${lock.owner}"))
    }
  yield result
```

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

## Step 3: Using the Lock

```scala
val lockDemo: IO[Unit] = for
  lockFsm <- LockStateMachine[IO]

  // Client-1 acquires a lock on "database-migration"
  r1 <- lockFsm.apply(Log.command(1, 1,
    LockCommand.encode(Acquire("db-migration", "client-1", 30.seconds))
  ))
  _ <- IO.println(s"Acquire: $r1")
  // Acquire: Acquired

  // Client-2 tries to acquire the same lock → denied
  r2 <- lockFsm.apply(Log.command(2, 1,
    LockCommand.encode(Acquire("db-migration", "client-2", 30.seconds))
  ))
  _ <- IO.println(s"Client-2 attempt: $r2")
  // Client-2 attempt: Denied(Lock held by client-1)

  // Client-1 renews via heartbeat
  r3 <- lockFsm.apply(Log.command(3, 1,
    LockCommand.encode(Heartbeat("db-migration", "client-1"))
  ))
  _ <- IO.println(s"Heartbeat: $r3")
  // Heartbeat: Renewed

  // Client-1 releases
  r4 <- lockFsm.apply(Log.command(4, 1,
    LockCommand.encode(Release("db-migration", "client-1"))
  ))
  _ <- IO.println(s"Release: $r4")
  // Release: Released

  // Now Client-2 can acquire
  r5 <- lockFsm.apply(Log.command(5, 1,
    LockCommand.encode(Acquire("db-migration", "client-2", 30.seconds))
  ))
  _ <- IO.println(s"Client-2 retry: $r5")
  // Client-2 retry: Acquired
yield ()
```

## Handling Failures

### Lock Holder Crashes

If a client crashes without releasing its lock, the TTL ensures automatic expiration. Other clients can acquire the lock after the TTL expires — no manual intervention needed.

### Leader Failure During Acquisition

If the Raft leader fails after the client sends an Acquire but before it's committed, the client should retry. The new leader will either:
- Have the Acquire in its log (it was replicated to a majority) → the lock is granted
- Not have the Acquire (it wasn't replicated) → the client retries with the new leader

### Split Brain Prevention

During a network partition, only the side with a majority of Raft nodes can commit new lock operations. The minority side cannot grant or release locks, preventing split-brain scenarios where two clients hold the same lock.

## Comparison with Other Systems

| Aspect | This Library | Chubby (Google) | ZooKeeper |
|--------|-------------|-----------------|-----------|
| **Consensus** | Raft | Paxos | Zab |
| **Lock model** | TTL-based, explicit release | TTL-based, session-based | Ephemeral znodes |
| **Failure detection** | TTL expiration | Session keepalive | Session timeout |
| **Read consistency** | Linearizable via ReadIndex | Linearizable | Sequential (default) |
| **Implementation** | ~80 lines of Scala | Proprietary | Java |

## Run the Example

```bash
sbt "runMain examples.lock.DistributedLockExample"
```

---

*Next: [Chapter 11 — Case Study: Replicated Counter](11-case-distributed-counter.md) demonstrates a full cluster simulation with in-memory networking.*
