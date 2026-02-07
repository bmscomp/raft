# Tutorial 1: Building a Configuration Service

*How Netflix, Uber, and every serious distributed system manages configuration — and how you can build the same thing with Raft in 200 lines of Scala.*

---

## The Problem

Every distributed application needs shared configuration: feature flags, database connection strings, service endpoints, rate limits. The naive approach — a config file on each server — breaks instantly:

- **Staleness**: Server A reads the old config while Server B has the new one. A user hits A, sees the old behavior, refreshes, hits B, sees the new behavior.
- **Split-brain**: Two administrators update the same key simultaneously. Which write wins? Without coordination, both "win" on different servers.
- **No change notification**: Services poll a config file every 30 seconds. For 30 seconds after a change, half your fleet runs the old config and half runs the new one.

Production systems solve this with dedicated configuration services: **etcd** (Kubernetes), **ZooKeeper** (Kafka, Hadoop), **Consul** (HashiCorp). All three use consensus algorithms (etcd and Consul use Raft; ZooKeeper uses ZAB, which is Raft-like) to guarantee that every client sees the same configuration at the same time.

## Why Raft Solves It

Raft provides exactly the guarantees a configuration service needs:

| Requirement | Raft property |
|------------|---------------|
| Every server sees the same config | **State Machine Safety** — all nodes apply the same commands in the same order |
| Writes are durable | **Leader Completeness** — committed entries survive leader failures |
| Reads reflect the latest writes | **Linearizable reads** via ReadIndex or lease |
| Change notifications are ordered | The log provides a **total order** — watch for entries after a known index |

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                  Configuration Service                    │
│                                                          │
│  ┌─────────┐    ┌─────────┐    ┌─────────┐              │
│  │ Node 1  │◄──►│ Node 2  │◄──►│ Node 3  │  (Raft)     │
│  │(Leader) │    │(Follower)│    │(Follower)│              │
│  └────▲────┘    └─────────┘    └─────────┘              │
│       │                                                  │
└───────┼──────────────────────────────────────────────────┘
        │
   ┌────┴─────────────────────┐
   │    Client Applications    │
   │                           │
   │  PUT /config/db.host      │
   │  GET /config/db.host      │
   │  WATCH /config/db.*       │
   └───────────────────────────┘
```

## Command Design

The state machine needs to support four operations:

```scala
import java.nio.charset.StandardCharsets

enum ConfigCommand:
  case Put(key: String, value: String)
  case Delete(key: String)
  case Get(key: String)
  case Watch(prefix: String, sinceVersion: Long)

object ConfigCommand:
  def encode(cmd: ConfigCommand): Array[Byte] = cmd match
    case Put(k, v)       => s"PUT|$k|$v".getBytes(StandardCharsets.UTF_8)
    case Delete(k)       => s"DEL|$k".getBytes(StandardCharsets.UTF_8)
    case Get(k)          => s"GET|$k".getBytes(StandardCharsets.UTF_8)
    case Watch(pfx, ver) => s"WATCH|$pfx|$ver".getBytes(StandardCharsets.UTF_8)

  def decode(data: Array[Byte]): ConfigCommand =
    val parts = new String(data, StandardCharsets.UTF_8).split("\\|", -1)
    parts(0) match
      case "PUT"   => Put(parts(1), parts(2))
      case "DEL"   => Delete(parts(1))
      case "GET"   => Get(parts(1))
      case "WATCH" => Watch(parts(1), parts(2).toLong)
```

### Why this encoding matters

Every Raft command is serialized to `Array[Byte]` in the log. The encoding must be:

- **Deterministic**: same command → same bytes, always. No HashMap iteration order, no floating point, no timestamps generated at encode time.
- **Self-describing**: the decoder must know which variant it's looking at without external context.
- **Compact**: these bytes are replicated to every node and persisted to disk. In production, use Protocol Buffers or similar — we use pipe-delimited strings here for clarity.

## State Machine: The Heart of the Service

```scala
import cats.effect.{IO, Ref}
import raft.state.Log
import raft.spi.StateMachine

case class ConfigEntry(
  value: String,
  version: Long,      // monotonically increasing per-key version
  modifiedAt: Long    // the log index at which this key was last written
)

case class ConfigState(
  data: Map[String, ConfigEntry],
  globalVersion: Long,   // monotonic counter across all keys
  history: Vector[(Long, String, String)]  // (version, key, value) for watch
)

