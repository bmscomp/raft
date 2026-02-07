# Raft: Theory and Practice

*A comprehensive guide to consensus algorithms, the Raft protocol, and building fault-tolerant distributed systems with the Functional RAFT Library for Scala 3.*

---

## About This Book

Distributed consensus — the ability for multiple processes to agree on a single value despite failures — is one of the foundational problems in computer science. It's also deeply practical: consensus algorithms power everything from Kubernetes' configuration store (etcd) to globally distributed databases (CockroachDB, TiDB, Spanner).

This book bridges the gap between **distributed systems theory** and **hands-on implementation**. It begins with the fundamental problems that make consensus necessary (and hard), builds up a rigorous understanding of the Raft protocol through all its extensions and optimizations, and then walks you through four complete, working case studies using the Functional RAFT Library.

The approach is deliberately layered:

- **Part I** gives you the conceptual foundation — what consensus is, why it's hard, what Raft does, and how Raft's advanced features (Pre-Vote, joint consensus, linearizable reads) solve real operational problems.
- **Part II** shows how the library translates Raft's theory into a pure functional design — no side effects, no shared mutable state, just functions and data.
- **Part III** gets you building — from a first election in 15 lines of code to a production-ready event loop with batching, pipelining, and parallel replication.
- **Part IV** applies everything to real-world distributed systems — a KV store, a lock service, a replicated counter, and a transactional engine with serializable isolation.
- **Part V** puts the library in context by surveying the major Raft implementations across Go, Rust, Java, and Scala.
- **Part VI** covers operational concerns — troubleshooting production clusters, end-to-end integration, and property-based safety testing.
- **Part VII** looks ahead to scaling beyond a single Raft group with Multi-Raft architectures.

Whether you're learning about distributed consensus for the first time or designing a production system that needs strong consistency guarantees, this book gives you both the *why* and the *how*.

> **Who is this for?** Software engineers who want to understand distributed consensus beyond the abstract. You should be comfortable reading Scala (or any typed functional language), but deep Scala expertise isn't required — the code is deliberately straightforward.

---

## Table of Contents

### Part I — Foundations

*The theoretical bedrock: what problem we're solving, how Raft solves it, what extensions push it further, and how safety proofs map to code.*

| # | Chapter | What You'll Learn |
|---|---------|-------------------|
| 1 | [Why Consensus?](01-why-consensus.md) | The core problem, FLP Impossibility, CAP theorem, Byzantine faults, historical evolution from Paxos to Raft |
| 2 | [Raft Fundamentals](02-raft-fundamentals.md) | Terms, leader election mechanics, log replication, safety properties, the Log Matching Property, a traced client write |
| 3 | [Advanced Raft Theory](03-raft-advanced-theory.md) | Pre-Vote protocol, leader stickiness, joint consensus, linearizable reads (ReadIndex + lease), leadership transfer, log compaction, learners |
| 4 | [Safety Proofs, Implemented](04-safety-proofs-implemented.md) | All five Raft safety properties mapped to concrete library code, with invariant proofs, enforcement mechanisms, TLA+ cross-references, and test cases |

### Part II — Library Architecture

*How the library translates protocol theory into a pure functional design with explicit effects and pluggable infrastructure.*

| # | Chapter | What You'll Learn |
|---|---------|-------------------|
| 5 | [Design Philosophy](05-design-philosophy.md) | Pure state transitions, the effects-as-data pattern, benefits of immutability, comparison with traditional approaches |
| 6 | [Core API](06-core-api.md) | `RaftLogic.onMessage`, node states, message types, the complete effect catalog, configuration tuning |
| 7 | [The SPI Layer](07-spi-layer.md) | Transport, LogStore, StableStore, StateMachine, TimerService — contracts, durability requirements, production implementation sketches |

### Part III — Building with the Library

*From zero to a working cluster: hands-on guides to election, replication, and performance tuning.*

| # | Chapter | What You'll Learn |
|---|---------|-------------------|
| 8 | [Getting Started](08-getting-started.md) | Setup, first election in 15 lines, the effect interpreter loop, testing without infrastructure |
| 9 | [Log Replication in Practice](09-log-replication-practice.md) | Entry lifecycle, conflict resolution search-back, commit index advancement, batching, pipelining, parallel replication |
| 10 | [Performance vs. Safety](10-performance-vs-safety.md) | Batching, pipelining, parallel replication, ReadIndex, lease reads, Pre-Vote — safety analysis, configuration profiles, and tradeoff map |

