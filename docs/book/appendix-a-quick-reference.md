# Appendix A: Quick Reference

*An accurate, one-page technical lookup for every domain type, message, effect, configuration parameter, SPI trait, and runtime entry point across all 7 modules of the Functional RAFT Library.*

---

## Domain Primitives (`raft-core`, Zero External Dependencies)

| Type | Definition / Role | Key Invariant / Capability |
|------|-------------------|----------------------------|
| `NodeId` / `GroupId` / `Term` / `LogIndex` | Zero-cost Scala 3 `opaque type`s (`String` / `Long`) | Compile-time distinction with zero heap boxing; `NodeId.validate` enforces non-empty, no control chars, `<= 256` UTF-8 bytes |
| `LogId(term: Term, index: LogIndex)` | Canonical coordinate implementing `Ordered[LogId]` | Exact §5.4.1 lexicographic total order (`term` primary, `index` tiebreaker) |
| `Bytes` | Deeply immutable byte sequence (`raft.state.Bytes`) | Structural `==`, SIMD-intrinsified `Arrays.hashCode`, zero-copy read-only `ByteBuffer` views |
| `HardState(term, votedFor, commitIndex)` | Minimal durable consensus state | Must be atomically flushed before sending `Ready.persistedMessages` (§5.1 / §5.2) |
| `ReadState(index, requestCtx)` | Confirmed §6.4 linearizable read watermark | Serve read once `lastApplied >= readState.index` |
| `Validated[ConfigError, A]` | Pure error-accumulating smart constructor result | Reports every invalid configuration field in one pass without throwing exceptions |
| `RaftError` | Sealed `RuntimeException with NoStackTrace` | `NotLeader(leaderId)`, `ElectionInProgress`, `LeadershipTransferInProgress`, `ConfigurationChangeInProgress`, `LeaderNotReadyInTerm`, `ProposalDropped`, `GroupNotFound`, `StorageCorruption` |

---

## Node States (`enum NodeState`)

Every role preserves `commitIndex: Long` monotonically across elections and higher-term step-downs:

```scala
enum NodeState:
  case Follower(
    term: Long,
    votedFor: Option[NodeId] = None,
    leaderId: Option[NodeId] = None,
    commitIndex: Long = 0L
  )
  case PreCandidate(
    term: Long,
    preVotesReceived: Set[NodeId] = Set.empty,
    commitIndex: Long = 0L
  )
  case Candidate(
    term: Long,
    votesReceived: Set[NodeId] = Set.empty,
    commitIndex: Long = 0L
  )
  case Leader(
    term: Long,
    nextIndex: Map[NodeId, Long] = Map.empty,
    matchIndex: Map[NodeId, Long] = Map.empty,
    commitIndex: Long = 0L,
    transferTarget: Option[NodeId] = None,
    pendingReads: Map[String, PendingRead] = Map.empty,
    leaseUntil: Long = 0L,
    recentlyActive: Set[NodeId] = Set.empty
  )
```

| State | Entry Condition | Transitions To |
|-------|----------------|----------------|
| **Follower** | Boot, higher term seen, or `CheckQuorum` step-down | `PreCandidate` (if `preVoteEnabled`), `Candidate` (if `!preVoteEnabled` or `TimeoutNow`) |
| **PreCandidate** | Election timeout with `preVoteEnabled = true` (term unchanged) | `Candidate` (on pre-vote quorum), `Follower` (on higher term / valid leader RPC) |
| **Candidate** | Pre-vote won, direct election timeout, or `TimeoutNowRequest` | `Leader` (on vote quorum), `Follower` (on higher/equal term leader RPC) |
| **Leader** | Vote quorum won (single or Joint Consensus `C_old,new`) | `Follower` (on higher term, `CheckQuorum` loss, or self-removal in `C_new`) |

---

## Messages (`sealed trait RaftMessage` — All 17 Variants)

### Internal Logical Events

