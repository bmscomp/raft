# Tutorials — Real-World Distributed Systems with the Functional RAFT Library

*Hands-on guides that solve production distributed systems problems using the Functional RAFT Library for Scala 3. Each tutorial tackles a specific real-world challenge, explaining not just **how** to build the solution, but **why** Raft is the right tool and **what happens** when things fail.*

---

## Prerequisites

- Scala 3 basics (pattern matching, sealed traits, opaque types)
- Familiarity with the library's core API — read [Chapter 6: Core API](../docs/book/06-core-api.md) and [Chapter 7: The SPI Layer](../docs/book/07-spi-layer.md) first
- Understanding of the pure state transition model — [Chapter 5: Design Philosophy](../docs/book/05-design-philosophy.md) explains the effects-as-data pattern

## Tutorials

| # | Tutorial | Real-World Problem | Key Concepts |
|---|----------|--------------------|-------------|
| 1 | [Configuration Service](01-configuration-service.md) | Centralized config management with change notifications | Watch notifications, ReadIndex, versioned state |
| 2 | [Leader Election for Microservices](02-leader-election-microservices.md) | Active-passive failover — exactly one active instance | Leader detection, leadership transfer, fencing tokens |
| 3 | [Replicated Task Queue](03-replicated-task-queue.md) | Distributed work queue with exactly-once execution | Claim/ack protocol, deterministic scheduling, TTL expiration |
| 4 | [Distributed Rate Limiter](04-distributed-rate-limiter.md) | Coordinated request throttling across API gateways | Sliding window algorithm, lease reads for fast path |
| 5 | [Cluster Membership Management](05-cluster-membership.md) | Adding and removing nodes without service interruption | Joint consensus, learner promotion, safe removal |

## How These Differ from the Book

The [book's case studies](../docs/book/README.md#part-iv--case-studies) (chapters 12–15) build complete systems — a KV store, lock service, counter, and transaction engine. These tutorials instead focus on **specific operational challenges** you encounter when deploying Raft in production:

- **Tutorial 1** goes deeper into **read consistency** — comparing log reads, ReadIndex, and lease reads with working code for each
- **Tutorial 2** addresses the common pattern of using Raft **just for leader election** without a full replicated data store
- **Tutorial 3** tackles **exactly-once semantics**, which requires careful state machine design to handle client retries
- **Tutorial 4** explores **trading consistency for latency** with lease-based reads in a throughput-sensitive setting
- **Tutorial 5** is a step-by-step **operational runbook** for the most anxiety-inducing production task: changing cluster membership

## Running the Examples

All tutorials reference code that runs with the library's in-memory infrastructure:

```bash
sbt test    # 350 tests — verifies all library functionality the tutorials depend on
```
