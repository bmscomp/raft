# Chapter 13: State of the Art — Raft Libraries Compared

*The Raft ecosystem is rich with implementations across Go, Rust, Java, and now Scala. This chapter surveys the major libraries, compares their architectures and features, and helps you choose the right one for your use case.*

---

## The Landscape

Since Raft was published in 2014, it has become the dominant consensus algorithm in modern infrastructure. Unlike Paxos, which has a single canonical description but many incompatible implementations, Raft's clear specification has produced a family of libraries with broadly compatible semantics but radically different architectural choices.

| Library | Language | Origin | First Release | Production Users |
|---------|----------|--------|---------------|-----------------|
| **etcd/raft** | Go | CoreOS / CNCF | 2014 | Kubernetes, etcd, CockroachDB |
| **HashiCorp Raft** | Go | HashiCorp | 2014 | Consul, Nomad, Vault |
| **tikv/raft-rs** | Rust | PingCAP | 2016 | TiKV, TiDB |
| **OpenRaft** | Rust | Databend | 2021 | Databend, Chroma |
| **SOFAJRaft** | Java | Ant Group | 2019 | SOFA, Nacos |
| **MicroRaft** | Java | Basri Kahveci | 2021 | Hazelcast (design heritage) |
| **Functional RAFT** | Scala 3 | This project | 2025 | — |

---

## Library Profiles

### etcd/raft (Go)

The most widely deployed Raft library in production. Originally built for etcd (the configuration store behind Kubernetes), it takes a **library approach** — providing the core consensus state machine while leaving I/O to the user.

**Architecture**: Like this library, etcd/raft models Raft as a deterministic state machine. You call `raft.Step()` with incoming messages, and it returns a `Ready` struct containing messages to send, entries to persist, and state changes to apply. The user's application loop processes these.

```
// etcd/raft conceptual loop (Go)
for {
    rd := <-node.Ready()
    saveToStorage(rd.HardState, rd.Entries)
    transport.Send(rd.Messages)
    applyCommitted(rd.CommittedEntries)
    node.Advance()
}
```

**Strengths**:
- Battle-tested at massive scale (every Kubernetes cluster runs it)
- Deterministic core enables rigorous testing
- Rich optimization surface: pipelining, batching, parallel disk writes, flow control
- Support for learner nodes and leadership transfer
- ReadIndex and lease-based reads for linearizable queries

**Weaknesses**:
- Go-only — no cross-platform story
- The `Ready` struct bundles everything together, making selective effect handling awkward
- Documentation is sparse; understanding requires reading the source
- No built-in observability or metrics; left to the integrator

**Best fit**: Building infrastructure-grade distributed systems in Go where you need maximum control and are willing to invest in integration work.

---

### HashiCorp Raft (Go)

A more **batteries-included** Raft library designed for easy embedding. Powers Consul (service mesh), Nomad (workload orchestrator), and Vault (secrets management).

**Architecture**: Unlike etcd/raft, HashiCorp Raft provides built-in transport, log storage, and snapshot management. You implement a `FSM` (finite state machine) interface and the library handles the rest.

```go
// HashiCorp Raft — user implements this
type FSM interface {
    Apply(*Log) interface{}
    Snapshot() (FSMSnapshot, error)
    Restore(io.ReadCloser) error
}
```

**Strengths**:
- Easy to get started — provide an FSM and you have consensus
- Built-in TCP transport, BoltDB-backed log store, file-based snapshots
- Non-voting server support
- Automatic snapshotting and log compaction
- Well-documented with clear API boundaries

**Weaknesses**:
- Less flexible than etcd/raft — the transport, storage, and timer integration points are less modular
- Tightly coupled to Go's concurrency model (goroutines)
- No pre-vote support (as of v1.x)
- Single-decree focus — Multi-Raft requires external coordination
- Performance ceiling is lower than etcd/raft for high-throughput scenarios

**Best fit**: Go applications where quick integration matters more than maximum performance. Ideal for services that need embedded consensus without building custom networking.

---

### tikv/raft-rs (Rust)

A faithful port of etcd/raft from Go to Rust, developed by PingCAP for TiKV — a distributed transactional key-value database.

**Architecture**: Mirrors etcd/raft's pure state machine design, with the `RawNode` struct serving the same role. Users process a `Ready` struct containing pending I/O. Supports both `rust-protobuf` and `prost` for message encoding.

**Strengths**:
- Production-proven since 2016 in TiKV (CNCF graduated project)
- Rust's memory safety eliminates entire categories of concurrency bugs
- Joint Consensus for safe membership changes
- Minimal, focused API — does one thing well
- High performance from Rust's zero-cost abstractions

**Weaknesses**:
- Consensus core only — you build everything else (networking, storage, timers)
- Steep integration effort; requires deep Raft understanding
- Rust's learning curve adds to the barrier
- Documentation is primarily the source code and TiKV's integration patterns

