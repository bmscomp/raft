# Chapter 23: Host-System Integration Blueprints

*How the 6-module architecture lets you adopt only what you need — from embedding pure `raft-core` on JDK 25 Virtual Threads to assembling a full Cats Effect + FFM Mmap WAL + Netty gRPC cluster.*

---

## The 6-Module Architecture

To keep the consensus core dependency-free while offering production-ready building blocks, the repository is organized into six sbt modules:

```mermaid
flowchart TD
    core["raft-core (Zero External Dependencies)"]
    runtime["raft-runtime (Cats Effect + FS2 + log4cats-core)"]
    mmap["raft-storage-mmap (JDK 22+ FFM WAL)"]
    grpc["raft-transport-grpc (ScalaPB + gRPC Netty)"]
    jepsen["raft-jepsen (Linearizability & Nemesis Suite)"]
    examples["raft-examples (publish / skip := true, Logback)"]

    core --> runtime
    runtime --> mmap
    runtime --> grpc
    runtime --> jepsen
    mmap --> examples
    grpc --> examples
```

| Module | Artifact | JDK Target | External Compile Dependencies | When to Choose |
|--------|----------|------------|-------------------------------|----------------|
| `modules/core` | `raft-core` | **JDK 21+** | **None (0 JARs)** | Embedding Raft inside your own actor system, Netty/Nio event loop, ZIO, Ox/Virtual Threads, or deterministic simulator |
| `modules/runtime` | `raft-runtime` | **JDK 21+** | `cats-effect`, `fs2-core`, `fs2-io`, `log4cats-core` | Turnkey functional runtime (`RaftNode`, `MultiRaftNode`, streaming `Snapshot[F]`, `CompactionManager`) |
| `modules/storage-mmap` | `raft-storage-mmap` | **JDK 22+/25** | `raft-runtime` (JDK `java.lang.foreign` FFM API) | Off-heap memory-mapped segmented WAL + CRC32 crash recovery |
| `modules/transport-grpc` | `raft-transport-grpc` | **JDK 22+/25** | `grpc-netty-shaded`, `scalapb-runtime-grpc` | Persistent HTTP/2 peer channels with Protobuf framing |
| `modules/jepsen` | *(internal)* | JDK 25 | `raft-runtime`, `scalatest` | Wing & Gong linearizability checker + partition/crash nemesis suite |
| `modules/examples` | *(internal)* | JDK 25 | All modules + `logback-classic` | 18 runnable end-to-end examples |

---

## Blueprint 1: Zero-Dependency JDK 25 Virtual Threads (`raft-core` Only)

When your host system manages its own threads and sockets, depend **only** on `raft-core`:

```scala
libraryDependencies += "com.github.bmscomp" %% "raft-core" % "<version>"
```

Because `RawNode` is intentionally not thread-safe (zero internal locks), the cleanest host architecture is **one Virtual Thread + one `BlockingQueue` mailbox per Raft group**:

1. Network reader threads decode wire bytes via `JsonCodec.byteCodec` (or your own Protobuf/FlatBuffers codec) and `offer(Inbound(sender, msg))` into the group's mailbox.
2. Client threads submit writes via `offer(Propose(bytes, replyPromise))`.
3. `inbox.poll(tickIntervalMs, MILLISECONDS)` drives `raw.tick()` on timeout or `raw.step(from, msg)` on arrival, followed by `if raw.hasReady then drainReady()`.

Because a single Virtual Thread owns `RawNode`, all consensus mutations are serialized without mutex contention, while thousands of Raft groups can run concurrently on a handful of carrier OS threads.

> **Runnable Reference:** See `modules/examples/src/main/scala/examples/rawnode/RawNodeHostLoopExample.scala` (`sbt "examples/runMain examples.rawnode.RawNodeHostLoopExample"`).

---

## Blueprint 2: Cats Effect + Custom Storage & Transport (`raft-runtime`)

If your service is already built on Typelevel Cats Effect 3 and FS2, `raft-runtime` provides `RaftNode[F]` and `MultiRaftNode[F]` over clean SPIs:

- **`LogStore[F]` & `StableStore[F]`**: Backed by RocksDB, PostgreSQL/SQLite, LMDB, or `raft-storage-mmap`.
- **`Transport[F, Wire]`**: Backed by `raft-transport-grpc`, HTTP/2, WebSockets, or `MultiGroupTransport[F]` for multi-group multiplexing.
- **`StateMachine[F, R]`**: Streaming `snapshot: F[Stream[F, Byte]]` and `restore(data: Stream[F, Byte]): F[Unit]` so large state snapshots never require contiguous heap arrays.

Typed consensus errors (`RaftError.NotLeader(leaderHint)`, `RaftError.ElectionInProgress`, `RaftError.LeadershipTransferInProgress(target)`, `RaftError.LeaderNotReadyInTerm(term)`, `RaftError.ConfigurationChangeInProgress(pending)`) let your RPC gateway route or retry client requests deterministically without string parsing.

### End-to-End `RaftNode[F, R]` Coordination Lifecycle