| Message | Trigger |
|---------|---------|
| `ElectionTimeout` | Randomized election timeout expired (§5.2) |
| `HeartbeatTimeout` | Periodic leader heartbeat tick expired (§5.2) |
| `CheckQuorumTimeout` | Leader quorum-activity verification window elapsed (§6.2) |

### Inter-Node & Administrative RPCs

| Request | Response | Purpose |
|---------|----------|---------|
| `RequestVoteRequest(term, candidateId, lastLogIndex, lastLogTerm, isPreVote, isTransfer)` | `RequestVoteResponse(term, voteGranted, isPreVote)` | Election, Pre-Vote (§9.6), and `TimeoutNow` transfer elections (§3.10) |
| `AppendEntriesRequest(term, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit)` | `AppendEntriesResponse(term, success, matchIndex, conflictIndex, conflictTerm)` | Log replication, heartbeats, and §5.3 fast term-skip backtracking |
| `InstallSnapshotRequest(term, leaderId, lastIncludedIndex, lastIncludedTerm, offset, payload: Bytes, done)` | `InstallSnapshotResponse(term, success, matchIndex)` | Chunked `"RSNP"` snapshot streaming (§7) |
| `TransferLeadershipRequest(term, target)` | *(emits `TimeoutNowRequest`)* | Initiate zero-downtime leadership transfer (§3.10) |
| `TimeoutNowRequest(term, leaderId)` | *(target campaigns with `isTransfer = true`)* | Immediate election trigger once transferee catches up (§3.10) |
| `ReadIndexRequest(requestId)` | `ReadIndexResponse(requestId, success, readIndex)` | Linearizable quorum read confirmation (§6.4) |
| `AddServerRequest(term, newServer, isLearner)` | `AddServerResponse(term, success, leaderHint)` | Dynamic membership addition (§6) |
| `RemoveServerRequest(term, server)` | `RemoveServerResponse(term, success, leaderHint)` | Dynamic membership removal (§6) |

---

## Pure Effect Algebra (`enum Effect` — All 18 Cases)

Emitted by `RaftLogic.onMessage` / `onVoteResponse`; zero closures, structural `==`:

| Category | Effect | Semantics |
|----------|--------|-----------|
| **Messaging** | `SendMessage(to, message)` | Unicast RPC to specific peer `to` |
| | `Broadcast(message)` | Deliver RPC to all cluster peers |
| **Persistence** | `PersistHardState(term, votedFor)` | Atomically flush `(term, votedFor)` via `StableStore.setHardState` **before** sending RPCs |
| | `AppendLogs(entries)` | Atomically persist contiguous log entries before acknowledging |
| | `TruncateLog(fromIndex)` | Delete conflicting suffix at `index >= fromIndex` (§5.3) |
| **Commitment** | `CommitEntries(index: LogIndex)` | Apply committed entries sequentially up to `index` |
| **Timers** | `ResetElectionTimer` | Cancel prior election fiber and schedule fresh randomized timeout |
| | `ResetHeartbeatTimer` | Schedule next periodic leader heartbeat fiber |
| **Leadership** | `BecomeLeader` | Enter Leader role, append current-term `NoOp`, start heartbeats |
| | `StopLeader` | Cancel leader timers and reject in-flight leader-only requests |
| | `TransferLeadership(target)` | Fence new client proposals while catching up `target` (§3.10) |
| | `TimeoutNow(target)` | Send `TimeoutNowRequest` to caught-up `target` |
| **Linearizable Reads** | `ConfirmLeadership(term, roundId)` | Initiate quorum heartbeat confirmation round (§6.4) |
| | `ReadIndexReady(id, index)` | Quorum confirmed; serve read once `lastApplied >= index` |
| | `ReadIndexRejected(id, reason, leaderHint)` | Reject read (non-leader or current-term `NoOp` uncommitted) |
| **Snapshots & Config** | `RequestSnapshot(lastApplied, logSize)` | Trigger state-machine snapshot + log compaction |
| | `InstallSnapshot(metadata)` | Restore state machine, persist snapshot, update `ClusterConfig` |
| | `ApplyClusterConfig(config, index, term)` | Activate appended/installed `ClusterConfig` (`C_old,new` or `C_new`) |