class ConfigStateMachine(stateRef: Ref[IO, ConfigState])
    extends StateMachine[IO, String]:

  def apply(log: Log): IO[String] =
    val cmd = ConfigCommand.decode(log.data)
    cmd match
      case ConfigCommand.Put(key, value) =>
        stateRef.modify { state =>
          val newVersion = state.globalVersion + 1
          val entry = ConfigEntry(value, newVersion, log.index)
          val newState = state.copy(
            data = state.data + (key -> entry),
            globalVersion = newVersion,
            history = state.history :+ (newVersion, key, value)
          )
          (newState, s"OK:$newVersion")
        }

      case ConfigCommand.Delete(key) =>
        stateRef.modify { state =>
          val newVersion = state.globalVersion + 1
          val newState = state.copy(
            data = state.data - key,
            globalVersion = newVersion,
            history = state.history :+ (newVersion, key, "")
          )
          (newState, s"DELETED:$newVersion")
        }

      case ConfigCommand.Get(key) =>
        stateRef.get.map { state =>
          state.data.get(key) match
            case Some(entry) => s"${entry.value}:${entry.version}"
            case None        => "NOT_FOUND"
        }

      case ConfigCommand.Watch(prefix, sinceVersion) =>
        stateRef.get.map { state =>
          val changes = state.history
            .filter((ver, k, _) => ver > sinceVersion && k.startsWith(prefix))
            .map((ver, k, v) => s"$k=$v@$ver")
            .mkString(",")
          if changes.isEmpty then "NO_CHANGES" else changes
        }

  def snapshot: IO[Array[Byte]] =
    stateRef.get.map { state =>
      val lines = state.data.map { (k, e) =>
        s"${k}|${e.value}|${e.version}|${e.modifiedAt}"
      }
      (s"V:${state.globalVersion}" +: lines.toVector)
        .mkString("\n")
        .getBytes(StandardCharsets.UTF_8)
    }

  def restore(data: Array[Byte]): IO[Unit] =
    val lines = new String(data, StandardCharsets.UTF_8).split("\n").toVector
    val version = lines.head.stripPrefix("V:").toLong
    val entries = lines.tail.map { line =>
      val parts = line.split("\\|")
      parts(0) -> ConfigEntry(parts(1), parts(2).toLong, parts(3).toLong)
    }.toMap
    stateRef.set(ConfigState(entries, version, Vector.empty))
```

### Deep dive: Version tracking

The `globalVersion` counter is critical. It serves three purposes:

1. **Optimistic concurrency control**: Clients read a key's version, then write conditionally: "set `db.host` to `new-host` only if version is still 7." If another client wrote in between, the version is now 8 and the conditional write is rejected.

2. **Watch efficiency**: Instead of diffing the entire key space, watches track "give me everything after version N." The `history` vector makes this a simple filter.

3. **Snapshot consistency**: The version tells you exactly which writes are included in a snapshot. After restoring from a snapshot with `globalVersion=42`, you know every write with version ≤42 is included.

> **Determinism note**: Notice that `Put` and `Delete` use `stateRef.modify` (atomic read-modify-write) while `Get` and `Watch` use `stateRef.get` (read-only). This is intentional — writes modify the state deterministically based on the log entry's position, while reads observe the state at whatever point they execute.

## Consistent Reads: Three Strategies

A configuration service that returns stale data is **worse than useless** — it's actively dangerous. If a client reads a stale database password after a rotation, connections fail. If it reads a stale feature flag, the wrong code path executes.

Here are three read strategies, in increasing order of consistency and decreasing order of performance:

### Strategy 1: Log Read (strongest, slowest)

Route the `Get` command through the Raft log. The read executes on every node after all prior writes, guaranteeing linearizability:

```scala
def getViaLogRead(key: String): IO[String] =
  val cmd = ConfigCommand.encode(ConfigCommand.Get(key))
  val entry = Log.Command(index = 0, term = 0, data = cmd)
  // Submit to leader → replicated → committed → applied → result
  raftNode.propose(entry)
```

**Trade-off**: Every read is a full Raft round-trip (leader → majority → commit → apply). For a config service, this is typically fine — config reads are infrequent relative to, say, database queries.

### Strategy 2: ReadIndex (consistent, one round-trip)

The leader confirms it's still the leader by checking with a quorum, then serves the read from its local state:

```scala
import raft.message.RaftMessage.ReadIndexRequest

def getViaReadIndex(key: String): IO[String] =
  for
    // Ask the leader to confirm its leadership
    requestId <- IO(java.util.UUID.randomUUID().toString)
    _         <- raftNode.submitReadIndex(ReadIndexRequest(requestId))

    // Wait for ReadIndexReady effect (leader confirmed)
    // Then read directly from the state machine
    result    <- stateMachine.directRead(key)
  yield result
```

**Trade-off**: One network round-trip (leader → majority ack), no disk write, no log entry. The read is linearizable but doesn't consume log space.

### Strategy 3: Lease Read (fastest, clock-dependent)

If the leader holds a valid lease (recent heartbeat quorum), serve reads immediately with no network round-trip:

```scala
import raft.effect.Effect.LeaseReadReady

def getViaLeaseRead(key: String): IO[String] =
  for
    requestId <- IO(java.util.UUID.randomUUID().toString)
    _         <- raftNode.submitLeaseRead(requestId)
    // If lease is valid → LeaseReadReady effect fires immediately
    // If lease expired → falls back to ReadIndex
    result    <- stateMachine.directRead(key)
  yield result