### Part IV — Case Studies

*Four complete distributed systems, built step by step, each demonstrating different patterns and trade-offs.*

| # | Chapter | What You'll Build |
|---|---------|-------------------|
| 11 | [Distributed Key-Value Store](11-case-distributed-kv.md) | Replicated data store with linearizable reads (three strategies compared), snapshot recovery |
| 12 | [Distributed Lock Service](12-case-distributed-lock.md) | Mutual exclusion with TTL leases, reentrant acquisition, automatic expiration, split-brain prevention |
| 13 | [Replicated Counter](13-case-distributed-counter.md) | Full 3-node cluster simulation with in-memory networking, end-to-end election → replication → commit |
| 14 | [Distributed Transactions](14-case-distributed-transactions.md) | Multi-key atomic operations with optimistic concurrency control, CAS-based conflict detection, serializable isolation |

### Part V — The Ecosystem

*Where this library fits in the broader Raft landscape.*

| # | Chapter | What You'll Learn |
|---|---------|-------------------|
| 15 | [State of the Art](15-state-of-the-art.md) | Survey of 7 major Raft libraries (etcd/raft, HashiCorp Raft, tikv/raft-rs, OpenRaft, SOFAJRaft, MicroRaft), feature matrix, architectural spectrum, decision guide |

### Part VI — Operations & Testing

*Taking your Raft cluster from prototype to production: diagnostics, integration, and formal verification.*

| # | Chapter | What You'll Learn |
|---|---------|-------------------|
| 16 | [Troubleshooting & Operational Pitfalls](16-troubleshooting.md) | Frequent leader changes, stalled writes, split-brain diagnosis, term inflation, slow followers, diagnostic checklist |
| 17 | [End-to-End Integration](17-end-to-end-integration.md) | Wiring `RaftNode` with production SPIs, event loop architecture, SPI implementation guidance, deployment topology |
| 18 | [Property-Based Testing](18-property-based-testing.md) | Five Raft safety invariants as ScalaCheck properties, random event generation, shrinking, deterministic reproduction |
| 19 | [Jepsen-Style Verification](19-jepsen-verification.md) | Jepsen methodology, fault injection, linearizability checking, real-world results, designing for Jepsen |

### Part VII — Scaling

*Designing beyond a single Raft group for larger datasets and higher throughput.*

| # | Chapter | What You'll Learn |
|---|---------|-------------------|
| 20 | [Multi-Raft Group Design](20-multi-raft-groups.md) | Partitioning strategies, cross-group transactions, leader balancing, transport multiplexing, resource sharing |

### Appendices

| | Appendix | Contents |
|---|---------|----------|
| A | [Quick Reference](appendix-a-quick-reference.md) | All types, effects, messages, configuration parameters, and SPI interfaces at a glance |

---

## Quick Start

Run an example to see the library in action:

```bash
sbt "runMain examples.kvstore.KVStoreExample"
sbt "runMain examples.cluster.ThreeNodeClusterExample"
sbt "runMain examples.distributed.DistributedTransactionExample"
```

Run the test suite (333 tests, completes in seconds):

```bash
sbt test
```

Begin reading at [Chapter 1: Why Consensus?](01-why-consensus.md), or jump directly to [Chapter 8: Getting Started](08-getting-started.md) if you want to write code immediately.

---

## References

The following academic works are referenced throughout the book:

- **Ongaro, D. & Ousterhout, J.** (2014). *In Search of an Understandable Consensus Algorithm.* USENIX ATC. — The original Raft paper
- **Ongaro, D.** (2014). *Consensus: Bridging Theory and Practice.* Ph.D. thesis, Stanford. — Extended version with Pre-Vote, joint consensus, and detailed proofs
- **Fischer, M., Lynch, N. & Paterson, M.** (1985). *Impossibility of Distributed Consensus with One Faulty Process.* JACM. — The FLP Impossibility result
- **Brewer, E.** (2000). *Towards Robust Distributed Systems.* PODC Keynote. — The CAP conjecture
- **Gilbert, S. & Lynch, N.** (2002). *Brewer's Conjecture and the Feasibility of Consistent, Available, Partition-Tolerant Web Services.* — Formal proof of the CAP theorem
- **Kleppmann, M.** (2016). *How to do distributed locking.* — Critique of Redis-based distributed locks

## License

Apache 2.0
