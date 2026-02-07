# Chapter 4: Design Philosophy

*Most Raft libraries mix protocol logic with networking, disk I/O, and timer management into a single tangled codebase. This library takes a fundamentally different approach — one borrowed from the world of functional programming — that makes it radically easier to test, port, and reason about. If you've ever wondered why testing distributed systems is so painful, this chapter explains the root cause and how to fix it.*

---

## The Insight: Side Effects Are the Enemy

Here's a question: why is it hard to test a typical Raft implementation? Think about what you'd need to do to test a single leader election:

1. Start three server processes (or threads simulating them)
2. Set up network connections between them (TCP sockets, gRPC channels, or mock transports)
3. Configure timers with specific timeouts
4. Wait for one server's election timer to fire
5. Observe the messages being sent over the network
6. Verify that the correct server becomes leader

That's a lot of infrastructure for testing about 30 lines of protocol logic. You're spending 90% of the effort on plumbing and 10% on the actual algorithm. Worse, the tests are slow (network timeouts), flaky (timer races), and hard to debug (interleaved log output from three threads).

The root cause is **side effects**. In traditional implementations, the protocol logic directly calls `socket.send()`, `disk.write()`, and `timer.schedule()`. These calls are tangled into the logic itself, making it impossible to test the protocol without the infrastructure.

This library eliminates that problem entirely.

## The Pure State Machine Pattern

The central architectural decision of this library is that every Raft protocol event is modeled as a **pure function**:

```
f(currentState, message) → (newState, effects)
```

Let's unpack what "pure" means here:

- **No side effects**: the function doesn't send messages, write to disk, or start timers. It only computes.
- **Deterministic**: given the same `currentState` and `message`, it always returns the same `(newState, effects)`.
- **Total**: it's defined for every possible input combination — no exceptions, no crashes.

The function receives the current node state and an incoming event (an RPC from another node, a timer timeout, a client request). It returns two things:

1. The **new state** of the node (an immutable data structure)
2. A **list of effects** — descriptions of work the runtime should do

The runtime — which is a separate piece of code that **you** control — is responsible for actually executing those effects (sending messages, writing to disk, etc.).

```
┌──────────────────────────────────────┐
│           Your Runtime               │
│                                      │
│   ┌──────────────────────────────┐   │
│   │     Pure Logic (RaftLogic)   │   │
│   │                              │   │
│   │   (state, msg)               │   │
│   │       → (newState, effects)  │   │
│   │                              │   │
│   │   No I/O. No sockets.        │   │
│   │   No threads. No disk.       │   │
│   └──────────────┬───────────────┘   │
│                  │                   │
│                  ▼                   │
│   ┌──────────────────────────────┐   │
│   │     Effect Interpreter       │   │
│   │                              │   │
│   │   Broadcast(msg) → gRPC      │   │
│   │   Persist(term)  → RocksDB   │   │
│   │   Commit(idx)    → apply FSM │   │
│   │   ResetTimer     → scheduler │   │
│   └──────────────────────────────┘   │
└──────────────────────────────────────┘
```

> **Note — This pattern has deep roots.** The idea of separating pure computation from side-effect execution is central to functional programming. Haskell uses monads for this; Elm uses the "Elm Architecture" (Model-View-Update); Redux in JavaScript uses reducers and middleware. In the distributed systems world, the idea of modeling a protocol as a state machine fed by inputs was formalized by Fred Schneider (1990) and is the basis of TLA+ specifications. What's unusual here is applying it to an *implementation*, not just a spec.

### Why This Matters: Four Concrete Benefits

**1. Testability — test a million cases in seconds**

Since the protocol logic is a pure function, testing it is trivially easy. No servers, no sockets, no timers. Just call the function and check the output:

```scala
val follower = Follower(term = 1, votedFor = None, leaderId = None)
val result = RaftLogic.onMessage(follower, ElectionTimeout, config, 0, 0, 3)

assert(result.state.isInstanceOf[Candidate])
assert(result.state.term == 2)
assert(result.effects.exists(_.isInstanceOf[Broadcast]))
```

You can run this test a million times in under a second. Compare that to waiting for real election timeouts.

