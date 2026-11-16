# Chapter 18: End-to-End Integration & Full-Stack Cluster Lifecycle

*Previous chapters explored the pure `RaftLogic` state machine, the `RawNode` Sans-I/O harness, the SPI contracts, the FFM `MmapWalEngine`, and the Netty `GrpcTransport`. This chapter shows how all layers compose into a production `Resource` hierarchy using `RaftNode[F]`, how crash recovery reconstructs exact cluster state from disk, and how multi-tier integration suites verify every seam.*

---

## Composing the Full Stack as a Cats Effect `Resource`

A running Raft node owns multiple stateful lifecycles: memory-mapped `Arena` segments, HTTP/2 Netty servers and `ManagedChannel` pools, background election/heartbeat/quorum-check fibers, and an inbound RPC event loop. Wrapping each layer in `cats.effect.Resource` guarantees **deterministic LIFO teardown** — on shutdown or error, `RaftNode` cancels its fibers and fails in-flight client `Deferred`s *before* `GrpcTransport` closes sockets and *before* `MmapWalEngine` unmaps `MemorySegment` arenas:

```mermaid
flowchart TD
    subgraph Lifecycle["Cats Effect Resource Acquisition (Top-Down) & Release (Bottom-Up)"]
        WAL["1. MmapWalEngine.resource (hard_state.bin + .wal segments + snapshots/)"]
        ADAPT["2. Storage Adapters (StorageBackedLogStore / StableStore / SnapshotStore)"]
        GRPC["3. GrpcTransport.raftResource (Netty HTTP/2 Server + ProtobufMessageCodec)"]
        TIMER["4. DefaultTimerService + Application StateMachine"]
        NODE["5. RaftNode.apply (Mutex + Event Loop + Election / Heartbeat / CheckQuorum Fibers)"]
    end

    WAL --> ADAPT
    ADAPT --> NODE
    GRPC --> NODE
    TIMER --> NODE
```

### Production Wiring Example

```scala
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.concurrent.duration.*

import cats.effect.{IO, IOApp, Ref, Resource}
import fs2.{Chunk, Stream}

import raft.RaftNode
import raft.impl.DefaultTimerService
import raft.spi.{CompactionPolicy, StateMachine, TransportConfig}
import raft.state.*
import raft.storage.{
  StorageBackedLogStore,
  StorageBackedSnapshotStore,
  StorageBackedStableStore
}
import raft.storage.mmap.MmapWalEngine
import raft.transport.grpc.{GrpcConfig, GrpcTransport}

final class KvStore(ref: Ref[IO, Map[String, String]])
    extends StateMachine[IO, Unit]:

  def apply(log: Log): IO[Unit] =
    new String(log.data, StandardCharsets.UTF_8).split("=", 2) match
      case Array(k, v) => ref.update(_.updated(k, v))
      case _           => IO.unit

  def snapshot: IO[Stream[IO, Byte]] =
    ref.get.map { entries =>
      val bytes = entries.toSeq
        .sortBy(_._1)
        .map((k, v) => s"$k=$v")
        .mkString("\n")
        .getBytes(StandardCharsets.UTF_8)
      Stream.chunk(Chunk.array(bytes)).covary[IO]
    }

  def restore(data: Stream[IO, Byte]): IO[Unit] =
    data.compile.to(Array).flatMap { bytes =>
      val text = new String(bytes, StandardCharsets.UTF_8)
      val restored =
        if text.isEmpty then Map.empty[String, String]
        else
          text
            .split("\n")
            .flatMap { line =>
              line.split("=", 2) match
                case Array(k, v) => Some(k -> v)
                case _           => None
            }
            .toMap
      ref.set(restored)
    }

  def get(key: String): IO[Option[String]] = ref.get.map(_.get(key))

object ProductionNode:

  def resource(
      localId: NodeId,
      dataDir: Path,
      bindPort: Int,
      peers: Map[NodeId, String],
      bootstrapCluster: ClusterConfig
  ): Resource[IO, (RaftNode[IO], KvStore)] =
    for
      engine <- MmapWalEngine.resource[IO](dataDir)
      logStore = StorageBackedLogStore(engine)
      stableStore = StorageBackedStableStore(engine)
      snapStore = StorageBackedSnapshotStore(engine)

      grpcCfg = GrpcConfig(
        host = "0.0.0.0",
        port = bindPort,
        maxInboundQueueSize = 4096
      )
      transportCfg = TransportConfig(
        localId = localId,
        bindAddress = s"0.0.0.0:$bindPort",
        peers = peers - localId
      )
      grpcPair <- GrpcTransport.raftResource[IO](grpcCfg, transportCfg)
      (_, raftTransport) = grpcPair

      timerSvc <- Resource.eval(DefaultTimerService[IO])
      smRef <- Resource.eval(Ref.of[IO, Map[String, String]](Map.empty))
      kv = new KvStore(smRef)

      raftCfg = RaftConfig(
        localId = localId,
        electionTimeoutMin = 150.millis,
        electionTimeoutMax = 300.millis,
        heartbeatInterval = 50.millis,
        compaction = CompactionConfig(
          level = CompactionLevel.Custom,
          snapshotChunkSize = 64 * 1024,
          retainedEntries = 250L,
          policy = Some(CompactionPolicy.threshold[IO](5_000L))
        )
      )

      node <- RaftNode[IO](
        config = raftCfg,
        cluster = bootstrapCluster,
        transport = raftTransport,
        logStore = logStore,
        stableStore = stableStore,
        stateMachine = kv,
        timerService = timerSvc,
        snapshotStoreOpt = Some(snapStore)
      )
      _ <- Resource.eval(node.start)
    yield (node, kv)
```