**Best fit**: Rust-based distributed databases and storage systems where performance and safety are paramount, and the team has Raft expertise.

---

### OpenRaft (Rust)

A modern, async-first Raft library for Rust that aims to be more complete and easier to use than tikv/raft-rs.

**Architecture**: Event-driven (no tick polling), with a unified `Raft<C>` API. Users implement traits for storage (`RaftLogStorage`, `RaftStateMachine`) and networking (`RaftNetwork`). The library manages the event loop internally.

**Strengths**:
- Async-native — built on Tokio from the ground up
- Event-driven, not tick-based — better resource usage and batching
- Fully pipelined log replication with congestion control
- Dynamic membership changes without downtime
- Built-in tracing instrumentation for distributed tracing
- Active development with frequent releases
- Leader lease support

**Weaknesses**:
- Pre-1.0 API — breaking changes are possible
- Younger than tikv/raft-rs with fewer production deployments
- Less battle-hardened under adversarial network conditions
- The unified API, while convenient, offers fewer customization points than the state-machine approach

**Best fit**: New Rust projects that want a more complete Raft solution without building their own event loop and I/O layer. Particularly good for async Rust ecosystems.

---

### SOFAJRaft (Java)

A production-grade Java implementation from Ant Group (Alibaba), designed for high-throughput, low-latency scenarios.

**Architecture**: A comprehensive framework that manages the full Raft lifecycle. Users implement `StateMachine` and configure the `NodeOptions`. Supports **Multi-Raft Group** — running many independent Raft groups within the same process, a pattern critical for sharded databases.

**Strengths**:
- Multi-Raft Group support — essential for database sharding
- Passed Jepsen consistency verification testing
- Replication pipeline for high throughput
- Priority-based semi-deterministic leader election
- Symmetric and asymmetric network partition tolerance
- Linearizable reads (ReadIndex + LeaseRead)
- Embedded distributed KV storage implementation
- Rich performance metrics and statistics
- Leadership transfer support

**Weaknesses**:
- Heavy framework — pulling in SOFAJRaft means adopting its threading model, lifecycle, and dependency tree
- Java-specific — no cross-compilation
- Documentation is primarily in Chinese (though improving)
- The framework approach makes custom I/O integration harder
- Limited community outside the Alibaba/Ant ecosystem

**Best fit**: Java-based distributed databases and services at scale, especially when Multi-Raft Group is needed. Ideal for teams in the Alibaba ecosystem or building sharded storage engines.

---

### MicroRaft (Java)

A lightweight, modular Java Raft library emphasizing minimalism. Inspired by the design heritage of Hazelcast's consensus layer.

**Architecture**: Similar philosophy to etcd/raft — provides the core algorithm with abstract interfaces for persistence, networking, serialization, and the state machine. Ships as a single small JAR with only a logging dependency.

**Strengths**:
- Extremely lightweight (~100KB JAR, single logging dependency)
- Clean modular design — implement interfaces, plug in your infrastructure
- Adaptive batching during replication
- Back pressure to prevent OOM on leaders and followers
- Parallel snapshot transfer
- Pre-vote and leader stickiness
- Linearizable quorum reads and lease-based local queries
- Monotonic local queries on followers
- Leadership transfer
- Parallel disk writes

**Weaknesses**:
- Small community and limited production track record
- No Multi-Raft Group support
- Less documentation and fewer examples than larger projects
- Single maintainer — bus factor risk
- No Jepsen-verified testing

**Best fit**: Java applications that want an embeddable, lightweight Raft core without the weight of SOFAJRaft. Good for learning and for systems that need tight control over the integration layer.

---

### Functional RAFT Library (Scala 3) — This Library

**Architecture**: Pure functional approach — `RaftLogic.onMessage()` returns `Transition(newState, effects)` with zero side effects. The effect ADT (`SendMessage`, `PersistHardState`, `CommitEntries`, etc.) describes all I/O as data, and your runtime interprets these effects using whatever infrastructure you choose.

**Strengths**:
- Purely functional core — deterministic, testable, composable
- Explicit effect ADT gives granular control over side-effect execution
- Type-safe with Scala 3 opaque types (`Term`, `LogIndex`, `NodeId`)
- Immutable state transitions — no shared mutable state
- Comprehensive feature set: Pre-Vote, joint consensus, linearizable reads (ReadIndex + lease), leadership transfer, batching, pipelining, parallel replication
- Pluggable SPI for all infrastructure concerns (Transport, LogStore, StableStore, StateMachine, TimerService)
- Cats Effect + FS2 integration for resource-safe runtimes
- Runs on JVM, Scala.js, and GraalVM native

**Weaknesses**:
- Scala ecosystem is smaller than Go, Rust, or Java
- No Multi-Raft Group support yet
- No Jepsen verification yet
- Young project — no production track record
- Requires understanding of functional programming patterns