**2. Portability — same logic everywhere**

The protocol logic has zero dependencies on any runtime infrastructure. It doesn't import `java.net.Socket`, doesn't depend on a file system, doesn't reference a specific timer library. This means:

- Use the same protocol logic on JVM (production), Scala.js (browser visualization), or GraalVM native (performance-critical scenarios)
- Swap transport implementations without touching protocol code
- Move from one storage engine to another without risk of introducing protocol bugs

**3. Composability — you choose the infrastructure**

Want gRPC for transport? Use it. Prefer HTTP/2? Use that instead. RocksDB for storage? Great. SQLite? Also fine. Your existing centralized scheduler for timers? Go ahead. The protocol logic doesn't care — it just produces effects, and you decide how to execute them.

**4. Determinism — reproduce bugs, replay traces**

Since the pure functions are deterministic, you can:
- **Record** the sequence of `(state, message)` inputs that led to a bug
- **Replay** them in a unit test to reproduce the exact scenario
- **Formally verify** protocol invariants by model-checking the pure logic

This is enormously valuable for debugging distributed systems, where bugs often involve specific orderings of concurrent events that are hard to reproduce.

## Comparison with Traditional Approaches

To make the difference concrete, here's how this library compares with traditional Raft implementations:

| Aspect | Traditional (e.g., HashiCorp Raft) | This Library |
|--------|-----------------------------------|-------------|
| **Transport** | Built-in TCP with specific framing | You provide via SPI trait |
| **Storage** | Built-in BoltDB/BadgerDB | You provide via SPI trait |
| **Timers** | Internal goroutines/threads | You provide via SPI trait |
| **Testing** | Requires mock servers, real timeouts, sleeps | Pure function calls, instant |
| **Portability** | Go runtime only | Any Scala 3 target |
| **Effect system** | Implicit side effects (I/O happens inside the function) | Explicit effect descriptors (I/O happens outside) |
| **Debugging** | Log-based, non-deterministic reproduction | Record/replay, deterministic |

> **Note — HashiCorp Raft is not "wrong".** It's a battle-tested, production-grade implementation that powers Consul, Nomad, and Vault. The tight coupling to Go's networking and storage is a deliberate design choice for their ecosystem — it makes getting started easier. The trade-off is that testing, porting, and customizing the internals is harder. This library makes the opposite trade-off: more initial setup, but much more flexibility and testability.

## Type Safety with Scala 3 Opaque Types

The library uses Scala 3's opaque types to prevent a class of bugs that are common in distributed systems code: accidentally confusing one kind of integer or string with another.

```scala
opaque type Term     = Long
opaque type LogIndex = Long
opaque type NodeId   = String
```

At runtime, these are represented as plain `Long` or `String` values — **zero overhead**, no boxing, no wrapper objects. But at compile time, they are distinct types:

```scala
val term: Term = Term(5)
val index: LogIndex = LogIndex(10)

// This would be a COMPILE ERROR:
// val bad: Term = index   ← Can't assign a LogIndex to a Term!

// You also can't accidentally pass a raw String where a NodeId is expected
```

This is important because in a Raft implementation, you're constantly working with multiple `Long` values (terms, log indices, commit indices, match indices) that have fundamentally different semantics. Swapping two of them by accident is a bug that compiles and runs but produces subtly wrong behavior — exactly the kind of bug that's hardest to diagnose in a distributed system.

> **Note — Why not just use case class wrappers?** You could wrap `Long` in a case class (`case class Term(value: Long)`), but this introduces heap allocation and boxing overhead on every term comparison, which happens on every single RPC. Opaque types give you the safety at zero cost.

## Immutability: No Shared Mutable State

All state objects in the protocol logic are **immutable** Scala case classes. State transitions return new instances rather than mutating existing ones:

```scala
// Each node state is an immutable case class
case class Follower(
  term: Long,
  votedFor: Option[NodeId],
  leaderId: Option[NodeId]
)

case class Candidate(
  term: Long,
  votesReceived: Set[NodeId]
)

case class Leader(
  term: Long,
  matchIndex: Map[NodeId, Long],
  commitIndex: Long
  // ...
)
```