---

## End-to-End Write, Compaction, and Crash-Recovery Path

When a client calls `proposeAndCommit` on the active leader, every layer participates in strict causal order:

```mermaid
sequenceDiagram
    participant C as Client Fiber
    participant RN as Leader RaftNode
    participant WAL as MmapWalEngine
    participant GRPC as GrpcTransport
    participant F as Follower Quorum
    participant SM as StateMachine

    C->>RN: proposeAndCommit(payload)
    RN->>WAL: append(Log.command) + forceRange
    RN->>GRPC: AppendEntriesRequest (Protobuf WireEnvelope)
    GRPC->>F: HTTP/2 unary RPC
    F-->>GRPC: AppendEntriesResponse(success = true, matchIndex)
    GRPC-->>RN: Deliver response to event loop
    RN->>SM: apply(entry) at commitIndex
    RN->>WAL: CompactionManager saveSnapshot + discardBefore (when policy fires)
    RN-->>C: LogId(term, index)
```

> [!IMPORTANT]
> **Persistence before external visibility.** `RaftNode` persists `HardState` (`term`, `votedFor`) atomically via `StableStore.setHardState` and flushes log entries via `LogStore.append` before emitting outbound RPCs or completing client `Deferred`s.

### Crash Recovery Sequence on Restart

When a node crashes (`SIGKILL` or power loss) and restarts against an existing `MmapWalEngine` directory, `MmapWalEngine.open` and `RaftNode.start` cooperate in four deterministic phases:

1. **HardState Recovery**: `MmapHardState` reads and CRC32-verifies `hard_state.bin`, restoring `(term, votedFor)`.
2. **WAL Index Recovery**: `MmapWalEngine` validates `index.ckpt` (`"WIDX"` v1 header + CRC32) or falls back to scanning `.wal` segment headers and entries, replaying any entries newer than the checkpoint.
3. **Snapshot & Membership Restoration**: `RaftNode.start` loads the highest numeric `snapshot-%020d.bin` via `SnapshotStore.loadLatest`, streams it into `StateMachine.restore`, advances `lastApplied` to `snap.lastIncludedIndex`, and reconstructs the latest `ClusterConfig` from the snapshot header plus any post-snapshot `LogType.Configuration` WAL entries.
4. **Follower Catch-Up**: The node enters `Follower(hs.term, hs.votedFor, None)`. On the next leader `AppendEntriesRequest`, `commitIndex` advances and `applyCommittedEntries` replays only the uncompacted tail `(snap.lastIncludedIndex + 1 .. commitIndex)` onto the restored `StateMachine`.

---

## Multi-Tier Integration Testing Architecture

