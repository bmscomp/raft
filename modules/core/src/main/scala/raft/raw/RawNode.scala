package raft.raw

import java.util.concurrent.atomic.AtomicReference

import raft.kernel.{ConsensusKernel, KernelState}
import raft.logic.ProgressTracker
import raft.message.RaftMessage
import raft.state.*

/** Sans-I/O deterministic Raft consensus driver modeled after Rust's
  * `tikv/raft-rs` `RawNode`.
  *
  * Thin cursor delegating 100% of consensus transitions, quorum calculations,
  * Joint Consensus rollback, multi-chunk snapshot assembly, and
  * persistence-before-RPC invariants to the pure, immutable
  * [[raft.kernel.ConsensusKernel]] Mealy machine.
  */
final class RawNode(
    val config: RaftConfig,
    val storage: RaftStorage,
    initialSeed: Long = 0x9e3779b97f4a7c15L
):
  private val cell = new AtomicReference[KernelState](
    ConsensusKernel.initialize(config, storage, initialSeed)
  )

  /** Inspect the underlying immutable [[raft.kernel.KernelState]] snapshot. */
  def kernelState: KernelState = cell.get()

  def state: NodeState = cell.get().roleState
  def currentTerm: Long = cell.get().currentTerm
  def commitIndex: Long = cell.get().commitIndex
  def lastApplied: Long = cell.get().appliedIndex
  def clusterConfig: ClusterConfig = cell.get().clusterConfig
  def progressTracker: ProgressTracker = cell.get().tracker
  def currentTick: Long = cell.get().logicalTick
  def leaderId: Option[NodeId] = cell.get().leaderId(config.localId)

  def lastLogIndex: Long =
    ConsensusKernel.lastLogIndex(cell.get(), storage)

  def termAt(index: Long): Option[Long] =
    ConsensusKernel.termAt(cell.get(), storage, index)

  def lastLogTerm: Long =
    ConsensusKernel.lastLogTerm(cell.get(), storage)

  /** Advance logical time by one tick (`tikv/raft-rs` `RawNode::tick`). */
  def tick(): Unit =
    val _ = cell.updateAndGet(st => ConsensusKernel.tick(st, config, storage))

  /** Propose an immutable [[Bytes]] command payload. */
  def proposeBytes(payload: Bytes): Either[RaftError, Long] =
    val (next, res) =
      ConsensusKernel.propose(cell.get(), payload, config, storage)
    cell.set(next)
    res

  /** Propose a client command payload (defensively copied into [[Bytes]]). */
  def propose(data: Array[Byte]): Either[RaftError, Long] =
    proposeBytes(Bytes.copyFrom(data))

  /** Atomically propose a batch of immutable [[Bytes]] command payloads. */
  def proposeBatchBytes(commands: Seq[Bytes]): Either[RaftError, Vector[Long]] =
    val (next, res) =
      ConsensusKernel.proposeBatch(cell.get(), commands, config, storage)
    cell.set(next)
    res

  /** Atomically propose a batch of client command payloads. */
  def proposeBatch(
      commands: Seq[Array[Byte]]
  ): Either[RaftError, Vector[Long]] =
    proposeBatchBytes(commands.map(Bytes.copyFrom))

  /** Propose a safe dynamic membership change (`ConfChangeV2` Joint Consensus
    * §6).
    */
  def proposeConfChange(
      newMembers: Map[NodeId, NodeRole]
  ): Either[RaftError, Long] =
    val (next, res) =
      ConsensusKernel.proposeConfChange(cell.get(), newMembers, config, storage)
    cell.set(next)
    res

  /** Request a linearizable read index (`ReadIndex` / `LeaseRead` §6.4). */
  def readIndex(requestCtx: String): Either[RaftError, Unit] =
    val (next, res) =
      ConsensusKernel.readIndex(cell.get(), requestCtx, config, storage)
    cell.set(next)
    res

  /** Initiate graceful leadership transfer to `transferee` (§3.10). */
  def transferLeader(transferee: NodeId): Either[RaftError, Unit] =
    val (next, res) =
      ConsensusKernel.transferLeader(cell.get(), transferee, config, storage)
    cell.set(next)
    res

  /** Step the state machine with an inbound message from `from`. */
  def step(from: NodeId, msg: RaftMessage): Unit =
    val _ = cell.updateAndGet(st =>
      ConsensusKernel.step(st, from, msg, config, storage)
    )

  /** True iff there is pending persistence, network, or state-machine work. */
  def hasReady: Boolean =
    ConsensusKernel.hasReady(cell.get(), config, storage)

  /** Snapshot the current [[Ready]] batch without mutating watermarks. */
  def ready(): Ready =
    ConsensusKernel.ready(cell.get(), config, storage)

  /** Acknowledge execution of `rd` and obtain any follow-up [[LightReady]]. */
  def advance(rd: Ready): LightReady =
    val (next, light) =
      ConsensusKernel.advance(cell.get(), rd, config, storage)
    cell.set(next)
    light