There are **no `var`s** in the protocol logic. No shared mutable state. No locks. No race conditions. The runtime holds the current state in a `Ref[F, NodeState]` (a Cats Effect atomic reference) and atomically swaps it after each transition:

```scala
// In the runtime event loop (simplified)
stateRef.modify { currentState =>
  val transition = RaftLogic.onMessage(currentState, msg, config, ...)
  (transition.state, transition.effects)
}.flatMap(executeEffects)
```

This `modify` call is atomic — even if multiple messages arrive concurrently, each transition sees a consistent snapshot of the state and produces a consistent update. No data races are possible.

## The Effects Pattern: Side Effects as Data

This is the most distinctive architectural choice in the library. Instead of performing side effects (I/O, network calls, disk writes), the protocol logic returns **descriptions** of side effects as ordinary data values. These descriptions form an algebraic data type (ADT):

```scala
enum Effect:
  case SendMessage(to: NodeId, msg: RaftMessage)
  case Broadcast(msg: RaftMessage)
  case PersistHardState(term: Long, votedFor: Option[NodeId])
  case AppendLogs(entries: Seq[Log])
  case TruncateLog(fromIndex: Long)
  case CommitEntries(upToIndex: Long)
  case BecomeLeader
  case ResetElectionTimer
  case ResetHeartbeatTimer
  // ... and more
```

Each `Effect` is just a case class — a piece of data that says "please do X". The protocol logic constructs these effects; a separate **effect interpreter** in the runtime actually executes them:

```scala
def executeEffects(effects: List[Effect], transport: RaftTransport[IO]): IO[Unit] =
  effects.traverse_ {
    case Broadcast(msg)            => transport.broadcast(msg)
    case SendMessage(to, msg)      => transport.send(to, msg)
    case PersistHardState(term, v) => stableStore.setCurrentTerm(term) *> stableStore.setVotedFor(v)
    case AppendLogs(entries)       => logStore.append(entries)
    case CommitEntries(upTo)       => applyCommittedEntries(upTo)
    case ResetElectionTimer        => timerService.resetElectionTimer
    case ResetHeartbeatTimer       => timerService.resetHeartbeatTimer
    case _                         => IO.unit
  }
```

### What Does This Buy You?

The effect interpreter is **your code**, which means you have total control over how effects are executed:

- **Observability**: log every effect before executing it. In production, this gives you a complete audit trail of every protocol decision.
- **Batching**: combine multiple `PersistHardState` + `AppendLogs` effects into a single disk write for better throughput.
- **Ordering**: always persist to disk **before** broadcasting messages. This ensures that if the node crashes between persisting and broadcasting, it can recover correctly.
- **Error handling**: if `transport.send()` fails, retry without re-running the protocol logic. The effects are already computed — you just need to retry the I/O.
- **Testing**: in tests, collect effects into a list and make assertions about them. No mock frameworks needed.

> **Note — Command/Query Separation:** If you're familiar with design patterns, the effects pattern is a specific instance of the Command pattern (GoF, 1994) combined with Command-Query Responsibility Segregation (CQRS). The protocol logic is the "query" side — it computes what should happen. The effect interpreter is the "command" side — it makes it happen. This separation is what makes the system so testable and flexible.

## Summary

| Design Principle | How It's Implemented | Why It Matters |
|-----------------|---------------------|---------------|
| **Pure functions** | `RaftLogic.onMessage` returns `Transition(state, effects)` with no I/O | Testable, deterministic, reproducible |
| **Explicit effects** | `Effect` ADT describes all side effects as data | Observable, batchable, retryable |
| **Type safety** | Opaque types for `Term`, `LogIndex`, `NodeId` | Catches misuse at compile time, zero runtime cost |
| **Immutability** | All state is immutable `case class` values | No locks, no races, no shared mutable state |
| **Zero coupling** | Protocol logic has no runtime dependencies | Portable across platforms and infrastructure |

---

*Next: [Chapter 5 — Core API](05-core-api.md) walks through every type and function in the library's public API, showing how the design philosophy translates into concrete code.*
