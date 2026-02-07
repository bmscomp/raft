# Tutorial 4: Building a Distributed Rate Limiter

*How to enforce a global rate limit of "1,000 requests per second" across 20 API gateway instances — and why this is harder than it looks.*

---

## The Problem

You run an API behind multiple gateway instances. Each user gets 1,000 requests per minute. The naive approach — each gateway tracks its own counter — fails immediately:

```
User makes 100 req/s across 20 gateways → Each sees 5 req/s → All pass
Total: 2,000 req/s — 2× the intended limit
```

The counter must be **global**: all gateways share a single, consistent view of each user's request count. This is fundamentally a consensus problem.

### Why existing solutions fall short

| Approach | Problem |
|----------|---------|
| **Redis counter** | Single point of failure, eventually consistent replicas allow burst overruns |
| **Local counters** | Under-count by a factor of N (number of gateways) |
| **Token bucket per gateway** | Uneven distribution when traffic is bursty |
| **Sticky sessions** | Eliminates the benefit of load balancing |

## Why Raft Fits

A rate limiter needs two operations with different consistency requirements:

1. **Check** ("is this user over their limit?") — must be **fast**, can tolerate slight staleness
2. **Record** ("this user made a request") — must be **durable**, eventually consistent is fine

Raft provides both:
- **Lease reads** for fast, zero-round-trip checks (the common path — most requests are within limits)
- **Log writes** for durable recording (batched for throughput)

## Architecture

```
┌───────────────────────────────────────────────────────────────────┐
│                     Rate Limiter Cluster                          │
│                                                                   │
│  ┌─────────┐    ┌─────────┐    ┌─────────┐                      │
│  │ Node 1  │◄──►│ Node 2  │◄──►│ Node 3  │  (Raft)             │
│  │(Leader) │    │(Follower)│    │(Follower)│                      │
│  └────▲────┘    └─────────┘    └─────────┘                      │
│       │                                                           │
└───────┼───────────────────────────────────────────────────────────┘
        │  (lease reads for checks, batched writes for recording)
   ╔════╧═══════════════════════════════════════╗
   ║              API Gateways                   ║
   ║  ┌─────┐ ┌─────┐ ┌─────┐      ┌─────┐    ║
   ║  │ GW1 │ │ GW2 │ │ GW3 │ ···  │GW20 │    ║
   ║  └─────┘ └─────┘ └─────┘      └─────┘    ║
   ╚════════════════════════════════════════════╝
```

## Sliding Window Algorithm

A fixed window counter has a well-known edge case: 999 requests at t=59s, 999 requests at t=61s — the user sends 1,998 requests in 2 seconds, all within budget per window but clearly violating the intent.

The **sliding window** algorithm fixes this by interpolating across window boundaries:

```scala
case class WindowState(
  currentCount: Long,
  previousCount: Long,
  windowStartMs: Long,
  windowSizeMs: Long = 60_000  // 1-minute windows
):
  def effectiveCount(nowMs: Long): Long =
    val elapsed = nowMs - windowStartMs
    val weight = 1.0 - (elapsed.toDouble / windowSizeMs)
    val interpolated = (previousCount * weight).toLong + currentCount
    interpolated
```

For example, at 20 seconds into the current window (weight = 0.67):
- Previous window: 600 requests × 0.67 = 402
- Current window: 200 requests
- Effective count: 602 — still under the 1,000 limit

## Command Design

```scala
import java.nio.charset.StandardCharsets

case class UserId(value: String) extends AnyVal

enum RateLimitCommand:
  case RecordRequests(userId: UserId, count: Int, timestampMs: Long)
  case CheckLimit(userId: UserId, timestampMs: Long)
  case UpdateLimits(userId: UserId, maxRequests: Long)
  case AdvanceWindow(timestampMs: Long)

object RateLimitCommand:
  def encode(cmd: RateLimitCommand): Array[Byte] = cmd match
    case RecordRequests(uid, n, ts) =>
      s"REC|${uid.value}|$n|$ts".getBytes(StandardCharsets.UTF_8)
    case CheckLimit(uid, ts) =>
      s"CHK|${uid.value}|$ts".getBytes(StandardCharsets.UTF_8)
    case UpdateLimits(uid, max) =>
      s"LIM|${uid.value}|$max".getBytes(StandardCharsets.UTF_8)
    case AdvanceWindow(ts) =>
      s"ADV|$ts".getBytes(StandardCharsets.UTF_8)

  def decode(data: Array[Byte]): RateLimitCommand =
    val parts = new String(data, StandardCharsets.UTF_8).split("\\|")
    parts(0) match
      case "REC" => RecordRequests(UserId(parts(1)), parts(2).toInt, parts(3).toLong)
      case "CHK" => CheckLimit(UserId(parts(1)), parts(2).toLong)
      case "LIM" => UpdateLimits(UserId(parts(1)), parts(2).toLong)
      case "ADV" => AdvanceWindow(parts(1).toLong)
```

## State Machine

