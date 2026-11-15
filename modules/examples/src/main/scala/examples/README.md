# Examples

This directory contains detailed examples demonstrating real-world usage of the Functional RAFT Library.

## Examples Overview

| Example | Description | Key Concepts |
|---------|-------------|--------------|
| [RawNode Host Loop](rawnode/) | Zero-dependency `RawNode` on JDK Virtual Threads | Sans-I/O `Ready` / `LightReady` host loop (zero Cats Effect) |
| [Deterministic Simulation](simulation/) | Seeded single-threaded 5-node chaos simulator | `RawNode`, packet drop/reorder, partitions, bit-for-bit replay |
| [ReadIndex & LeaseRead](protocol/) | Quorum-verified vs clock-bounded linearizable reads | `readIndex`, `ReadState`, `CheckQuorum` lease step-down |
| [Leadership Transfer](protocol/) | Zero-downtime leader handoff (§3.10) | `transferLeader`, catch-up replication, `TimeoutNow` |
| [Joint Consensus](cluster/) | Safe 3-voter → 5-voter membership migration (§6) | Dual-quorum `JointConfig`, `C_old,new` → `C_new` |
| [KV Store](kvstore/) | Distributed key-value store | `StateMachine` implementation |
| [Lock Service](lock/) | Distributed locks with TTL | Lock ownership, expiration |
| [Counter](counter/) | Counter with custom codec | `MessageCodec[M]` implementation |
| [Cluster](cluster/) | 3-node cluster simulation | Leader election, state transitions |
| [Pre-Vote](protocol/) | Pre-vote election protocol | Two-phase election, term inflation prevention |
| [Log Matching](protocol/) | Log consistency demonstration | `prevLogIndex`/`prevLogTerm` checks |
| [Commit Tracking](protocol/) | Commit index advancement | Majority-based commits, `CommitEntries` |
| [Timer Service](protocol/) | Election and heartbeat timers | `TimerService` SPI, timeout handling |
| [Distributed Counter](distributed/) | Multi-node counter | Consensus-driven increments |
| [Raft Transaction](distributed/) | Distributed transactions | Multi-key atomic operations |
| [Distributed Transaction](distributed/) | Transaction coordinator | Two-phase commit over Raft |
| [Multi-Group](distributed/) | Multi-Raft groups | `MultiRaftNode`, `GroupEnvelope` |

---

## 1. Distributed Key-Value Store

**File:** `examples/kvstore/KVStoreExample.scala`

A replicated key-value store demonstrating:
- Implementing `StateMachine[F, R]` trait
- Encoding/decoding commands to bytes
- Snapshot and restore functionality

```scala
// Create state machine
val fsm = KVStateMachine[IO]

// Commands are replicated via RAFT, then applied
fsm.apply(Log.command(1, 1, KVCommand.encode(Put("key", "value"))))

// Reads can be done directly (for stale reads)
// or via ReadIndex for linearizable reads (future)
fsm.get("key") // => Some("value")
```

**Run:** `sbt "examples/runMain examples.kvstore.KVStoreExample"`

---

## 2. Distributed Lock Service

**File:** `examples/lock/DistributedLockExample.scala`

A distributed lock service demonstrating:
- Lock acquisition with TTL
- Lock ownership tracking
- Heartbeat-based lease renewal

```scala
val lockService = LockStateMachine[IO]

// Acquire lock
lockService.apply(Log.command(1, 1, 
  LockCommand.encode(Acquire("resource-a", "client-1", 30.seconds))))

// Check ownership
lockService.getOwner("resource-a") // => Some("client-1")

// Release lock
lockService.apply(Log.command(2, 1, 
  LockCommand.encode(Release("resource-a", "client-1"))))
```

**Run:** `sbt "examples/runMain examples.lock.DistributedLockExample"`

---

## 3. Counter with Custom Message Codec

**File:** `examples/counter/CounterWithCodecExample.scala`

Demonstrates implementing `MessageCodec[M]` for custom wire formats:

```scala
// Define your wire format
case class TextWireMessage(content: String)

// Implement the codec
given MessageCodec[TextWireMessage] with
  def encode(msg: RaftMessage): TextWireMessage = ...
  def decode(raw: TextWireMessage): Either[DecodeError, RaftMessage] = ...

// Use with Transport
val transport: Transport[IO, TextWireMessage] = ...
val raftTransport = Transport.withCodec(transport)
```

**Run:** `sbt "examples/runMain examples.counter.CounterWithCodecExample"`