---

## Configuration (`RaftConfig`, `BatchConfig`, `PipelineConfig`)

```scala
final case class RaftConfig(
  localId: NodeId,
  electionTimeoutMin: FiniteDuration  = 150.millis,
  electionTimeoutMax: FiniteDuration  = 300.millis,
  heartbeatInterval: FiniteDuration   = 50.millis,
  maxEntriesPerRequest: Int           = 100,
  preVoteEnabled: Boolean             = true,
  leaderStickinessEnabled: Boolean    = true,
  leaderLeaseDuration: FiniteDuration = 100.millis,
  parallelReplicationEnabled: Boolean = true,
  batching: BatchConfig               = BatchConfig(enabled = false, maxSize = 100, maxWait = 10.millis),
  pipelining: PipelineConfig          = PipelineConfig(enabled = false, maxInflight = 10),
  compaction: CompactionConfig        = CompactionConfig(),
  checkQuorumEnabled: Boolean         = true,
  leaseReadEnabled: Boolean           = false,
  electionTimeoutTicks: Int           = 10,
  heartbeatIntervalTicks: Int         = 1,
  maxInboundQueueSize: Int            = 8192,
  maxPendingProposals: Int            = 8192
)
```

Use `RaftConfig.validate(...)`, `BatchConfig.validate(...)`, `PipelineConfig.validate(...)`, and `ClusterConfig.validate(...)` for pure error-accumulating validation (`Validated[ConfigError, A]`).

---

## Log Entries & Cluster Membership

```scala
final case class Log(index: Long, term: Long, logType: LogType, payload: Bytes)
// Factories: Log.command(idx, term, Array[Byte]), Log.commandBytes(LogIndex, Term, Bytes),
//            Log.noOp(idx, term), Log.configuration(idx, term, ClusterConfig)
// Helpers:   log.data, log.typedIndex, log.typedTerm, log.stripForWitness

final case class ClusterConfig(
  members: Map[NodeId, NodeRole] = Map.empty,
  jointConfig: Option[Map[NodeId, NodeRole]] = None,
  pendingConfig: Option[Map[NodeId, NodeRole]] = None
)
// Wire format: ClusterConfig.encodeBytes / decodeBytes ("RCFG" v2 length-prefixed + CRC32)
```

| `NodeRole` | Votes & Quorum? | Can Campaign? | Command Payloads Replicated? |
|------------|:---------------:|:-------------:|:----------------------------:|
| `Voter` | Yes | Yes | Full (`Log.payload`) |
| `Learner` | No | No | Full (`Log.payload`) |
| `Witness` | Yes | No (`canCampaign == false`) | Stripped (`Log.stripForWitness`) |

---

## SPI Interfaces (`raft-runtime`)

| Interface | Core Methods | Contract / Built-In Implementations |
|-----------|-------------|-------------------------------------|
| `Transport[F, Wire]` | `send`, `sendBatch`, `broadcast`, `receive: Stream[F, (NodeId, Wire)]`, `localAddress`, `shutdown` | Best-effort; `InMemTransport`, `GrpcTransport`, `MultiGroupTransport` |
| `LogStore[F]` | `get`, `getRange`, `lastIndex`, `termAt`, `isEmpty`, `append`, `truncateFrom`, `clear`, `discardBefore` | Durable `fsync`/`msync`; `InMemLogStore`, `StorageBackedLogStore` |
| `StableStore[F]` | `currentTerm`, `setCurrentTerm`, `votedFor`, `setVotedFor`, `hardState`, `setHardState(Term, Option[NodeId])` | Atomic single-flush `setHardState`; `InMemStableStore`, `StorageBackedStableStore` |
| `SnapshotStore[F]` | `save(Snapshot[F])`, `loadLatest`, `list`, `delete(index)` | Atomic + dir `fsync`; `InMemSnapshotStore`, `StorageBackedSnapshotStore` |
| `StateMachine[F, R]` | `apply(log: Log): F[R]`, `snapshot: F[Stream[F, Byte]]`, `restore(Stream[F, Byte]): F[Unit]` | Strictly deterministic; output `R` returned via `Applied[R]` |
| `TimerService[F]` | `scheduleElection(delay, onTimeout)`, `scheduleHeartbeat(delay, onTimeout)`, `randomElectionTimeout(base)` | Cancellable `Fiber`s + jitter; `DefaultTimerService` (`TestControl`-safe) |
| `SimpleMessageCodec[W]` | `encode(RaftMessage): W`, `decode(W): Either[CodecError, RaftMessage]` | Total round-trip; `JsonCodec`, `ProtobufMessageCodec`, `ProtobufMultiGroupCodec` |