**Best fit**: Scala/JVM projects that value type safety, testability, and functional programming. Excellent for systems where the consensus logic must be auditable, formally verifiable, or embedded in a larger functional architecture.

---

## Feature Comparison Matrix

| Feature | etcd/raft | HC Raft | tikv/raft-rs | OpenRaft | SOFAJRaft | MicroRaft | **This Library** |
|---------|:---------:|:-------:|:------------:|:--------:|:---------:|:---------:|:----------------:|
| **Pure state machine** | ✅ | ❌ | ✅ | ❌ | ❌ | ✅ | ✅ |
| **Leader election** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Log replication** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Snapshots** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Pre-Vote** | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Leader stickiness** | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| **Joint consensus** | ✅ | ❌ | ✅ | ✅ | ✅ | ❌ | ✅ |
| **ReadIndex** | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Lease-based reads** | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Leadership transfer** | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Learner/non-voting** | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ |
| **Witness nodes** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| **Batching** | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ |
| **Pipelining** | ✅ | ❌ | ❌ | ✅ | ✅ | ❌ | ✅ |
| **Parallel replication** | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ |
| **Multi-Raft Group** | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| **Jepsen-verified** | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| **Built-in transport** | ❌ | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ (SPI) |
| **Built-in storage** | ❌ | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ (SPI) |

---

## Architectural Spectrum

Libraries fall on a spectrum from **minimal core** to **complete framework**:

```
Minimal Core                                              Complete Framework
     ◄──────────────────────────────────────────────────────────►
     │                           │                              │
 etcd/raft                   OpenRaft                      SOFAJRaft
 tikv/raft-rs                                             HashiCorp Raft
 MicroRaft
 This Library
```

**Minimal core** libraries (etcd/raft, tikv/raft-rs, MicroRaft, this library) give you the consensus algorithm as a pure function or state machine. You build the networking, storage, and timers. This maximizes flexibility but increases integration effort.

**Complete frameworks** (HashiCorp Raft, SOFAJRaft) provide everything out of the box. You implement a state machine and the framework handles the rest. This minimizes time-to-working-system but limits customization.

**Middle ground** (OpenRaft) provides the core algorithm plus an async runtime, requiring you to implement storage and networking traits but managing the event loop internally.

## Decision Guide

### Choose etcd/raft when:
- You're building in Go and need maximum production pedigree
- You want the same technology that runs Kubernetes
- You're comfortable building your own I/O layer
- Throughput and latency matter (pipelining, batching, flow control)

### Choose HashiCorp Raft when:
- You want the fastest path to a working Raft cluster in Go
- Built-in transport and storage are acceptable
- You're building something similar to Consul or Vault
- You don't need Pre-Vote, ReadIndex, or joint consensus

### Choose tikv/raft-rs when:
- You're building a database or storage engine in Rust
- You need production-proven consensus at the core
- Your team has deep Raft and Rust expertise
- You want the exact semantics of etcd/raft in Rust

### Choose OpenRaft when:
- You're building a new Rust project with async/Tokio
- You want a more complete solution than tikv/raft-rs
- You're comfortable with a pre-1.0 API
- You value event-driven architecture over tick-polling

### Choose SOFAJRaft when:
- You need Multi-Raft Group for database sharding
- You want Jepsen-verified correctness
- You're building a high-throughput Java system
- You're in the Alibaba/SOFA ecosystem

### Choose MicroRaft when:
- You want lightweight Raft in Java without heavy frameworks
- You need fine-grained control over the integration layer
- The small footprint (~100KB) matters for your deployment
- You want a clean, educational codebase to learn from

### Choose this library when:
- You're building in Scala or on the JVM with functional patterns
- You value testability and determinism above all else
- You want explicit effect management (visible, loggable, reorderable)
- You need to audit or formally verify the consensus logic
- Type safety (opaque types, sealed hierarchies) is important to your team
- You want to use Cats Effect / FS2 for your runtime

---

## Summary

The Raft ecosystem offers strong options for every language and architectural preference. The key differentiator is **where the boundary sits** between the library's responsibility and yours:

| If you want... | Choose... |
|----------------|-----------|
| Maximum production pedigree | **etcd/raft** |
| Fastest time to working cluster | **HashiCorp Raft** or **SOFAJRaft** |
| Rust + maximum control | **tikv/raft-rs** |
| Rust + async-first | **OpenRaft** |
| Java + Multi-Raft | **SOFAJRaft** |
| Java + lightweight | **MicroRaft** |
| Scala + pure functions + type safety | **This library** |
| Correctness verification | **SOFAJRaft** (Jepsen) |

---

*This concludes the book. You should now have both the theoretical foundation and practical skills to build reliable distributed systems with Raft.*