---

## 4. 3-Node Cluster Simulation

**File:** `examples/cluster/ThreeNodeClusterExample.scala`

Simulates a complete leader election flow:

1. Three nodes start as Followers
2. Node-1 times out → becomes Candidate
3. Node-1 requests votes → receives majority
4. Node-1 becomes Leader → sends heartbeats
5. Followers acknowledge leadership

This example uses `RaftLogic` directly to demonstrate pure state transitions without actual networking.

**Run:** `sbt "examples/runMain examples.cluster.ThreeNodeClusterExample"`

---

## Running All 17 Examples

```bash
# Sans-I/O RawNode & Deterministic Simulation (zero Cats Effect dependencies)
sbt "examples/runMain examples.rawnode.RawNodeHostLoopExample"
sbt "examples/runMain examples.simulation.DeterministicSimulationExample"

# Protocol mechanics & cluster reconfiguration
sbt "examples/runMain examples.protocol.PreVoteExample"
sbt "examples/runMain examples.protocol.LogMatchingExample"
sbt "examples/runMain examples.protocol.CommitTrackingExample"
sbt "examples/runMain examples.protocol.TimerServiceExample"
sbt "examples/runMain examples.protocol.ReadIndexAndLeaseExample"
sbt "examples/runMain examples.protocol.LeadershipTransferExample"
sbt "examples/runMain examples.cluster.ThreeNodeClusterExample"
sbt "examples/runMain examples.cluster.JointConsensusExample"

# Application case studies & Multi-Raft
sbt "examples/runMain examples.kvstore.KVStoreExample"
sbt "examples/runMain examples.lock.DistributedLockExample"
sbt "examples/runMain examples.counter.CounterWithCodecExample"
sbt "examples/runMain examples.distributed.DistributedCounterExample"
sbt "examples/runMain examples.distributed.RaftTransactionExample"
sbt "examples/runMain examples.distributed.DistributedTransactionExample"
sbt "examples/runMain examples.distributed.MultiGroupExample"
```

---

## Embedding in Your System

### Option A: Zero-Dependency Sans-I/O (`raft-core`)

Drive `RawNode` directly on your own threads, storage, and RPC transport:

```scala
val store = MemStorage.create(ConfState(voters = Set(n1, n2, n3)))
val raw   = RawNode(RaftConfig.default(n1), store)

// Advance logical clock or step incoming RPCs/proposals
raw.tick()
if raw.hasReady then
  val ready = raw.ready()
  // 1. Send immediate leader AppendEntries (Ready.messages)
  // 2. Persist hardState + entries before sending Ready.persistedMessages
  store.persistReady(ready)
  // 3. Apply Ready.committedEntries to your state machine
  val light = raw.advance(ready)
  // 4. Send light.messages & apply light.committedEntries
  raw.advanceApply()
```

### Option B: Turnkey Cats Effect Runtime (`raft-runtime`)

#### Step 1: Implement `StateMachine`

```scala
class MyStateMachine[F[_]: Sync](state: Ref[F, MyState])
    extends StateMachine[F, MyResult]:

  def apply(log: Log): F[MyResult] =
    // Decode command from log.data and apply to state

  def snapshot: F[fs2.Stream[F, Byte]] =
    // Stream serialized state bytes

  def restore(data: fs2.Stream[F, Byte]): F[Unit] =
    // Restore state from snapshot stream
```

#### Step 2: Implement `Transport` (or use `GrpcTransport` / `InMemTransport`)

```scala
class MyTransport[F[_]: Async] extends Transport[F, MyWireFormat]:
  def send(to: NodeId, msg: MyWireFormat): F[Unit] = ...
  def sendBatch(to: NodeId, msgs: Seq[MyWireFormat]): F[Unit] = ...
  def broadcast(msg: MyWireFormat): F[Unit] = ...
  def receive: fs2.Stream[F, (NodeId, MyWireFormat)] = ...
  def shutdown: F[Unit] = ...
```

#### Step 3: Create `RaftNode`

```scala
RaftNode[IO](
  config = RaftConfig.default(myNodeId),
  cluster = ClusterConfig(Map(n1 -> NodeRole.Voter, n2 -> NodeRole.Voter, n3 -> NodeRole.Voter)),
  transport = Transport.withCodec(myTransport),
  logStore = myLogStore,
  stableStore = myStableStore,
  stateMachine = myFSM,
  timerService = myTimerService
).use { node =>
  node.start *> node.propose(bytes) *> ...
}
```