`RaftNode[F, R]` preserves your `StateMachine[F, R]` output type end-to-end (`proposeAndApply(cmd: Bytes): F[Applied[R]]`), caches recent log terms in a bounded `TermRingBuffer`, batches committed reads via `getRange`, streams incoming snapshots to disk via `FilePendingSnapshot[F]`, supervises background tasks under `Supervisor[F]`, and dispatches all network RPCs **outside** its internal state `Mutex[F]`:

```mermaid
sequenceDiagram
    participant Client
    participant Node as "RaftNode[F, R] (Mutex Scope)"
    participant Out as "Lock-Free Outbound Dispatcher"
    participant Peers as "Follower Quorum"

    Client->>Node: "proposeAndApply(cmd: Bytes) / readIndex / proposeConfChange"
    Node->>Node: "Append WAL + update TermRingBuffer (nanoseconds)"
    Node-->>Out: "Release Mutex & emit OutboundAction batch"
    Note over Client,Node: "Client fiber awaits Deferred[Applied[R]] outside Mutex"
    Out->>Peers: "Async sendBatch / broadcast / supervised streaming snapshot"
    Peers-->>Node: "AppendEntriesResponse(success=true)"
    Node->>Node: "Single batched getRange -> StateMachine.apply(log): F[R]"
    Node-->>Client: "Complete Deferred with Applied(LogId, value: R)"
```

Key runtime invariants handled automatically by `RaftNode[F]`:
- **Two-Stage `ReadIndex` Barrier**: `node.readIndex` first awaits quorum heartbeat confirmation (`Effect.ReadIndexReady(id, commitIdx)`) and then suspends in `readApplyWaiters` until `lastApplied >= commitIdx`, ensuring the local `StateMachine` reflects all writes committed before the read started.
- **Zero-RTT Monotonic `leaseRead`**: When `leaseReadEnabled = true` and `checkQuorumEnabled = true`, quorum `AppendEntriesResponse` ACKs extend a monotonic `Clock[F].monotonic` deadline by `leaderLeaseDuration`; `node.leaseRead` returns `Some(commitIndex)` locally while the lease is valid and `None` upon expiry so callers can seamlessly fall back to `node.readIndex`.
- **Automatic Joint Consensus Finalization & Self-Removal Step-Down**: Calling `node.proposeConfChange(targetCluster)` automatically enters `C_old,new` when voter sets change, appends and commits `C_new` once `C_old,new` commits, steps down immediately if `C_new` removes the active leader from `voters`, and resolves the caller's `F[LogId]` once `C_new` is durably committed.

---

## Blueprint 3: Off-Heap Memory-Mapped WAL (`raft-storage-mmap`)

`MmapWalEngine` uses JDK's Foreign Function & Memory (`java.lang.foreign.Arena` + `MemorySegment`) API for zero-copy, off-heap segmented write-ahead logging:

- **Segmented rotation**: When an active segment (`wal-0000000001.seg`) fills past `segmentSize`, `MmapWalEngine` automatically syncs it and rotates to `wal-0000000002.seg`.
- **Zero-copy CRC32**: Checksums are computed directly over `MemorySegment.asSlice(...).asByteBuffer()` without allocating intermediate byte arrays or sharing non-thread-safe `CRC32` instances.
- **Compaction & crash recovery**: `discardBefore(snapshotIndex)` unmaps and deletes obsolete segment files while writing an index checkpoint; on restart, `recoverFromWal` parses actual segment IDs from filenames so compacted logs recover seamlessly from any starting segment number.

```scala
import java.nio.file.Paths
import cats.effect.IO
import raft.storage.{
  EngineBackedRaftStorage,
  StorageBackedLogStore,
  StorageBackedSnapshotStore,
  StorageBackedStableStore
}
import raft.storage.mmap.MmapWalEngine

val storageResource =
  MmapWalEngine.resource[IO](Paths.get("/var/lib/raft/group-0")).map { engine =>
    val logStore    = StorageBackedLogStore(engine)
    val stableStore = StorageBackedStableStore(engine)
    val snapStore   = StorageBackedSnapshotStore(engine)
    (logStore, stableStore, snapStore)
  }
```

### Bridging Sans-I/O `RawNode` Directly to `MmapWalEngine` (`EngineBackedRaftStorage`)

If you want to run your own custom Sans-I/O event loop around `raft.raw.RawNode` while still delegating durable WAL segments, `hard_state.bin` (`term`, `votedFor`, and 8-byte-aligned `commitIndex`), and snapshots to `MmapWalEngine`, use `EngineBackedRaftStorage`:

```mermaid
flowchart LR
    RN["RawNode (Sans-I/O Protocol Driver)"]
    EB["EngineBackedRaftStorage[F] (persistReady / persistLightReady / compactTo)"]
    MM["MmapWalEngine[F] (hard_state.bin + .wal + snapshots/)"]

    RN -->|"Ready / LightReady"| EB
    EB -->|"Effectful F[Unit] Flushes"| MM
    EB -.->|"Synchronous RaftStorage Reads"| RN
```