The repository verifies runtime and cross-module behavior at two complementary integration tiers:

| Tier | Module | Transport & Storage | What It Proves |
|------|--------|---------------------|----------------|
| **Runtime Cluster Suites** | `raft-runtime` (`modules/runtime/src/test/scala/raft/integration/`) | Partitionable `InMemTransport` + `InMemLogStore` / `InMemSnapshotStore` + `TestControl` | Virtual-time deterministic elections, 50-fiber concurrent `proposeAndCommit`, `CheckQuorum` minority step-down, `readIndex` / `leaseRead` safety, `transferLeadership`, `3 -> 5` Joint Consensus, leader self-removal, multi-chunk `SnapshotTransfer`, and `MultiRaftNode` 4-shard multiplexing |
| **Full-Stack E2E Suite** | `raft-itest` (`modules/itest/src/test/scala/raft/itest/`) | Live Netty HTTP/2 `GrpcTransport` (`127.0.0.1:0`) + FFM `MmapWalEngine` (`4 KB` segments) | Ephemeral gRPC peer discovery & `updatePeers`, multi-segment `.wal` rotation, on-disk snapshot compaction, chunked gRPC `InstallSnapshot` to a joining 4th node, hard leader kill + disk crash recovery on the same port, and concurrent WGL linearizability (`KVModel`) |

### Deterministic Virtual-Time Cluster Tests with `TestControl`

Because `DefaultTimerService` schedules timeouts via `Async[F].sleep` and accepts a seeded `cats.effect.std.Random[F]`, entire multi-node `RaftNode[IO]` elections and commits can execute inside Cats Effect's `TestControl.executeEmbed` in milliseconds of CPU time with **zero wall-clock sleeps**:

```scala
import cats.effect.testkit.TestControl
import raft.integration.RuntimeClusterHarness

val program = RuntimeClusterHarness
  .clusterResource(
    allIds = List(NodeId("n1"), NodeId("n2"), NodeId("n3")),
    initialClusterFor = _ => ClusterConfig(Set(NodeId("n1"), NodeId("n2"), NodeId("n3"))),
    deterministicSeed = Some(42L)
  )
  .use { cluster =>
    for
      (_, leader) <- cluster.awaitLeader()
      logId       <- leader.proposeAndCommit("k=v".getBytes("UTF-8"))
      readIdx     <- leader.readIndex
    yield assert(readIdx >= logId.index)
  }

TestControl.executeEmbed(program).unsafeRunSync()
```

---

## WAN & Multi-AZ Deployment Tuning

```mermaid
flowchart LR
    subgraph AZ1["Availability Zone 1"]
        N1["Node 1 (Voter / Leader)"]
    end

    subgraph AZ2["Availability Zone 2"]
        N2["Node 2 (Voter / Follower)"]
    end

    subgraph AZ3["Availability Zone 3"]
        N3["Node 3 (Voter / Follower)"]
    end

    subgraph RemoteRegion["DR Region"]
        L1["Node 4 (Non-Voting Learner)"]
    end

    N1 <-->|"HTTP/2 gRPC"| N2
    N1 <-->|"HTTP/2 gRPC"| N3
    N2 <-->|"HTTP/2 gRPC"| N3
    N1 -->|"Async Replication / Snapshot"| L1
```

For cross-AZ and cross-region deployments:

- **Timeout Ratio (`electionTimeoutMin >= 5 * heartbeatInterval`)**: Keep `heartbeatInterval` at least 3x the p99 inter-AZ RTT so transient TCP retransmissions never trigger spurious `PreVote` rounds.
- **Dynamic Peer Updates (`GrpcTransport.updatePeers`)**: Whenever `proposeConfChange` adds or decommissions nodes, invoke `updatePeers` so Netty `ManagedChannel`s to removed hosts are evicted cleanly without socket leaks.
- **Remote `Learner` Replicas**: Place `NodeRole.Learner` replicas in distant DR regions so cross-continent links receive continuous WAL/snapshot replication without adding WAN latency to local write quorums.

---

*Next: [Chapter 19 — Property-Based Testing](19-property-based-testing.md) shows how ScalaCheck generators verify wire codecs, state invariants, and protocol transitions across randomized inputs.*