```scala
import cats.effect.{IO, Ref}
import raft.state.Log
import raft.spi.StateMachine

case class UserRateState(
  window: WindowState,
  maxRequests: Long = 1000
)

case class RateLimitState(
  users: Map[UserId, UserRateState],
  defaultLimit: Long = 1000,
  windowSizeMs: Long = 60_000
)

class RateLimitStateMachine(stateRef: Ref[IO, RateLimitState])
    extends StateMachine[IO, String]:

  def apply(log: Log): IO[String] =
    val cmd = RateLimitCommand.decode(log.data)
    cmd match
      case RateLimitCommand.RecordRequests(userId, count, timestampMs) =>
        stateRef.modify { state =>
          val userState = state.users.getOrElse(userId,
            UserRateState(
              WindowState(0, 0, timestampMs, state.windowSizeMs),
              state.defaultLimit
            )
          )

          // Advance window if needed
          val advanced = advanceWindow(userState.window, timestampMs)
          val updated = advanced.copy(currentCount = advanced.currentCount + count)
          val newUser = userState.copy(window = updated)
          val newState = state.copy(users = state.users + (userId -> newUser))

          val effective = updated.effectiveCount(timestampMs)
          val allowed = effective <= newUser.maxRequests

          (newState, s"RECORDED:${userId.value}:$effective:$allowed")
        }

      case RateLimitCommand.CheckLimit(userId, timestampMs) =>
        stateRef.get.map { state =>
          state.users.get(userId) match
            case Some(userState) =>
              val effective = userState.window.effectiveCount(timestampMs)
              val remaining = math.max(0, userState.maxRequests - effective)
              s"CHECK:${userId.value}:remaining=$remaining:allowed=${remaining > 0}"
            case None =>
              s"CHECK:${userId.value}:remaining=${state.defaultLimit}:allowed=true"
        }

      case RateLimitCommand.UpdateLimits(userId, maxRequests) =>
        stateRef.modify { state =>
          val userState = state.users.getOrElse(userId,
            UserRateState(
              WindowState(0, 0, 0, state.windowSizeMs),
              maxRequests
            )
          )
          val updated = userState.copy(maxRequests = maxRequests)
          (state.copy(users = state.users + (userId -> updated)),
           s"LIMIT_SET:${userId.value}:$maxRequests")
        }

      case RateLimitCommand.AdvanceWindow(timestampMs) =>
        stateRef.modify { state =>
          val updated = state.users.map { (uid, us) =>
            uid -> us.copy(window = advanceWindow(us.window, timestampMs))
          }
          (state.copy(users = updated), s"WINDOWS_ADVANCED")
        }

  private def advanceWindow(w: WindowState, nowMs: Long): WindowState =
    if nowMs >= w.windowStartMs + w.windowSizeMs then
      // Roll to new window
      WindowState(
        currentCount = 0,
        previousCount = w.currentCount,
        windowStartMs = w.windowStartMs + w.windowSizeMs,
        windowSizeMs = w.windowSizeMs
      )
    else w

  def snapshot: IO[Array[Byte]] =
    stateRef.get.map { state =>
      state.users.map { (uid, us) =>
        s"${uid.value}|${us.window.currentCount}|${us.window.previousCount}" +
        s"|${us.window.windowStartMs}|${us.maxRequests}"
      }.mkString("\n").getBytes(StandardCharsets.UTF_8)
    }

  def restore(data: Array[Byte]): IO[Unit] =
    val lines = new String(data, StandardCharsets.UTF_8).split("\n").toVector
    val users = lines.map { line =>
      val p = line.split("\\|")
      UserId(p(0)) -> UserRateState(
        WindowState(p(1).toLong, p(2).toLong, p(3).toLong),
        p(4).toLong
      )
    }.toMap
    stateRef.set(RateLimitState(users))
```

## The Fast Path: Lease Reads

The critical insight for a rate limiter: **most requests are within limits**. Checking the limit doesn't need to go through consensus — it's a read-only operation. Use lease reads for zero-latency checks on the leader:

```scala
class RateLimitGateway(
  raftNode: RaftNodeApi,
  stateMachine: RateLimitStateMachine,
  localStateRef: Ref[IO, RateLimitState]
):

  def checkAndRecord(userId: UserId): IO[RateLimitDecision] =
    for
      now <- IO.realTimeInstant.map(_.toEpochMilli)

      // Fast path: lease read for limit check (zero network round-trips)
      state <- localStateRef.get
      effective = state.users.get(userId)
        .map(_.window.effectiveCount(now))
        .getOrElse(0L)
      limit = state.users.get(userId)
        .map(_.maxRequests)
        .getOrElse(state.defaultLimit)

      decision <-
        if effective >= limit then
          IO.pure(RateLimitDecision.Rejected(effective, limit))
        else
          // Record the request via Raft (batched for throughput)
          val cmd = RateLimitCommand.RecordRequests(userId, 1, now)
          raftNode
            .propose(Log.Command(0, 0, RateLimitCommand.encode(cmd)))
            .as(RateLimitDecision.Allowed(limit - effective - 1))
    yield decision

enum RateLimitDecision:
  case Allowed(remaining: Long)
  case Rejected(current: Long, limit: Long)
```

### Why slight staleness is acceptable

