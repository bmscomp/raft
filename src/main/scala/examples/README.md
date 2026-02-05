# Examples

This directory contains detailed examples demonstrating real-world usage of the Functional RAFT Library.

## Examples Overview

| Example | Description | Key Concepts |
|---------|-------------|--------------|
| [KV Store](kvstore/) | Distributed key-value store | `StateMachine` implementation |
| [Lock Service](lock/) | Distributed locks with TTL | Lock ownership, expiration |
| [Counter](counter/) | Counter with custom codec | `MessageCodec[M]` implementation |
| [Cluster](cluster/) | 3-node cluster simulation | Leader election, state transitions |

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

**Run:** `sbt "runMain examples.kvstore.KVStoreExample"`

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

**Run:** `sbt "runMain examples.lock.DistributedLockExample"`

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

**Run:** `sbt "runMain examples.counter.CounterWithCodecExample"`

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

**Run:** `sbt "runMain examples.cluster.ThreeNodeClusterExample"`

---

## Running All Examples

```bash
# Run individual examples
sbt "runMain examples.kvstore.KVStoreExample"
sbt "runMain examples.lock.DistributedLockExample"
sbt "runMain examples.counter.CounterWithCodecExample"
sbt "runMain examples.cluster.ThreeNodeClusterExample"
```

---

## Creating Your Own Application

### Step 1: Implement StateMachine

```scala
class MyStateMachine[F[_]: Sync](state: Ref[F, MyState]) 
    extends StateMachine[F, MyResult]:
  
  def apply(log: Log): F[MyResult] = 
    // Decode command from log.data and apply to state
    
  def snapshot: F[Array[Byte]] = 
    // Serialize current state
    
  def restore(data: Array[Byte]): F[Unit] = 
    // Restore state from snapshot
```

### Step 2: Implement Transport (or use in-memory for testing)

```scala
class MyTransport[F[_]: Async] extends Transport[F, MyWireFormat]:
  def send(to: NodeId, msg: MyWireFormat): F[Unit] = ...
  def broadcast(msg: MyWireFormat): F[Unit] = ...
  def receive: Stream[F, (NodeId, MyWireFormat)] = ...
```

### Step 3: Create RaftNode

```scala
RaftNode[IO, MyWireFormat](
  config = RaftConfig.default(myNodeId),
  cluster = ClusterConfig.of(server1, server2, server3),
  transport = myTransport,
  logStore = myLogStore,
  stableStore = myStableStore,
  stateMachine = myFSM
).use { node =>
  node.start *> ...
}
```
