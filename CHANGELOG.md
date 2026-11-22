# Changelog

All notable changes to the **Functional RAFT Library for Scala 3** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) with automated MiMa binary compatibility verification starting from `v0.2.0`.

---

## [Unreleased]

### Added
- **MiMa Binary Compatibility Enforcement**: Configured `sbt-mima-plugin` across all four published modules (`raft-core`, `raft-runtime`, `raft-storage-mmap`, `raft-transport-grpc`) against the `v0.2.0` release baseline, wired into both CI and release workflows.
- **Changelog**: Added `CHANGELOG.md` tracking releases and upcoming `v0.2.1` preparation.

### Changed
- **Documentation Sync**: Updated `README.md` to reflect the 7-module architecture (including `raft-itest`), all 18 runnable examples (including flagship `ShardedBankLedgerExample`), `0.2.0` coordinates, and `sbt mimaReportBinaryIssues` verification.

---

## [0.2.0] — 2026-11-20

### Added
- **7-Module Sans-I/O Architecture**: Zero-dependency `raft-core` (JDK 21+ bytecode) with optional turnkey modules `raft-runtime`, `raft-storage-mmap`, `raft-transport-grpc`, `raft-jepsen`, `raft-examples`, and `raft-itest`.
- **Pure Functional Consensus Kernel (`ConsensusKernel` & `RawNode`)**: Immutable `Ready` / `LightReady` state machine with structural Persistence-Before-RPC, `ProgressTracker` (`Probe` / `Replicate` / `Snapshot` + `Inflights`), `Bytes`, error-accumulating `Validated` smart constructors, and embedded SplitMix64 PRNG.
- **Crash-Safe Off-Heap FFM WAL (`raft-storage-mmap`)**: JDK 22+ `MemorySegment` segmented WAL with lock-free `SegmentLease` pins, 2-level paged `WalIndexState`, CRC32-protected `MmapHardState`, torn-tail `SIGKILL` self-healing, and `"RSNP"` v2 streaming snapshots.
- **Typed Lock-Free Runtime (`raft-runtime`)**: Strongly typed `RaftNode[F, R]` returning `Applied[R]`, lock-free outbound RPC dispatch outside `Mutex[F]`, `O(1)` `TermRingBuffer`, group-commit batching, pipelining, and disk-backed streaming snapshots.
- **Multi-Raft & Coalesced Heartbeats**: `MultiRaftNode` group lifecycle coordinator, native Protobuf multi-group framing, and `CoalescedHeartbeatConfig`.
- **Hardened gRPC Transport (`raft-transport-grpc`)**: Lock-free `AtomicCell` channel pool, single-RPC `WireEnvelope.payloads` batching, `WireAck` backpressure, and mTLS X.509 peer-identity (`CN` / `DNS` SAN / SPIFFE `URI` SAN) binding.
- **Verification & Examples**: Split-persist Deterministic Simulation Testing (DST), stateful FFM WAL model tests, Jepsen WGL linearizability checker, Multi-JVM `SIGKILL -9` E2E suite, 88% Scoverage gate, and 18 runnable examples including `ShardedBankLedgerExample`.

### Fixed
- Anchored leader lease expiration to send timestamp and enforced Ongaro §6.2 vote stickiness.
- Failed in-flight post-append proposals with `ProposalDropped` upon leader step-down.
- Hardened `ReadIndex` heartbeats, Ongaro §4.1 conf-change gate, and `"RSNP"` snapshot envelopes.

### Changed
- Disabled `crossPaths` on published artifacts (`raft-core`, `raft-runtime`, `raft-storage-mmap`, `raft-transport-grpc`) to drop the `_3` Scala suffix.

---

## [0.1.0] — 2026-11-15

### Added
- Initial public release of the Functional RAFT Library for Scala 3 with core Raft consensus state machine, Cats Effect runtime, examples, and documentation book.

[Unreleased]: https://github.com/bmscomp/raft/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/bmscomp/raft/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/bmscomp/raft/releases/tag/v0.1.0
