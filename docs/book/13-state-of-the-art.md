# Chapter 13: State of the Art — Raft Libraries Compared

*The Raft ecosystem is rich with implementations across Go, Rust, Java, and now Scala. This chapter surveys the major libraries, compares their architectures and feature sets, and helps you choose the right one for your use case. The differences are not just about language — they reflect fundamentally different design philosophies about where the boundary should sit between the library's responsibility and yours.*

---

## The Landscape

Since Raft was published by Ongaro and Ousterhout in 2014, it has become the dominant consensus algorithm in modern infrastructure. Unlike Paxos, which has a single canonical description (Lamport, 1998) but many incompatible implementations with subtle semantic differences, Raft's clear specification — with its explicit leader, sequential log, and well-defined safety properties — has produced a family of libraries with broadly compatible semantics but radically different **architectural choices**.

The fundamental architectural question each library answers differently is: **how much of the system does the library own?** At one extreme, the library is a pure state machine that you feed messages into and read effects from. At the other, it's a complete framework that manages networking, storage, and timers internally.

| Library | Language | Origin | First Release | Notable Production Users |
|---------|----------|--------|---------------|------------------------|
| **etcd/raft** | Go | CoreOS / CNCF | 2014 | Kubernetes, etcd, CockroachDB, TiDB |
| **HashiCorp Raft** | Go | HashiCorp | 2014 | Consul, Nomad, Vault |
| **tikv/raft-rs** | Rust | PingCAP | 2016 | TiKV, TiDB |
| **OpenRaft** | Rust | Databend | 2021 | Databend, Chroma |
| **SOFAJRaft** | Java | Ant Group (Alibaba) | 2019 | SOFA Stack, Nacos |
| **MicroRaft** | Java | Basri Kahveci | 2021 | Design heritage from Hazelcast |
| **Functional RAFT** | Scala 3 | This project | 2025 | — |

---

## Library Profiles

### etcd/raft (Go)

The most widely deployed Raft library in production and arguably the most battle-tested consensus implementation in existence. Originally built for etcd (the distributed configuration store that underpins every Kubernetes cluster), it takes a **library approach** — providing the core consensus state machine while leaving all I/O to the user.

**Architecture**: Like this library, etcd/raft models Raft as a deterministic state machine. You call `raft.Step()` with incoming messages, and it returns a `Ready` struct containing messages to send, entries to persist, and state changes to apply. The user's application loop processes these "effects" (though etcd/raft doesn't use that term):

```go
// etcd/raft conceptual loop (Go)
for {
    rd := <-node.Ready()
    saveToStorage(rd.HardState, rd.Entries)
    transport.Send(rd.Messages)
    applyCommitted(rd.CommittedEntries)
    node.Advance()
}
```

This pattern should look familiar — it's the same loop from Chapter 7, expressed in Go instead of Scala. The `Ready` struct is etcd/raft's equivalent of our `Transition(newState, effects)`.

**Strengths**:
- Battle-tested at enormous scale (literally every Kubernetes cluster in the world runs it)
- Deterministic core enables rigorous testing (the same approach as this library)
- Rich optimization surface: pipelining, batching, parallel disk writes, flow control
- Support for learner nodes and leadership transfer
- ReadIndex and lease-based reads for linearizable queries
- Extensive use by CockroachDB proves it handles complex multi-group scenarios