---

## Key API Entry Points Across All Modules

| API | Module | Primary Operations | Purpose |
|-----|--------|-------------------|---------|
| `ConsensusKernel` | `raft-core` | `initialize`, `tick`, `step`, `propose`, `proposeBatch`, `proposeConfChange`, `readIndex`, `transferLeader`, `ready`, `advance` | `0`-`var` pure Mealy engine with `UnstableLog` & SplitMix64 PRNG |
| `RawNode` | `raft-core` | `tick()`, `step(from, msg)`, `proposeBytes`, `proposeBatchBytes`, `proposeConfChange`, `readIndex`, `transferLeader`, `hasReady`, `ready()`, `advance(rd)` | Thin Sans-I/O driver (`tikv/raft-rs` style, zero external dependencies) |
| `Ready` / `LightReady` | `raft-core` | `hardState`, `softState`, `truncateFromIndex`, `entries`, `snapshot`, `messages`, `persistedMessages`, `committedEntries`, `readStates` | Two-phase persistence barrier enforcing §5.1/§5.2 Persistence-Before-RPC |
| `ProgressTracker` | `raft-core` | `Probe`, `Replicate`, `Snapshot`, `Inflights`, `tallyVotes`, `maximalCommittedIndex`, `quorumRecentlyActive` | Per-peer replication flow control & Joint Consensus dual-quorum math |
| `RaftLogic` | `raft-core` | `onMessage`, `onVoteResponse` → `Transition(NodeState, List[Effect])` | Stateless pure transition functions over 18-case `Effect` ADT |
| `RaftNode[F, R]` | `raft-runtime` | `proposeAndApply(Bytes): F[Applied[R]]`, `proposeBatchAndApply`, `readIndex`, `leaseRead`, `proposeConfChange`, `transferLeadership` | Typed Cats Effect runtime with `TermRingBuffer` & lock-free RPC dispatch |
| `MultiRaftNode[F]` | `raft-runtime` | `createGroup`, `createTypedGroup[R]`, `removeGroup`, `proposeAndApplyToGroup`, `proposeBatchAndCommitToGroup`, `readIndexFromGroup`, `leaseReadFromGroup`, `rebalanceLeadership` | Multi-group shard coordinator with `AtomicCell` lifecycle & `CoalescedHeartbeatConfig` |
| `MmapWalEngine[F]` | `raft-storage-mmap` | `MmapWalEngine.resource[F](dir, segmentSize)`, `EngineBackedRaftStorage.open` | JDK 22+ FFM off-heap segmented WAL, `SegmentLease`, radix `WalIndexState`, `MmapHardState` |
| `GrpcTransport[F]` | `raft-transport-grpc` | `GrpcTransport.raftResource`, `GrpcTransport.multiGroupResource`, `updatePeers`, `transportStats` | Netty HTTP/2 + Protobuf batching, `WireAck` backpressure & mTLS identity binding |
| `LinearizabilityChecker` | `raft-jepsen` | `LinearizabilityChecker.check(history, KVModel | RegisterModel | CounterModel)` | Wing & Gong / Lowe (WGL) linearizability checker with P-compositionality |