```

**Trade-off**: Zero-latency reads while the lease is valid, but correctness depends on bounded clock skew. If the leader's clock is fast by more than the `maxClockSkew` setting, a deposed leader could serve stale reads during its incorrectly extended lease window.

### Which strategy to use?

| Strategy | Latency | Throughput | Safety | Use when... |
|----------|---------|-----------|--------|-------------|
| **Log read** | ~2 RTT | Low | Always safe | Reads are rare, simplicity matters |
| **ReadIndex** | ~1 RTT | Medium | Always safe | Default choice for most config services |
| **Lease read** | ~0 RTT | High | Clock-dependent | Sub-millisecond reads needed, NTP is reliable |

For a configuration service, **ReadIndex** is the sweet spot — it's linearizable without clock assumptions and fast enough for config access patterns (typically <100 reads/sec per client).

## Watch Notifications: Reacting to Config Changes

The watch mechanism lets clients react to configuration changes in real-time. Here's how it works:

```scala
case class WatchStream(
  prefix: String,
  lastSeenVersion: Long,
  pollInterval: FiniteDuration = 500.millis
)

def watchConfig(watch: WatchStream): fs2.Stream[IO, (String, String, Long)] =
  fs2.Stream
    .fixedDelay[IO](watch.pollInterval)
    .evalMap { _ =>
      val cmd = ConfigCommand.Watch(watch.prefix, watch.lastSeenVersion)
      getViaReadIndex(watch.prefix) // consistent read of changes
    }
    .flatMap { response =>
      if response == "NO_CHANGES" then fs2.Stream.empty
      else
        val changes = response.split(",").map { change =>
          val parts = change.split("=|@")
          (parts(0), parts(1), parts(2).toLong) // (key, value, version)
        }
        fs2.Stream.emits(changes.toSeq)
    }
```

### Why not push-based notifications?

Production systems like etcd use **server-push** (gRPC streaming) for watch notifications. Our polling approach is simpler but has higher latency. In a real deployment, you would:

1. Maintain a per-client watch list on the leader
2. After each `CommitEntries` effect, check if any committed entry matches a watch prefix
3. Push the change to the watching client via the transport layer

The pure functional core makes this clean — watch evaluation is just another function applied to the committed entries, with no threading or synchronization concerns.

## Failure Scenarios

### Scenario 1: Leader crashes mid-write

```
t=0   Client sends PUT db.host=new-host to Leader (Node 1)
t=1   Leader appends to its log and sends AppendEntries to Nodes 2, 3
t=2   Node 2 acknowledges ── Leader CRASHES before committing
t=3   Node 3 didn't receive the AppendEntries
```

**What happens**: Node 2 or Node 3 is elected. If Node 2 wins (it has the entry), the write is committed when Node 2 appends its own no-op entry. If Node 3 wins (log is more up-to-date or equal), Node 3's log doesn't have the entry. The write is lost — but the client never received an acknowledgment, so from the client's perspective, the write failed.

**Lesson**: Writes are only durable after the leader responds with success. Clients must retry on timeout.

### Scenario 2: Network partition during read

```
t=0   Client reads db.host via ReadIndex on Leader (partition starts)
t=5   Leader sends heartbeat to confirm leadership — no responses
t=10  ReadIndex times out → ReadIndexRejected effect
```

**What happens**: With ReadIndex, the leader must confirm it's still the leader before serving a read. If it can't reach a quorum, the read is rejected. The client retries and discovers the new leader.

**Lesson**: ReadIndex is safe under partitions — it never serves stale data, but it may be temporarily unavailable.

### Scenario 3: Watch misses an update

```
t=0   Client watching prefix "db." with lastSeenVersion=5
t=1   PUT db.host=new-host (version 6) committed
t=2   PUT db.port=5433 (version 7) committed
t=3   Client polls — sees both changes (versions 6 and 7)
```

**What happens**: Because watches are version-based, the client never misses an update — it asks for "everything after version 5" and gets all changes. Even if the client was offline for hours, reconnecting and providing the last seen version retrieves all missed changes.

**Lesson**: Version-based watches are gap-free. The client only needs to track one number: the last version it processed.

## Production Considerations

### Snapshotting frequency

The `history` vector grows without bound. In production, truncate it during snapshotting:

```scala
def compactHistory(state: ConfigState, keepVersions: Int = 1000): ConfigState =
  if state.history.size > keepVersions then
    state.copy(history = state.history.takeRight(keepVersions))
  else state
```

Clients that fall behind by more than `keepVersions` must re-read the full key space.

### Key namespacing

Use hierarchical keys (like filesystem paths) for organization:

```
/services/api/db.host = "db-primary.internal"
/services/api/db.port = "5432"
/services/worker/max-concurrency = "16"
/feature-flags/new-checkout = "true"
```

Watches on `/services/api/` capture all API config changes without noise from worker or feature flag updates.

### Monitoring

Track these metrics:
- **Read latency by strategy** (log read vs ReadIndex vs lease)
- **Watch lag** (time between a write committing and the watching client receiving it)
- **Version growth rate** (if history grows too fast, increase snapshot frequency)
- **Key count** (a config service with >10,000 keys may need sharding via Multi-Raft Groups)

---

*Next: [Tutorial 2 — Leader Election for Microservices](02-leader-election-microservices.md) — using Raft purely for coordination, not data replication.*