```scala
import cats.effect.IO
import raft.raw.RawNode
import raft.state.{ClusterConfig, NodeId, RaftConfig}
import raft.storage.EngineBackedRaftStorage
import raft.storage.mmap.MmapWalEngine

def openRawPeer(engine: MmapWalEngine[IO], voters: ClusterConfig): IO[RawNode] =
  EngineBackedRaftStorage.open[IO](engine, voters).map { storage =>
    new RawNode(RaftConfig(localId = NodeId("raw-1")), storage)
  }
```

---

## Blueprint 4: Netty HTTP/2 & Mutual TLS Transport (`raft-transport-grpc`)

`GrpcTransport.raftResource[F]` binds a Netty HTTP/2 gRPC server using the binary `ProtobufMessageCodec` (`WireEnvelope`) and maintains a lazy pool of persistent `ManagedChannel` connections to peers. For zero-trust production networks, enable **Mutual TLS (`mTLS`)** (`useTls = true`, `tlsClientAuth = true`) backed by JDK's built-in `SslProvider.JDK` (`ClientAuth.REQUIRE`):

```scala
import cats.effect.IO
import raft.spi.TransportConfig
import raft.state.NodeId
import raft.transport.grpc.{GrpcConfig, GrpcTransport}

val local = NodeId("node-1")
val peers = Map(
  NodeId("node-2") -> "10.0.1.12:9090",
  NodeId("node-3") -> "10.0.1.13:9090"
)

val mtlsTransportResource =
  GrpcTransport.raftResource[IO](
    GrpcConfig(
      host = "0.0.0.0",
      port = 9090,
      useTls = true,
      tlsCertPath = Some("/etc/raft/pki/node-1.crt"),
      tlsKeyPath = Some("/etc/raft/pki/node-1.key"),       // PKCS#8 PEM
      tlsTrustCertPath = Some("/etc/raft/pki/ca.crt"),     // Cluster Root CA
      tlsClientAuth = true,                                // Enforce mTLS ClientAuth.REQUIRE
      verifyPeerIdentity = true                            // Bind WireEnvelope.sender_id to X.509 CN / SPIFFE URI
    ),
    TransportConfig(local, "0.0.0.0:9090", peers)
  )
```

```mermaid
sequenceDiagram
    participant L as "Leader GrpcTransport (AtomicCell Pool)"
    participant N as "Netty HTTP/2 + mTLS Interceptor"
    participant F as "Follower RaftService (Bounded Queue)"

    L->>N: "SendEnvelope(sender_id='node-1', payloads=[AE_1..AE_k])"
    N->>N: "Extract X.509 CN / SPIFFE URI into Context.Key"
    alt "sender_id not in X.509 identities"
        N-->>L: "Status.PERMISSION_DENIED (rejectedSpoofedEnvelopes++)"
    else "Authentic peer identity"
        N->>F: "Offer k frames to bounded incomingQueue"
        alt "Queue saturated"
            F-->>L: "WireAck(accepted=false) -> RESOURCE_EXHAUSTED backoff"
        else "Accepted"
            F-->>L: "WireAck(accepted=true)"
        end
    end
```

Notice four operational guarantees built into `GrpcTransport`:
1. **Lock-free pure `AtomicCell` channel pool**: Peer routing maps and Netty `ManagedChannel` lifecycles are serialized via `AtomicCell[F, ChannelPoolState]` without `ConcurrentHashMap` or monitor locks; `updatePeers(newMap)` atomically evicts and closes retired channels.
2. **Single-RPC batch framing (`WireEnvelope.payloads`)**: Pipelined `AppendEntries` bursts (`sendBatch`) traverse HTTP/2 in a single `WireEnvelope` RPC rather than `N` unary calls.
3. **End-to-end queue backpressure**: When a follower's bounded `incomingQueue` saturates (`WireAck(accepted = false)`), the sender treats the response as transient `RESOURCE_EXHAUSTED` and backs off exponentially without tearing down healthy sockets.
4. **Cryptographic `sender_id` binding**: Under `tlsClientAuth && verifyPeerIdentity`, a compromised peer holding a valid CA-signed certificate for `node-2` cannot impersonate `sender_id = "node-1"`.

---

## Integration Checklist Before Production

1. **Batch `fsync` per `Ready`**, never per log entry: persist `truncateFromIndex`, all `entries`, and `hardState` in one write batch before sending `persistedMessages`.
2. **Fence stale leaders on reads**: always serve linearizable reads via `ReadIndex` (`rawNode.readIndex(ctx)`) or `LeaseRead` with `checkQuorumEnabled = true`, waiting until `lastApplied >= readState.index` before reading local state-machine state.
3. **Bound in-memory logs via snapshots**: trigger `compactTo` / `CompactionManager` periodically so `RaftStorage` stays bounded and restarted followers replay seconds of WAL rather than hours.

---

*Next: [Chapter 24 — Deterministic Simulation Testing for Consensus](24-deterministic-simulation-testing.md) shows how Sans-I/O `RawNode` unlocks FoundationDB/TigerBeetle-style seeded chaos simulation in milliseconds.*
