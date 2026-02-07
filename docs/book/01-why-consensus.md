# Chapter 1: Why Consensus?

*Before we can understand Raft, we need to understand why consensus algorithms exist at all — what breaks when distributed systems try to agree, and why the problem is so fundamentally difficult.*

---

## The Core Problem

Imagine you have a single server that stores critical data — user accounts, financial transactions, configuration state. Everything works fine until that server fails. Now your entire system is offline.

The obvious fix is replication: keep copies of the data on multiple servers. If one dies, the others continue. But replication introduces a deeper problem: **how do the servers agree on what the data is?**

```
Client writes "x = 5" to Server A
Client writes "x = 7" to Server B    ← Which value is correct?
Server A crashes                      ← Does Server B know about x = 5?
```

This is the **consensus problem**: getting a collection of machines to agree on a sequence of values, even when some machines fail, messages get lost, or the network splits into disconnected partitions.

## What Makes Distributed Consensus Hard?

Three fundamental challenges make this problem far harder than it appears.

### 1. No Global Clock

In a single-threaded program, events have a natural order — one happens before the next. In a distributed system, **there is no shared notion of time**. Clocks on different machines drift apart. NTP synchronization has bounded but non-zero error. You cannot simply timestamp events and sort them.

This means that when two servers receive conflicting updates, there is no oracle to tell them which came first. Ordering must be established through communication — which itself is unreliable.

### 2. Unreliable Networks

Networks in real data centers exhibit every failure mode imaginable:

| Failure | Effect |
|---------|--------|
| **Message loss** | A server never receives a request |
| **Message duplication** | The same request arrives twice |
| **Message reordering** | Request B arrives before request A |
| **Network partition** | Servers are split into groups that cannot communicate |
| **Asymmetric partition** | A can reach B but B cannot reach A |

A correct consensus algorithm must handle all of these simultaneously.

### 3. Process Failures

Servers crash. They run out of memory, their disks fill up, their power supplies fail. A correct algorithm must tolerate any minority of servers failing at any time, without losing committed data and without blocking the remaining servers from making progress.

## Impossibility Results

Two foundational theorems frame what is and isn't possible.

### The FLP Impossibility (1985)

Fischer, Lynch, and Paterson proved that **no deterministic algorithm can guarantee consensus in an asynchronous system if even one process can fail**. The key insight is that you can never distinguish a crashed server from a very slow one, so you can never safely decide to proceed without it.

This sounds like it makes consensus impossible. In practice, algorithms work around it by using **randomization** (randomized timeouts break the symmetry that FLP exploits) and by relying on **partial synchrony** (eventually, messages do arrive within some bounded time).

### The CAP Theorem (2000)

Brewer's conjecture (later proved by Gilbert and Lynch) states that a distributed system can provide at most two of three guarantees:

- **Consistency** — every read returns the most recent write
- **Availability** — every request receives a response
- **Partition tolerance** — the system operates despite network splits

Since network partitions are inevitable in real systems, the practical choice is between **CP** (consistent but may be unavailable during partitions) and **AP** (available but may return stale data).

Raft is a **CP** system. During a network partition, the minority side cannot accept new writes — only the side with a majority of nodes can make progress. This ensures that committed data is never lost or contradicted.

## The Byzantine Spectrum

Failure models vary in what they assume can go wrong:

| Model | Assumption | Example Algorithms |
|-------|------------|-------------------|
| **Crash-fail** | Servers stop cleanly | Paxos, Raft, Zab |
| **Crash-recovery** | Servers stop and may restart | Raft (with stable storage) |
| **Byzantine** | Servers may behave arbitrarily (including maliciously) | PBFT, Tendermint |

Raft operates in the **crash-recovery** model: a server may crash at any time, but when it restarts, it behaves correctly and can recover its state from durable storage. This is the realistic model for data center software — hardware fails, but doesn't lie.

## Before Raft: A Brief History

### Paxos (1989)

Leslie Lamport's Paxos algorithm was the first practical solution to consensus. It is provably correct, tolerates minority failures, and has been deployed in production systems like Google's Chubby lock service and Microsoft's Azure Storage.

However, Paxos has a reputation for being **extremely difficult to understand**. Lamport's original paper described the algorithm through an allegory about a fictional Greek parliament. The "Multi-Paxos" extension needed for replicated state machines was never formally specified — every implementation interprets the extension differently.

As Ongaro and Ousterhout wrote in the Raft paper:

> *"We hypothesize that Paxos's opaqueness derives from its choice of the single-decree subset as its foundation... The composition rules add significant additional complexity and subtlety."*

### Viewstamped Replication (1988)

Oki and Liskov's VR protocol predates Paxos and shares many structural similarities with Raft (primary-backup with view changes). It was less widely known but influenced later systems.

### Zab (2011)

The Zookeeper Atomic Broadcast protocol powers Apache ZooKeeper. It provides total ordering of messages and handles leader election, but is specialized for the primary-backup model and harder to generalize.

### Raft (2014)

Diego Ongaro and John Ousterhout designed Raft explicitly for **understandability**. Their key technique was **decomposition**: instead of describing consensus as a single monolithic algorithm, they broke it into three relatively independent sub-problems:

1. **Leader election** — how to choose a single leader
2. **Log replication** — how the leader replicates commands to followers
3. **Safety** — why the algorithm never produces inconsistent results

This decomposition makes Raft dramatically easier to teach, implement, and debug compared to Paxos. It has become the dominant consensus algorithm in modern infrastructure software, powering etcd (Kubernetes), CockroachDB, TiKV, Consul, and many others.

## What Consensus Gives You

With a correct consensus algorithm, you can build:

| Primitive | Example |
|-----------|---------|
| **Replicated state machines** | Consistent databases that survive failures |
| **Distributed locks** | Mutual exclusion across processes |
| **Leader election** | Electing a primary in a primary-backup system |
| **Configuration stores** | etcd, ZooKeeper, Consul |
| **Atomic broadcast** | Total-order message delivery |
| **Sequence generators** | Globally unique, monotonically increasing IDs |

All of these are instances of the same underlying pattern: a group of machines agreeing on a **sequence of operations** to apply in the same order.

## Summary

| Concept | Key Insight |
|---------|-------------|
| **The problem** | Replication introduces disagreement |
| **FLP** | Deterministic consensus is impossible with failures |
| **CAP** | You must choose between consistency and availability |
| **Crash-recovery** | Raft's failure model: servers crash but don't lie |
| **Paxos** | Correct but infamously hard to understand |
| **Raft** | Designed for understandability via decomposition |

---

*Next: [Chapter 2 — Raft Fundamentals](02-raft-fundamentals.md) dives into how Raft actually works: terms, elections, log replication, and the safety invariants that make it all correct.*
