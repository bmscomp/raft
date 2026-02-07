# Raft: Theory and Practice

*A comprehensive guide to consensus algorithms, the Raft protocol, and building distributed systems with the Functional RAFT Library.*

---

## About This Book

This book bridges the gap between distributed systems theory and hands-on implementation. It starts with the fundamental problems that consensus algorithms solve, builds up a deep understanding of the Raft protocol, and then walks through four real-world case studies using the Functional RAFT Library for Scala 3.

Whether you're learning about distributed consensus for the first time or building a production system that needs strong consistency, this book gives you both the "why" and the "how."

---

## Table of Contents

### Part I — Foundations

| # | Chapter | Topics |
|---|---------|--------|
| 1 | [Why Consensus?](01-why-consensus.md) | The core problem, FLP impossibility, CAP theorem, Paxos → Raft |
| 2 | [Raft Fundamentals](02-raft-fundamentals.md) | Terms, leader election, log replication, safety properties |
| 3 | [Advanced Raft Theory](03-raft-advanced-theory.md) | Pre-Vote, joint consensus, linearizable reads, leadership transfer |

### Part II — Library Architecture

| # | Chapter | Topics |
|---|---------|--------|
| 4 | [Design Philosophy](04-design-philosophy.md) | Pure state transitions, the effects pattern, type safety |
| 5 | [Core API](05-core-api.md) | `RaftLogic`, `NodeState`, `Effect`, `ClusterConfig` |
| 6 | [The SPI Layer](06-spi-layer.md) | Transport, LogStore, StableStore, StateMachine, TimerService |

### Part III — Building with the Library

| # | Chapter | Topics |
|---|---------|--------|
| 7 | [Getting Started](07-getting-started.md) | Setup, first election, effect interpreter loop |
| 8 | [Log Replication in Practice](08-log-replication-practice.md) | Conflict resolution, commit advancement, batching, pipelining |

### Part IV — Case Studies

| # | Chapter | Topics |
|---|---------|--------|
| 9 | [Distributed Key-Value Store](09-case-distributed-kv.md) | StateMachine SPI, linearizable reads, snapshots |
| 10 | [Distributed Lock Service](10-case-distributed-lock.md) | TTL leases, ownership, failure handling |
| 11 | [Replicated Counter](11-case-distributed-counter.md) | Full cluster simulation, in-memory networking |
| 12 | [Distributed Transactions](12-case-distributed-transactions.md) | Multi-key atomicity, optimistic concurrency, CAS |

---

## Quick Start

```bash
# Run an example
sbt "runMain examples.kvstore.KVStoreExample"

# Run all tests
sbt test
```

## License

Apache 2.0