The lease read checks the local state, which may be slightly behind the latest committed state. In the worst case:

- Leader's local state shows 998 requests (2 remaining)
- Actually 999 requests are committed (1 remaining)
- The gateway allows the request → actual count becomes 1000

The error is bounded by the number of in-flight (uncommitted) writes. With batching enabled, this is typically 1-2 requests — well within acceptable tolerance for rate limiting.

For users who **must not exceed** their limit under any circumstances (e.g., paid API tiers with legal SLAs), use ReadIndex instead of lease reads:

```scala
def strictCheck(userId: UserId): IO[RateLimitDecision] =
  for
    requestId <- IO(java.util.UUID.randomUUID().toString)
    _         <- raftNode.submitReadIndex(ReadIndexRequest(requestId))
    // After ReadIndex confirms, the local state is guaranteed up-to-date
    state     <- localStateRef.get
    // ... same check logic
  yield decision
```

## Request Batching for Throughput

Individual per-request Raft writes are too expensive at API gateway scale. Batch multiple requests within a small time window:

```scala
import cats.effect.std.Queue

class BatchingRecorder(
  raftNode: RaftNodeApi,
  batchSize: Int = 50,
  flushInterval: FiniteDuration = 10.millis
):
  private val queue = Queue.bounded[IO, (UserId, Long)](1000)

  def record(userId: UserId, timestampMs: Long): IO[Unit] =
    queue.offer((userId, timestampMs))

  def batchLoop: fs2.Stream[IO, Unit] =
    fs2.Stream
      .fromQueueUnterminated(queue)
      .groupWithin(batchSize, flushInterval)
      .evalMap { chunk =>
        // Aggregate counts per user within the batch
        val aggregated = chunk.toList
          .groupBy(_._1)
          .map { (userId, entries) =>
            RateLimitCommand.RecordRequests(
              userId, entries.size, entries.head._2
            )
          }
        // Submit as a single batched Raft write
        aggregated.toList.traverse_ { cmd =>
          raftNode.propose(Log.Command(0, 0, RateLimitCommand.encode(cmd)))
        }
      }
```

With batching, 20 gateways × 1,000 req/s = 20,000 events/s become ~400 batched Raft writes/s (50 events per batch) — well within Raft's throughput capacity.

## Failure Scenarios

### Scenario 1: Leader fails during burst

```
t=0    User at 950/1000 requests. Leader crashes.
t=2    New leader elected. State machine shows 950 requests.
t=2.1  User sends 50 more requests → correctly limited at 1000.
```

**What happens**: The committed state (950 requests) survives leadership change. No requests are "forgotten" — the new leader has the exact same state machine.

### Scenario 2: Network partition — gateway talks to minority

```
t=0    Gateway connected to Node 1 (partitioned minority)
t=1    Gateway's RecordRequests writes are not committed (no quorum)
t=5    Gateway's propose calls time out
t=5.1  Gateway falls back to local-only tracking (degraded mode)
```

**Mitigation**: Gateways should detect write failures and switch to a local token bucket as a fallback. The local limit should be set to `globalLimit / numGateways` — conservative but safe.

### Scenario 3: Clock skew between gateways

```
t=0    Gateway 1 (clock accurate): sends timestamp 1000
t=0    Gateway 2 (clock +5s ahead): sends timestamp 5000
```

**What happens**: Because timestamps enter through the Raft log (the leader includes them in the command), all nodes see the same timestamp for each command. The sliding window advances consistently across all nodes. Clock skew between gateways doesn't affect correctness — it only affects the local lease-read check accuracy, which is already approximate.

## Production Considerations

### Memory management

Active users with zero recent requests waste memory. Evict users with expired windows:

```scala
def evictInactive(state: RateLimitState, nowMs: Long): RateLimitState =
  val activeUsers = state.users.filter { (_, us) =>
    // Keep users who had activity in the last 2 windows
    nowMs - us.window.windowStartMs < us.window.windowSizeMs * 2
  }
  state.copy(users = activeUsers)
```

### Response headers

Return standard rate limit headers so clients can self-throttle:

```
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 742
X-RateLimit-Reset: 1707300120
Retry-After: 45          (only on 429 responses)
```

### Scaling with Multi-Raft Groups

Partition users across groups by hash for horizontal scaling:

```scala
val numGroups = 8

def groupForUser(userId: UserId): GroupId =
  val hash = userId.value.hashCode.abs % numGroups
  GroupId(s"ratelimit-$hash")
```

Each group independently manages its subset of users. A user's requests always route to the same group, so the counter is accurate. With 8 groups, throughput scales to ~8× a single group.

### Monitoring

| Metric | Alert | Meaning |
|--------|-------|---------|
| P99 check latency | > 5ms | Lease may be expired, falling back to ReadIndex |
| Batch queue depth | > 500 | Raft writes can't keep up — increase batch size |
| Rejection rate | > 10% | Legitimate users hitting limits, or DDoS |
| State machine size | > 100K users | Eviction not aggressive enough |

---

*Next: [Tutorial 5 — Cluster Membership Management](05-cluster-membership.md) — the most anxiety-inducing production task: changing cluster membership without downtime.*
