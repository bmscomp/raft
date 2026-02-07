# Chapter 4: Design Philosophy

*Most Raft libraries bundle protocol logic with networking, storage, and timers. This library takes a radically different approach: pure functions that return effects, giving you complete control over how consensus integrates with your system.*

---

## The Pure State Machine Pattern

The central insight of this library is that every Raft protocol event can be modeled as a **pure function**:

```
f(currentState, message) → (newState, effects)
```

There are no sockets, no threads, no disk writes inside the consensus engine. The function receives the current node state and an incoming event (an RPC, a timeout, a client request) and returns two things:

1. The **new state** of the node
2. A **list of effects** — descriptions of work to be done

The runtime — which you control — is responsible for executing those effects.

```
┌──────────────────────────────────┐
│         Your Runtime             │
│                                  │
│  ┌──────────────────────────┐    │
│  │  Pure Logic (RaftLogic)  │    │
│  │                          │    │
│  │  onMessage(state, msg)   │    │
│  │    → Transition(         │    │
│  │        newState,         │    │
│  │        effects           │    │
│  │      )                   │    │
│  └──────────────────────────┘    │
│         │                        │
│         ▼                        │
│  ┌──────────────────────────┐    │
│  │    Effect Interpreter    │    │
│  │                          │    │
│  │  Broadcast → transport   │    │
│  │  Persist   → disk        │    │
│  │  Commit    → state mach. │    │
│  │  Timer     → scheduler   │    │
│  └──────────────────────────┘    │
└──────────────────────────────────┘
```

### Why This Matters

**Testability**: Every protocol transition is a pure function call. You can test election logic, log conflict resolution, and commit tracking without starting a server, opening a socket, or waiting for a timer. Run a million test cases in milliseconds.

**Portability**: The same logic runs on JVM, JavaScript (via Scala.js), or GraalVM native. There's no dependency on a specific networking stack, filesystem, or timer implementation.

**Composability**: You choose how to wire the pieces together. Use gRPC for transport, RocksDB for storage, your existing scheduler for timers. Or use the built-in in-memory implementations for testing.

**Determinism**: Given the same inputs, the pure functions always produce the same outputs. This makes it possible to record and replay protocol traces, reproduce bugs, and formally verify invariants.

## Comparison with Traditional Approaches

| Aspect | Traditional (e.g., HashiCorp Raft) | This Library |
|--------|-----------------------------------|-------------|
| **Transport** | Built-in TCP, tightly coupled | You provide via SPI |
| **Storage** | Built-in BoltDB/badger | You provide via SPI |
| **Timers** | Internal goroutines/threads | You provide via SPI |
| **Testing** | Requires mock servers, timeouts | Pure function calls |
| **Portability** | Go runtime only | Any Scala platform |
| **Effect system** | Implicit side effects | Explicit effect descriptors |

## Type Safety with Opaque Types

The library uses Scala 3 opaque types to prevent accidental misuse of primitive values:

```scala
opaque type Term     = Long
opaque type LogIndex = Long
opaque type NodeId   = String
```

This means:
- You cannot accidentally compare a `Term` with a `LogIndex`
- You cannot pass a raw `String` where a `NodeId` is expected
- The runtime representation is zero-overhead (just a `Long` or `String`)

```scala
val term: Term = Term(5)
val index: LogIndex = LogIndex(10)
// term == index  → compile error! Different types.
```

## Immutability

All state objects are **immutable case classes**. State transitions return new instances rather than mutating existing ones:

```scala
// NodeState is a sealed trait with immutable case classes
case class Follower(term: Long, votedFor: Option[NodeId], leaderId: Option[NodeId])
case class Candidate(term: Long, votesReceived: Set[NodeId])
case class Leader(term: Long, matchIndex: Map[NodeId, Long], ...)
```

There are no `var`s in the protocol logic, no shared mutable state, no synchronization concerns. The runtime holds the current state in an atomic reference (`Ref[F, NodeState]`) and updates it after each transition.

## The Effects Pattern

Effects are an **algebraic data type** — a sealed hierarchy of case classes that describe every possible side effect:

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

A typical effect interpreter:

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

This pattern gives you:
- **Observability**: log every effect before executing it
- **Batching**: combine multiple `PersistHardState` + `AppendLogs` into a single disk write
- **Ordering control**: execute effects in a specific order (persist before broadcast)
- **Error handling**: retry network failures without re-running the protocol logic

## Summary

| Principle | Implementation |
|-----------|---------------|
| **Pure functions** | `RaftLogic.onMessage` returns `Transition(state, effects)` |
| **Explicit effects** | `Effect` ADT describes all side effects |
| **Type safety** | Opaque types for `Term`, `LogIndex`, `NodeId` |
| **Immutability** | All state objects are `case class`es |
| **Zero coupling** | No runtime dependencies in protocol logic |

---

*Next: [Chapter 5 — Core API](05-core-api.md) walks through every type and function in the library's public API.*