**Weaknesses**:
- Go-only — no cross-platform compilation story
- The `Ready` struct bundles everything together, making selective effect handling awkward (you can't easily reorder or filter effects)
- Documentation is sparse; true understanding requires reading the source code and CockroachDB's integration
- No built-in observability or metrics; left entirely to the integrator

**Best fit**: Building infrastructure-grade distributed systems in Go where you need maximum production pedigree and are willing to invest heavily in integration work.

---

### HashiCorp Raft (Go)

A more **batteries-included** Raft library designed for rapid embedding. Powers three of HashiCorp's flagship products: Consul (service mesh), Nomad (workload orchestrator), and Vault (secrets management).

**Architecture**: Unlike etcd/raft, HashiCorp Raft provides built-in transport (TCP), log storage (BoltDB), and snapshot management (file-based). You implement a `FSM` (finite state machine) interface — essentially the same as our `StateMachine` SPI from Chapter 6 — and the library handles the rest:

```go
// HashiCorp Raft — user implements this interface
type FSM interface {
    Apply(*Log) interface{}
    Snapshot() (FSMSnapshot, error)
    Restore(io.ReadCloser) error
}
```

**Strengths**:
- Easiest path to a working Raft cluster in Go — provide an FSM and you have consensus
- Built-in TCP transport, BoltDB-backed log store, file-based snapshots
- Non-voting server support (similar to our Learner role)
- Automatic snapshotting and log compaction
- Well-documented with clear API boundaries

**Weaknesses**:
- Less flexible than etcd/raft — the transport, storage, and timer integration points are less modular
- Tightly coupled to Go's goroutine concurrency model
- No pre-vote support (as of v1.x), making it susceptible to disruptive rejoining nodes
- Single Raft group focus — Multi-Raft requires external coordination
- Performance ceiling is lower than etcd/raft for high-throughput scenarios (single-threaded log processing)

**Best fit**: Go applications where quick integration matters more than maximum performance or advanced features. Ideal for services that need embedded consensus without building custom networking.

---

### tikv/raft-rs (Rust)

A faithful port of etcd/raft from Go to Rust, developed by PingCAP for TiKV — a distributed transactional key-value database and CNCF graduated project.

**Architecture**: Mirrors etcd/raft's pure state machine design, with the `RawNode` struct serving the same role as Go's `raft.Node`. Users process a `Ready` struct containing pending I/O. Supports both `rust-protobuf` and `prost` for message serialization.

**Strengths**:
- Production-proven since 2016 powering TiKV (CNCF graduated project backing TiDB)
- Rust's memory safety guarantees eliminate entire categories of concurrency bugs (data races, use-after-free)
- Joint Consensus for safe membership changes
- Minimal, focused API — a direct translation of etcd/raft's well-understood model
- High performance from Rust's zero-cost abstractions (no garbage collection pauses)

**Weaknesses**:
- Consensus core only — you build everything else (networking, storage, timers)
- Steep integration effort; requires deep understanding of both Raft and Rust
- Rust's learning curve (ownership, lifetimes, borrow checker) adds to the barrier
- Documentation is primarily the source code and TiKV's integration as the reference implementation

**Best fit**: Rust-based distributed databases and storage systems where performance, memory safety, and correctness are paramount — and the team has deep Raft and Rust expertise.

---

### OpenRaft (Rust)

A modern, async-first Raft library for Rust that aims to be more complete and easier to integrate than tikv/raft-rs.

**Architecture**: Event-driven (no tick polling), with a unified `Raft<C>` API. Users implement traits for storage (`RaftLogStorage`, `RaftStateMachine`) and networking (`RaftNetwork`). Unlike etcd/raft and tikv/raft-rs, OpenRaft manages the event loop internally — similar to HashiCorp Raft's approach.

**Strengths**:
- Async-native — built on Tokio from the ground up, fitting naturally into the Rust async ecosystem
- Event-driven rather than tick-based — better resource usage and natural batching
- Fully pipelined log replication with congestion control
- Dynamic membership changes without downtime
- Built-in `tracing` instrumentation for distributed tracing integration
- Leader lease support for low-latency reads
- Active development with frequent releases and responsive maintainers

**Weaknesses**:
- Pre-1.0 API — breaking changes are possible between minor versions
- Younger than tikv/raft-rs with fewer production deployments
- Less battle-hardened under adversarial network conditions and extended partitions
- The unified API, while convenient, offers fewer customization points than the pure state-machine approach

**Best fit**: New Rust projects that want a more complete Raft solution without building a custom event loop and I/O layer. Particularly well-suited for async Rust ecosystems using Tokio.

---

### SOFAJRaft (Java)

A production-grade Java implementation from Ant Group (part of Alibaba), designed for high-throughput, low-latency scenarios at massive scale.

**Architecture**: A comprehensive framework that manages the full Raft lifecycle. Users implement `StateMachine` and configure `NodeOptions`. The standout feature is **Multi-Raft Group** support — running many independent Raft groups within the same process, a pattern critical for sharded databases (each shard runs its own Raft group).

**Strengths**:
- **Multi-Raft Group support** — essential for database sharding at scale
- **Jepsen-verified** — has passed rigorous independent consistency testing
- Replication pipeline for high throughput
- Priority-based semi-deterministic leader election
- Symmetric and asymmetric network partition tolerance
- Linearizable reads (both ReadIndex and LeaseRead)
- Embedded distributed KV storage implementation for quick prototyping
- Rich performance metrics and statistics
- Leadership transfer support

**Weaknesses**:
- Heavy framework — pulling in SOFAJRaft means adopting its threading model, lifecycle, and dependency tree
- Java-specific — no cross-compilation story
- Documentation is primarily in Chinese (English docs are improving but incomplete)
- The framework approach makes custom I/O integration harder than library approaches
- Limited community outside the Alibaba/Ant ecosystem

**Best fit**: Java-based distributed databases and services at scale, especially when Multi-Raft Group is needed for sharding. Ideal for teams in the Alibaba ecosystem or building sharded storage engines.

---

### MicroRaft (Java)

A lightweight, modular Java Raft library emphasizing minimalism and clean abstractions. Inspired by the design heritage of Hazelcast's consensus layer.

**Architecture**: Similar philosophy to etcd/raft — provides the core algorithm with abstract interfaces for persistence, networking, serialization, and the state machine. Ships as a single small JAR with only a logging dependency.

**Strengths**:
- Extremely lightweight (~100KB JAR, single logging dependency)
- Clean modular design — implement interfaces, plug in your infrastructure
- Adaptive batching during replication
- Back-pressure mechanisms to prevent OOM on leaders and followers
- Parallel snapshot transfer
- Pre-vote and leader stickiness
- Linearizable quorum reads and lease-based local queries
- Monotonic local queries on followers (a unique feature)
- Leadership transfer
- Parallel disk writes

**Weaknesses**:
- Small community and limited production track record
- No Multi-Raft Group support
- Less documentation and fewer examples than larger projects
- Single maintainer (bus factor risk)
- No Jepsen-verified testing

**Best fit**: Java applications that want an embeddable, lightweight Raft core without the weight of SOFAJRaft. Good for learning the algorithm and for systems that need tight control over the integration layer.

---

### Functional RAFT Library (Scala 3) — This Library

**Architecture**: Pure functional approach — `RaftLogic.onMessage()` returns `Transition(newState, effects)` with zero side effects. The effect ADT (`SendMessage`, `PersistHardState`, `CommitEntries`, etc.) describes all I/O as data, and your runtime interprets these effects using whatever infrastructure you choose. This is the purest expression of the "library as state machine" philosophy.

**Strengths**:
- **Purely functional core** — deterministic, testable, composable, auditable
- **Explicit effect ADT** gives granular control over side-effect execution (effects can be logged, reordered, filtered, or batched)
- **Type-safe** with Scala 3 opaque types (`Term`, `LogIndex`, `NodeId`)
- **Immutable state transitions** — no shared mutable state, no data races possible
- **Comprehensive feature set**: Pre-Vote, joint consensus, linearizable reads (ReadIndex + lease), leadership transfer, learners, witnesses, batching, pipelining, parallel replication
- **Pluggable SPI** for all infrastructure concerns (Transport, LogStore, StableStore, StateMachine, TimerService)
- **Cats Effect + FS2 integration** for resource-safe, cancellation-aware runtimes
- **Multi-platform**: runs on JVM, Scala.js, and GraalVM native image

**Weaknesses**:
- Scala ecosystem is smaller than Go, Rust, or Java
- No Multi-Raft Group support yet
- No Jepsen verification yet
- Young project — no production track record
- Requires familiarity with functional programming patterns (effects-as-data, ADTs, referential transparency)

**Best fit**: Scala/JVM projects that value type safety, testability, and functional programming. Excellent for systems where the consensus logic must be auditable, formally verifiable, or embedded in a larger functional architecture (e.g., alongside Cats Effect, http4s, or ZIO).

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

## The Architectural Spectrum

Libraries fall on a spectrum from **minimal core** (you build everything) to **complete framework** (it handles everything):

```
Minimal Core                                              Complete Framework
     ◄──────────────────────────────────────────────────────────►
     │                           │                              │
 etcd/raft                   OpenRaft                      SOFAJRaft
 tikv/raft-rs                                             HashiCorp Raft
 MicroRaft
 This Library
```

**Minimal core** libraries (etcd/raft, tikv/raft-rs, MicroRaft, this library) give you the consensus algorithm as a pure function or state machine. You build the networking, storage, and timers. This maximizes flexibility — you can use any network stack, any storage engine, any timer implementation — but increases integration effort. It also makes the library **testable in isolation**, which is why this end of the spectrum tends to produce more thoroughly tested consensus logic.

**Complete frameworks** (HashiCorp Raft, SOFAJRaft) provide everything out of the box. You implement a state machine and the framework handles the rest. This minimizes time-to-working-system but limits customization. Want to use QUIC instead of TCP? Write to RocksDB instead of BoltDB? With a framework, you may be fighting the design.

**Middle ground** (OpenRaft) provides the core algorithm plus an async runtime, requiring you to implement storage and networking traits but managing the event loop internally.

> **Note — Neither end is "better."** The right choice depends on your team's expertise and project requirements. If you're building a database where consensus is the core, the control of a minimal library is essential. If you're adding consensus to an existing service, the convenience of a framework is hard to beat.

## Decision Guide

### Choose etcd/raft when:
- You're building in Go and need maximum production pedigree
- You want the same technology that powers Kubernetes
- You're comfortable building your own I/O layer
- Throughput and latency matter (pipelining, batching, flow control are well-tested)

### Choose HashiCorp Raft when:
- You want the fastest path to a working Raft cluster in Go
- Built-in transport and storage are acceptable for your use case
- You're building something architecturally similar to Consul or Vault
- You don't need Pre-Vote, ReadIndex, or joint consensus

### Choose tikv/raft-rs when:
- You're building a database or storage engine in Rust
- You need production-proven consensus at the core of your architecture
- Your team has deep Raft and Rust expertise
- You want the exact, well-understood semantics of etcd/raft in Rust

### Choose OpenRaft when:
- You're building a new Rust project in the async/Tokio ecosystem
- You want a more complete solution than tikv/raft-rs
- You're comfortable with a pre-1.0 API that may have breaking changes
- You value event-driven architecture over tick-polling

### Choose SOFAJRaft when:
- You need Multi-Raft Group for database sharding
- You want Jepsen-verified correctness guarantees
- You're building a high-throughput Java system at scale
- You're in or adjacent to the Alibaba/SOFA ecosystem

### Choose MicroRaft when:
- You want lightweight Raft in Java without adopting a heavy framework
- You need fine-grained control over the integration layer
- The small footprint (~100KB) matters for your deployment
- You want a clean, readable codebase to study the algorithm

### Choose this library when:
- You're building in Scala or on the JVM with functional programming patterns
- You value **testability and determinism** above all else
- You want **explicit effect management** (effects are visible, loggable, reorderable data)
- You need to audit or formally verify the consensus logic
- **Type safety** (opaque types, sealed hierarchies, exhaustive pattern matching) is important to your team
- You want to use Cats Effect / FS2 for your runtime infrastructure

---

## Summary

The Raft ecosystem offers strong options for every language and architectural preference. The key differentiator is **where the boundary sits** between the library's responsibility and yours:

| If you want... | Choose... |
|----------------|-----------|
| Maximum production pedigree | **etcd/raft** |
| Fastest time to working cluster | **HashiCorp Raft** or **SOFAJRaft** |
| Rust with maximum control | **tikv/raft-rs** |
| Rust with async-first design | **OpenRaft** |
| Java with Multi-Raft sharding | **SOFAJRaft** |
| Java, lightweight and modular | **MicroRaft** |
| Scala, pure functions, type safety | **This library** |
| Independent correctness verification | **SOFAJRaft** (Jepsen-tested) |

---

*This concludes Part V. Continue to [Chapter 14 — Troubleshooting & Operational Pitfalls](14-troubleshooting.md) for production diagnostics and common failure mode solutions, or jump to [Appendix A — Quick Reference](appendix-a-quick-reference.md) for a one-page lookup of all library types and APIs.*

