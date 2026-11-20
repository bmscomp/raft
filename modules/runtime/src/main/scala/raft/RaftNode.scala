package raft

import scala.annotation.tailrec
import scala.concurrent.duration.*

import cats.effect.*
import cats.effect.implicits.*
import cats.effect.std.{AtomicCell, Mutex, Queue, Supervisor}
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

import raft.compaction.{CompactionManager, SnapshotTransfer}
import raft.effect.Effect as Eff
import raft.logic.{Inflights, Progress, ProgressState, RaftLogic}
import raft.message.*
import raft.message.RaftMessage.*
import raft.metrics.RaftMetrics
import raft.spi.{
  LogStore,
  RaftTransport,
  SnapshotStore,
  StableStore,
  StateMachine,
  TimerService
}
import raft.state.*
import raft.state.NodeState.*

/** Convenience alias for a [[RaftNode]] whose [[raft.spi.StateMachine]] yields
  * `Unit`.
  */
type UnitRaftNode[F[_]] = RaftNode[F, Unit]

/** Convenience alias for an existentially / covariantly projected [[RaftNode]]
  * (e.g. inside heterogeneous Multi-Raft coordinators).
  */
type AnyRaftNode[F[_]] = RaftNode[F, Any]

/** Typed, lock-free-I/O Cats Effect consensus runtime parameterized over both
  * the effect type `F[_]` and the [[raft.spi.StateMachine]] output type `R`.
  *
  *   - **Typed State-Machine Outputs (`Applied[R]`)**: [[proposeAndApply]] and
  *     [[proposeBatchAndApply]] return both the committed `LogId` and the exact
  *     value `R` produced by `StateMachine.apply(log)` without side-channel
  *     mutable maps (**fixes FP3**).
  *   - **Lock-Free Network Dispatch & `O(1)` Term Ring-Buffer**: consensus
  *     state transitions and WAL appends run inside `Mutex[F]`, while all
  *     network RPCs (`send`, `sendBatch`, `broadcast`) execute **outside** the
  *     lock, `TermRingBuffer` eliminates `O(N)` full-log scans, and committed
  *     entries are fetched via a single batched `getRange` (**fixes FP4**).
  *   - **True Disk-Backed Snapshot Streaming & `Supervisor[F]`**: leader
  *     snapshots stream `"RSNP"` v2 headers + `Snapshot.data` without
  *     `compile.to(Array)`, follower chunks stream to disk via
  *     [[raft.state.FilePendingSnapshot]], queues are bounded, and background
  *     fibers are supervised (**fixes FP5, FP6**).
  */
trait RaftNode[F[_], R]:
  /** Start (or restart) the RAFT node event loop. */
  def start: F[Unit]

  /** Propose an immutable [[raft.state.Bytes]] command payload and return its
    * local leader `(term, index)` once appended.
    */
  def proposeBytes(payload: Bytes): F[LogId]

  /** Propose a client command payload (`Array[Byte]`, defensively copied) and
    * return its assigned `(term, index)` coordinate once appended.
    */
  def propose(data: Array[Byte]): F[LogId] =
    proposeBytes(Bytes.copyFrom(data))

  /** Propose an immutable [[raft.state.Bytes]] command payload, suspend until
    * committed by a quorum and applied to the local [[raft.spi.StateMachine]],
    * and return both its committed `LogId` and typed state-machine output `R`.
    */
  def proposeAndApply(payload: Bytes): F[Applied[R]]

  /** `Array[Byte]` convenience overload for [[proposeAndApply]]. */
  def proposeAndApply(data: Array[Byte]): F[Applied[R]] =
    proposeAndApply(Bytes.copyFrom(data))

  /** Propose a client command payload and suspend until committed and applied,
    * returning its committed [[raft.state.LogId]].
    */
  def proposeAndCommit(data: Array[Byte]): F[LogId]

  /** `Bytes` variant of [[proposeAndCommit]]. */
  def proposeBytesAndCommit(payload: Bytes): F[LogId]

  /** Atomically propose a batch of [[raft.state.Bytes]] commands in a single
    * group-commit append and return their assigned `(term, index)` coordinates.
    */
  def proposeBatchBytes(commands: Seq[Bytes]): F[Vector[LogId]]

  /** Atomically propose a batch of `Array[Byte]` commands in a single
    * group-commit append and return their assigned `(term, index)` coordinates.
    */
  def proposeBatch(commands: Seq[Array[Byte]]): F[Vector[LogId]] =
    proposeBatchBytes(commands.map(Bytes.copyFrom))

  /** Atomically propose a batch of [[raft.state.Bytes]] commands and suspend
    * until all entries are committed and applied, returning their typed
    * [[raft.state.Applied]] outputs in order.
    */
  def proposeBatchAndApply(commands: Seq[Bytes]): F[Vector[Applied[R]]]

  /** `Array[Byte]` convenience overload for [[proposeBatchAndApply]]. */
  def proposeBatchAndApplyArrays(
      commands: Seq[Array[Byte]]
  ): F[Vector[Applied[R]]] =
    proposeBatchAndApply(commands.map(Bytes.copyFrom))

  /** Atomically propose a batch of `Array[Byte]` commands and suspend until all
    * entries are committed and applied, returning their committed
    * [[raft.state.LogId]]s.
    */
  def proposeBatchAndCommit(commands: Seq[Array[Byte]]): F[Vector[LogId]]

  /** Fire-and-forget proposal shorthand. */
  def submit(data: Array[Byte]): F[Unit]

  /** Confirm leadership with a voting quorum (§6.4 `ReadIndex`) and suspend
    * until `lastApplied >= confirmedCommitIndex`, returning the linearizable
    * read index.
    */
  def readIndex: F[Long]

  /** Attempt a zero-RTT linearizable read index check (§6.4 `LeaseRead`) while
    * `config.leaseReadEnabled` is enabled and this leader's monotonic lease is
    * valid.
    */
  def leaseRead: F[Option[Long]]

  /** Propose a cluster membership transition (§6), automatically orchestrating
    * Joint Consensus (`C_old,new -> C_new`) and leader self-removal step-down.
    */
  def proposeConfChange(newConfig: ClusterConfig): F[LogId]

  /** Initiate graceful leadership transfer to `target` (§3.10). */
  def transferLeadership(target: NodeId): F[Unit]

  /** Inspect the current consensus role, term, and leader state. */
  def getState: F[NodeState]

  /** Inspect the currently active [[raft.state.ClusterConfig]]. */
  def getClusterConfig: F[ClusterConfig]

  /** Gracefully stop timers, supervised background tasks, and the event loop.
    */
  def shutdown: F[Unit]

  /** Functorial projection over the state-machine output type `R`. */
  def mapOutput[R2](f: R => R2)(using F: cats.Functor[F]): RaftNode[F, R2] =
    val self = this
    new RaftNode[F, R2]:
      def start: F[Unit] = self.start
      def proposeBytes(payload: Bytes): F[LogId] = self.proposeBytes(payload)
      def proposeAndApply(payload: Bytes): F[Applied[R2]] =
        F.map(self.proposeAndApply(payload))(a => Applied(a.logId, f(a.value)))
      def proposeAndCommit(data: Array[Byte]): F[LogId] =
        self.proposeAndCommit(data)
      def proposeBytesAndCommit(payload: Bytes): F[LogId] =
        self.proposeBytesAndCommit(payload)
      def proposeBatchBytes(commands: Seq[Bytes]): F[Vector[LogId]] =
        self.proposeBatchBytes(commands)
      def proposeBatchAndApply(
          commands: Seq[Bytes]
      ): F[Vector[Applied[R2]]] =
        F.map(self.proposeBatchAndApply(commands))(
          _.map(a => Applied(a.logId, f(a.value)))
        )
      def proposeBatchAndCommit(commands: Seq[Array[Byte]]): F[Vector[LogId]] =
        self.proposeBatchAndCommit(commands)
      def submit(data: Array[Byte]): F[Unit] = self.submit(data)
      def readIndex: F[Long] = self.readIndex
      def leaseRead: F[Option[Long]] = self.leaseRead
      def proposeConfChange(newConfig: ClusterConfig): F[LogId] =
        self.proposeConfChange(newConfig)
      def transferLeadership(target: NodeId): F[Unit] =
        self.transferLeadership(target)
      def getState: F[NodeState] = self.getState
      def getClusterConfig: F[ClusterConfig] = self.getClusterConfig
      def shutdown: F[Unit] = self.shutdown

  /** Widen this node's output type to `Any` (`AnyRaftNode[F]`). */
  def widenOutput(using cats.Functor[F]): AnyRaftNode[F] =
    mapOutput(v => v: Any)

object RaftNode:

  /** Partially-applied builder enabling `RaftNode[IO](config, ..., sm, timer)`
    * to infer `R` directly from `sm: StateMachine[IO, R]`.
    */
  final class Builder[F[_]](using F: Async[F]):
    def apply[R](
        config: RaftConfig,
        cluster: ClusterConfig,
        transport: RaftTransport[F],
        logStore: LogStore[F],
        stableStore: StableStore[F],
        stateMachine: StateMachine[F, R],
        timerService: TimerService[F],
        metrics: Option[RaftMetrics[F]] = None,
        snapshotStoreOpt: Option[SnapshotStore[F]] = None,
        loggerOpt: Option[Logger[F]] = None
    ): Resource[F, RaftNode[F, R]] =
      typed[F, R](
        config = config,
        cluster = cluster,
        transport = transport,
        logStore = logStore,
        stableStore = stableStore,
        stateMachine = stateMachine,
        timerService = timerService,
        metrics = metrics,
        snapshotStoreOpt = snapshotStoreOpt,
        loggerOpt = loggerOpt
      )

  /** Create a managed [[RaftNode]] resource with `R` inferred from
    * `stateMachine`.
    */
  def apply[F[_]](using Async[F]): Builder[F] = new Builder[F]

  /** Explicitly parameterized [[RaftNode]] resource constructor. */
  def typed[F[_]: Async, R](
      config: RaftConfig,
      cluster: ClusterConfig,
      transport: RaftTransport[F],
      logStore: LogStore[F],
      stableStore: StableStore[F],
      stateMachine: StateMachine[F, R],
      timerService: TimerService[F],
      metrics: Option[RaftMetrics[F]] = None,
      snapshotStoreOpt: Option[SnapshotStore[F]] = None,
      loggerOpt: Option[Logger[F]] = None
  ): Resource[F, RaftNode[F, R]] =
    Supervisor[F](await = false).flatMap { supervisor =>
      val resolvedMetrics = metrics.getOrElse(RaftMetrics.noop[F])
      val resolvedLogger = loggerOpt.getOrElse(NoOpLogger[F])
      val acquire: F[RaftNode[F, R]] =
        for
          mutex <- Mutex[F]
          stateRef <- Ref.of[F, NodeState](Follower(0L, None, None))
          clusterRef <- Ref.of[F, ClusterConfig](cluster)
          shutdownRef <- SignallingRef.of[F, Boolean](false)
          msgQueue <- Queue.bounded[F, RaftMessage](config.maxInboundQueueSize)
          lastAppliedRef <- Ref.of[F, Long](0L)
          latestSnapMetaRef <- Ref.of[F, Option[SnapshotMetadata]](None)
          pendingSnapRef <- Ref.of[F, Option[FilePendingSnapshot[F]]](None)
          termRingRef <- Ref.of[F, TermRingBuffer](TermRingBuffer.empty())
          pendingProposalsRef <- Ref.of[
            F,
            Map[Long, (Long, CommitWaiter[F, R])]
          ](Map.empty)
          jointWaiterRef <- Ref.of[
            F,
            Map[Long, Deferred[F, Either[RaftError, LogId]]]
          ](Map.empty)
          readSeqRef <- Ref.of[F, Long](0L)
          pendingReadsRef <- Ref.of[
            F,
            Map[String, Deferred[F, Either[RaftError, Long]]]
          ](Map.empty)
          readApplyWaitersRef <- Ref.of[
            F,
            Vector[(Long, Deferred[F, Either[RaftError, Long]])]
          ](Vector.empty)
          leaseUntilRef <- Ref.of[F, FiniteDuration](Duration.Zero)
          peerLastAckRef <- Ref.of[F, Map[NodeId, FiniteDuration]](Map.empty)
          peerPendingSendsRef <- Ref.of[
            F,
            Map[NodeId, Vector[(Long, FiniteDuration)]]
          ](Map.empty)
          snapshotInFlightRef <- Ref.of[F, Set[NodeId]](Set.empty)
          proposalQueue <- Queue.bounded[F, QueuedProposal[F, R]](
            config.maxPendingProposals
          )
          peerProgressRef <- Ref.of[F, Map[NodeId, Progress]](Map.empty)
          compactionMgrOpt <- snapshotStoreOpt.traverse { store =>
            CompactionManager.create[F](
              config = config.compaction,
              logStore = logStore,
              snapshotStore = store,
              stateMachine = stateMachine,
              nodeId = config.localId,
              metrics = resolvedMetrics,
              logger = resolvedLogger,
              clusterProvider = Some(clusterRef.get.map(Some(_))),
              onCompacted = Some { meta =>
                latestSnapMetaRef.set(Some(meta)) *>
                  termRingRef.update(
                    _.withSnapshot(
                      meta.lastIncludedIndex,
                      meta.lastIncludedTerm
                    ).discardBefore(
                      math.max(
                        1L,
                        meta.lastIncludedIndex - config.compaction.retainedEntries
                      )
                    )
                  )
              }
            )
          }
          snapshotTransfer = new SnapshotTransfer[F](
            chunkSize = math.max(1, config.compaction.snapshotChunkSize),
            transport = transport,
            logger = resolvedLogger
          )
          eventLoopFiberRef <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](
            None
          )
          batcherFiberRef <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](None)
          electionFiberRef <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](None)
          heartbeatFiberRef <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](
            None
          )
          checkQuorumFiberRef <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](
            None
          )
          transferFiberRef <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](None)
          stickinessUntilRef <- Ref.of[F, FiniteDuration](Duration.Zero)
          peerMailboxesCell <- AtomicCell[F].of(
            Map.empty[NodeId, (Queue[F, PeerSend], Fiber[F, Throwable, Unit])]
          )
        yield new RaftNodeImpl[F, R](
          config = config,
          bootstrapCluster = cluster,
          clusterRef = clusterRef,
          transport = transport,
          logStore = logStore,
          stableStore = stableStore,
          snapshotStoreOpt = snapshotStoreOpt,
          compactionMgrOpt = compactionMgrOpt,
          snapshotTransfer = snapshotTransfer,
          stateMachine = stateMachine,
          timerService = timerService,
          metrics = resolvedMetrics,
          supervisor = supervisor,
          mutex = mutex,
          stateRef = stateRef,
          shutdownRef = shutdownRef,
          msgQueue = msgQueue,
          proposalQueue = proposalQueue,
          peerProgressRef = peerProgressRef,
          termRingRef = termRingRef,
          lastAppliedRef = lastAppliedRef,
          latestSnapMetaRef = latestSnapMetaRef,
          pendingSnapRef = pendingSnapRef,
          pendingProposalsRef = pendingProposalsRef,
          jointWaiterRef = jointWaiterRef,
          readSeqRef = readSeqRef,
          pendingReadsRef = pendingReadsRef,
          readApplyWaitersRef = readApplyWaitersRef,
          leaseUntilRef = leaseUntilRef,
          peerLastAckRef = peerLastAckRef,
          peerPendingSendsRef = peerPendingSendsRef,
          stickinessUntilRef = stickinessUntilRef,
          snapshotInFlightRef = snapshotInFlightRef,
          peerMailboxesCell = peerMailboxesCell,
          eventLoopFiberRef = eventLoopFiberRef,
          batcherFiberRef = batcherFiberRef,
          electionFiberRef = electionFiberRef,
          heartbeatFiberRef = heartbeatFiberRef,
          checkQuorumFiberRef = checkQuorumFiberRef,
          transferFiberRef = transferFiberRef,
          logger = resolvedLogger
        )
      Resource.make(acquire)(_.shutdown)
    }

private enum CommitWaiter[F[_], R]:
  case ForApplied(deferred: Deferred[F, Either[RaftError, Applied[R]]])
  case ForLogId(deferred: Deferred[F, Either[RaftError, LogId]])

  def fail(err: RaftError)(using Functor: cats.Functor[F]): F[Unit] =
    this match
      case ForApplied(d) => Functor.void(d.complete(Left(err)))
      case ForLogId(d)   => Functor.void(d.complete(Left(err)))

private enum QueuedReply[F[_], R]:
  case LocalAppend(deferred: Deferred[F, Either[RaftError, LogId]])
  case CommitApplied(deferred: Deferred[F, Either[RaftError, Applied[R]]])

  def fail(err: RaftError)(using Functor: cats.Functor[F]): F[Unit] =
    this match
      case LocalAppend(d)   => Functor.void(d.complete(Left(err)))
      case CommitApplied(d) => Functor.void(d.complete(Left(err)))

private final case class QueuedProposal[F[_], R](
    payload: Bytes,
    reply: QueuedReply[F, R],
    cancelled: Ref[F, Boolean]
)

private enum PeerSend:
  case Single(msg: RaftMessage)
  case Batch(msgs: Vector[RaftMessage])

private enum OutboundAction:
  case SendOne(to: NodeId, msg: RaftMessage)
  case SendBatch(to: NodeId, msgs: Vector[RaftMessage])
  case SendGroup(
      byPeer: List[(NodeId, List[RaftMessage])],
      heartbeatPeerCount: Int
  )
  case BroadcastMsg(msg: RaftMessage, heartbeatPeerCount: Int)

private final class RaftNodeImpl[F[_]: Async, R](
    config: RaftConfig,
    bootstrapCluster: ClusterConfig,
    clusterRef: Ref[F, ClusterConfig],
    transport: RaftTransport[F],
    logStore: LogStore[F],
    stableStore: StableStore[F],
    snapshotStoreOpt: Option[SnapshotStore[F]],
    compactionMgrOpt: Option[CompactionManager[F]],
    snapshotTransfer: SnapshotTransfer[F],
    stateMachine: StateMachine[F, R],
    timerService: TimerService[F],
    metrics: RaftMetrics[F],
    supervisor: Supervisor[F],
    mutex: Mutex[F],
    stateRef: Ref[F, NodeState],
    shutdownRef: SignallingRef[F, Boolean],
    msgQueue: Queue[F, RaftMessage],
    proposalQueue: Queue[F, QueuedProposal[F, R]],
    peerProgressRef: Ref[F, Map[NodeId, Progress]],
    termRingRef: Ref[F, TermRingBuffer],
    lastAppliedRef: Ref[F, Long],
    latestSnapMetaRef: Ref[F, Option[SnapshotMetadata]],
    pendingSnapRef: Ref[F, Option[FilePendingSnapshot[F]]],
    pendingProposalsRef: Ref[F, Map[Long, (Long, CommitWaiter[F, R])]],
    jointWaiterRef: Ref[F, Map[Long, Deferred[F, Either[RaftError, LogId]]]],
    readSeqRef: Ref[F, Long],
    pendingReadsRef: Ref[F, Map[String, Deferred[F, Either[RaftError, Long]]]],
    readApplyWaitersRef: Ref[
      F,
      Vector[(Long, Deferred[F, Either[RaftError, Long]])]
    ],
    leaseUntilRef: Ref[F, FiniteDuration],
    peerLastAckRef: Ref[F, Map[NodeId, FiniteDuration]],
    peerPendingSendsRef: Ref[F, Map[NodeId, Vector[(Long, FiniteDuration)]]],
    stickinessUntilRef: Ref[F, FiniteDuration],
    snapshotInFlightRef: Ref[F, Set[NodeId]],
    peerMailboxesCell: AtomicCell[
      F,
      Map[NodeId, (Queue[F, PeerSend], Fiber[F, Throwable, Unit])]
    ],
    eventLoopFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]],
    batcherFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]],
    electionFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]],
    heartbeatFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]],
    checkQuorumFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]],
    transferFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]],
    logger: Logger[F]
) extends RaftNode[F, R]:

  private val F = Async[F]

  private val effectiveMaxInflight: Int =
    if config.pipelining.enabled then config.pipelining.maxInflight else 1

  def start: F[Unit] =
    for
      _ <- cancelFiber(eventLoopFiberRef)
      _ <- cancelFiber(batcherFiberRef)
      _ <- cancelFiber(electionFiberRef)
      _ <- cancelFiber(heartbeatFiberRef)
      _ <- cancelFiber(checkQuorumFiberRef)
      _ <- cancelFiber(transferFiberRef)
      _ <- cancelPeerMailboxes
      _ <- cleanupPendingSnapshot
      _ <- drainQueue
      _ <- drainProposalQueue(RaftError.ProposalDropped("Node restarting"))
      _ <- peerProgressRef.set(Map.empty)
      _ <- shutdownRef.set(false)
      _ <- leaseUntilRef.set(Duration.Zero)
      _ <- peerLastAckRef.set(Map.empty)
      _ <- peerPendingSendsRef.set(Map.empty)
      _ <- stickinessUntilRef.set(Duration.Zero)
      _ <- logger.info(s"Starting RAFT node ${config.localId.value}")
      hs <- stableStore.hardState
      _ <- recoverSnapshotAndConfig
      recoveredApplied <- lastAppliedRef.get
      _ <- stateRef.set(
        Follower(hs.term, hs.votedFor, None, recoveredApplied)
      )
      _ <- resetElectionTimer
      fiber <- supervisor.supervise(runEventLoop)
      _ <- eventLoopFiberRef.set(Some(fiber))
      _ <-
        if config.batching.enabled then
          supervisor
            .supervise(runBatcherLoop)
            .flatMap(bf => batcherFiberRef.set(Some(bf)))
        else F.unit
    yield ()

  def proposeBytes(payload: Bytes): F[LogId] =
    if config.batching.enabled then enqueueLocalProposal(payload)
    else
      mutex.lock
        .surround(appendCommandsLocked(Vector(payload), Map.empty))
        .flatMap { case (ids, out) => dispatchOutbound(out).as(ids.head) }

  def proposeAndApply(payload: Bytes): F[Applied[R]] =
    if config.batching.enabled then enqueueAppliedProposal(payload)
    else proposeBatchAndApplyDirect(Vector(payload)).map(_.head)

  def proposeBytesAndCommit(payload: Bytes): F[LogId] =
    proposeAndApply(payload).map(_.logId)

  def proposeAndCommit(data: Array[Byte]): F[LogId] =
    proposeBytesAndCommit(Bytes.copyFrom(data))

  def proposeBatchBytes(commands: Seq[Bytes]): F[Vector[LogId]] =
    val vec = commands.toVector
    if vec.isEmpty || !config.batching.enabled then
      mutex.lock
        .surround(appendCommandsLocked(vec, Map.empty))
        .flatMap { case (ids, out) => dispatchOutbound(out).as(ids) }
    else enqueueBatchLocalProposals(vec)

  def proposeBatchAndApply(commands: Seq[Bytes]): F[Vector[Applied[R]]] =
    val vec = commands.toVector
    if vec.isEmpty || !config.batching.enabled then
      proposeBatchAndApplyDirect(vec)
    else enqueueBatchAppliedProposals(vec)

  def proposeBatchAndCommit(commands: Seq[Array[Byte]]): F[Vector[LogId]] =
    proposeBatchAndApply(commands.map(Bytes.copyFrom)).map(_.map(_.logId))

  def submit(data: Array[Byte]): F[Unit] =
    proposeBytes(Bytes.copyFrom(data)).void

  private def checkLeaderFast: F[Unit] =
    stateRef.get.flatMap {
      case l: Leader if l.transferTarget.isDefined =>
        F.raiseError(
          RaftError.LeadershipTransferInProgress(l.transferTarget.get)
        )
      case _: Leader   => F.unit
      case f: Follower => F.raiseError(RaftError.NotLeader(f.leaderId))
      case _: Candidate | _: PreCandidate =>
        F.raiseError(RaftError.ElectionInProgress)
    }

  private def offerProposalOrFail(item: QueuedProposal[F, R]): F[Unit] =
    proposalQueue.tryOffer(item).flatMap { accepted =>
      if accepted then F.unit
      else
        F.raiseError(
          RaftError.ProposalDropped(
            s"Pending proposal queue full (${config.maxPendingProposals})"
          )
        )
    }

  private def enqueueLocalProposal(payload: Bytes): F[LogId] =
    F.uncancelable { poll =>
      checkLeaderFast *> (
        for
          waiter <- Deferred[F, Either[RaftError, LogId]]
          cancelled <- Ref.of[F, Boolean](false)
          _ <- offerProposalOrFail(
            QueuedProposal(
              payload,
              QueuedReply.LocalAppend(waiter),
              cancelled
            )
          )
          res <- poll(waiter.get).onCancel(cancelled.set(true))
          logId <- F.fromEither(res)
        yield logId
      )
    }

  private def enqueueAppliedProposal(payload: Bytes): F[Applied[R]] =
    F.uncancelable { poll =>
      checkLeaderFast *> (
        for
          waiter <- Deferred[F, Either[RaftError, Applied[R]]]
          cancelled <- Ref.of[F, Boolean](false)
          _ <- offerProposalOrFail(
            QueuedProposal(
              payload,
              QueuedReply.CommitApplied(waiter),
              cancelled
            )
          )
          res <- poll(waiter.get).onCancel(
            cancelled.set(true) *>
              pendingProposalsRef.update { m =>
                m.filterNot {
                  case (_, (_, CommitWaiter.ForApplied(d))) => d eq waiter
                  case _                                    => false
                }
              }
          )
          applied <- F.fromEither(res)
        yield applied
      )
    }

  private def enqueueBatchLocalProposals(
      commands: Vector[Bytes]
  ): F[Vector[LogId]] =
    F.uncancelable { poll =>
      checkLeaderFast *> (
        for
          items <- commands.traverse { cmd =>
            for
              waiter <- Deferred[F, Either[RaftError, LogId]]
              cancelled <- Ref.of[F, Boolean](false)
            yield (
              waiter,
              QueuedProposal[F, R](
                cmd,
                QueuedReply.LocalAppend[F, R](waiter),
                cancelled
              )
            )
          }
          _ <- items.traverse_(pair => offerProposalOrFail(pair._2))
          results <- poll(items.traverse(_._1.get)).onCancel(
            items.traverse_(_._2.cancelled.set(true))
          )
          logIds <- results.traverse(F.fromEither)
        yield logIds
      )
    }

  private def enqueueBatchAppliedProposals(
      commands: Vector[Bytes]
  ): F[Vector[Applied[R]]] =
    F.uncancelable { poll =>
      checkLeaderFast *> (
        for
          items <- commands.traverse { cmd =>
            for
              waiter <- Deferred[F, Either[RaftError, Applied[R]]]
              cancelled <- Ref.of[F, Boolean](false)
            yield (
              waiter,
              QueuedProposal(cmd, QueuedReply.CommitApplied(waiter), cancelled)
            )
          }
          _ <- items.traverse_(pair => offerProposalOrFail(pair._2))
          waitersSet = items.map(_._1).toSet
          results <- poll(items.traverse(_._1.get)).onCancel(
            items.traverse_(_._2.cancelled.set(true)) *>
              pendingProposalsRef.update { m =>
                m.filterNot {
                  case (_, (_, CommitWaiter.ForApplied(d))) =>
                    waitersSet.contains(d)
                  case _ => false
                }
              }
          )
          applied <- results.traverse(F.fromEither)
        yield applied
      )
    }

  private def proposeBatchAndApplyDirect(
      commands: Vector[Bytes]
  ): F[Vector[Applied[R]]] =
    F.uncancelable { poll =>
      mutex.lock
        .surround {
          if commands.isEmpty then
            appendCommandsLocked(Vector.empty, Map.empty).map { case (_, out) =>
              (
                Vector.empty[Long],
                Vector.empty[Deferred[F, Either[RaftError, Applied[R]]]],
                out
              )
            }
          else
            for
              waiters <- commands
                .traverse(_ => Deferred[F, Either[RaftError, Applied[R]]])
              waiterMap = waiters.zipWithIndex.map { case (d, i) =>
                i -> CommitWaiter.ForApplied(d)
              }.toMap
              res <- appendCommandsLocked(commands, waiterMap)
            yield (res._1.map(_.index), waiters, res._2)
        }
        .flatMap { case (indices, waiters, outActions) =>
          dispatchOutbound(outActions) *> {
            if waiters.isEmpty then F.pure(Vector.empty)
            else
              val idxSet = indices.toSet
              poll(waiters.traverse(_.get))
                .onCancel(
                  pendingProposalsRef
                    .update(_.filterNot(kv => idxSet.contains(kv._1)))
                )
                .flatMap(_.traverse(F.fromEither))
          }
        }
    }

  private def appendCommandsLocked(
      commands: Vector[Bytes],
      commitWaiters: Map[Int, CommitWaiter[F, R]]
  ): F[(Vector[LogId], Vector[OutboundAction])] =
    stateRef.get.flatMap {
      case l: Leader if l.transferTarget.isDefined =>
        F.raiseError(
          RaftError.LeadershipTransferInProgress(l.transferTarget.get)
        )
      case l: Leader =>
        if commands.isEmpty then F.pure((Vector.empty, Vector.empty))
        else
          for
            lastCoord <- effectiveLastLog
            baseIdx = lastCoord._1
            entries = commands.zipWithIndex.map { case (payload, offset) =>
              Log.commandBytes(
                LogIndex.unsafeFrom(baseIdx + offset + 1L),
                Term.unsafeFrom(l.term),
                payload
              )
            }
            indexedWaiters = commitWaiters.map { case (offset, waiter) =>
              (baseIdx + offset + 1L) -> (l.term, waiter)
            }
            _ <- logStore.append(entries)
            _ <- termRingRef.update(_.append(entries))
            _ <-
              if indexedWaiters.nonEmpty then
                pendingProposalsRef.update(_ ++ indexedWaiters)
              else F.unit
            _ <- metrics.onEntriesAppended(config.localId, entries.size)
            lastBatchIdx = baseIdx + entries.size.toLong
            _ <- logger.debug(
              s"Leader appended batch of ${entries.size} command(s) up to index $lastBatchIdx"
            )
            cluster <- clusterRef.get
            outActions <-
              if cluster.hasQuorum(Set(config.localId)) then
                val committed = LogIndex.unsafeFrom(lastBatchIdx)
                for
                  _ <- stateRef.set(l.withCommitIndex(lastBatchIdx))
                  applyOut <- applyCommittedEntriesLocked(committed)
                  _ <- metrics.onEntriesCommitted(config.localId, lastBatchIdx)
                yield applyOut
              else
                prepareReplicateAllPeers(l, LogIndex.unsafeFrom(lastBatchIdx))
          yield (entries.map(e => LogId(l.term, e.index)), outActions)

      case f: Follower =>
        F.raiseError(RaftError.NotLeader(f.leaderId))
      case _: Candidate | _: PreCandidate =>
        F.raiseError(RaftError.ElectionInProgress)
    }

  private def runBatcherLoop: F[Unit] =
    val step: F[Unit] =
      for
        first <- proposalQueue.take
        maxBatch = config.batching.maxSize
        readyNow <-
          if maxBatch > 1 then proposalQueue.tryTakeN(Some(maxBatch - 1))
          else F.pure(Nil)
        initial = first +: readyNow.toVector
        batch <-
          if initial.size >= maxBatch || config.batching.maxWait <= Duration.Zero
          then F.pure(initial)
          else
            Clock[F].monotonic.flatMap { now =>
              collectMoreUntilDeadline(initial, now + config.batching.maxWait)
            }
        _ <- flushQueuedBatch(batch)
      yield ()

    Stream
      .repeatEval(step)
      .interruptWhen(shutdownRef)
      .compile
      .drain

  private def collectMoreUntilDeadline(
      buf: Vector[QueuedProposal[F, R]],
      deadline: FiniteDuration
  ): F[Vector[QueuedProposal[F, R]]] =
    val maxBatch = config.batching.maxSize
    if buf.size >= maxBatch then F.pure(buf)
    else
      proposalQueue.tryTakeN(Some(maxBatch - buf.size)).flatMap { readyNow =>
        val updated = buf ++ readyNow
        if updated.size >= maxBatch then F.pure(updated)
        else
          Clock[F].monotonic.flatMap { now =>
            val remaining = deadline - now
            if remaining <= Duration.Zero then F.pure(updated)
            else
              F.timeoutTo(
                proposalQueue.take.map(Some(_)),
                remaining,
                F.pure(None)
              ).flatMap {
                case Some(nextItem) =>
                  collectMoreUntilDeadline(updated :+ nextItem, deadline)
                case None =>
                  proposalQueue
                    .tryTakeN(Some(maxBatch - updated.size))
                    .map(updated ++ _)
              }
          }
      }

  private def flushQueuedBatch(
      items: Vector[QueuedProposal[F, R]]
  ): F[Unit] =
    items.filterA(it => it.cancelled.get.map(!_)).flatMap { activeItems =>
      if activeItems.isEmpty then F.unit
      else
        val commands = activeItems.map(_.payload)
        val commitWaiters = activeItems.zipWithIndex.collect {
          case (
                QueuedProposal(_, QueuedReply.CommitApplied(d), _),
                idx
              ) =>
            idx -> CommitWaiter.ForApplied(d)
        }.toMap
        mutex.lock
          .surround(appendCommandsLocked(commands, commitWaiters))
          .attempt
          .flatMap {
            case Right((logIds, outActions)) =>
              activeItems.zip(logIds).traverse_ { case (it, logId) =>
                it.reply match
                  case QueuedReply.LocalAppend(d) =>
                    d.complete(Right(logId)).void
                  case QueuedReply.CommitApplied(_) => F.unit
              } *> dispatchOutbound(outActions)
            case Left(raftErr: RaftError) =>
              activeItems.traverse_(_.reply.fail(raftErr))
            case Left(other) =>
              val dropErr = RaftError.ProposalDropped(
                Option(other.getMessage).getOrElse("Batch append failed")
              )
              activeItems.traverse_(_.reply.fail(dropErr))
          }
    }

  private def drainProposalQueue(err: RaftError): F[Unit] =
    proposalQueue.tryTakeN(None).flatMap { items =>
      items.traverse_(_.reply.fail(err))
    }

  def readIndex: F[Long] =
    F.uncancelable { poll =>
      mutex.lock
        .surround {
          for
            stopped <- shutdownRef.get
            _ <-
              if stopped then
                F.raiseError(RaftError.ProposalDropped("Node is shut down"))
              else F.unit
            seq <- readSeqRef.updateAndGet(_ + 1L)
            reqId = s"${config.localId.value}-read-$seq"
            waiter <- Deferred[F, Either[RaftError, Long]]
            _ <- pendingReadsRef.update(_.updated(reqId, waiter))
            outActions <- processEventLocked(None, ReadIndexRequest(reqId))
          yield (reqId, waiter, outActions)
        }
        .flatMap { case (reqId, waiter, outActions) =>
          dispatchOutbound(outActions) *>
            poll(waiter.get)
              .onCancel(pendingReadsRef.update(_ - reqId))
              .flatMap(F.fromEither)
        }
    }

  def leaseRead: F[Option[Long]] =
    F.uncancelable { poll =>
      mutex.lock
        .surround {
          shutdownRef.get.flatMap {
            case true =>
              F.raiseError(RaftError.ProposalDropped("Node is shut down"))
            case false =>
              stateRef.get.flatMap {
                case l: Leader =>
                  if !config.leaseReadEnabled then F.pure(None)
                  else
                    for
                      readyInTerm <- hasCommittedInCurrentTerm(l)
                      cluster <- clusterRef.get
                      now <- Clock[F].monotonic
                      leaseUntil <- leaseUntilRef.get
                      singleVoter = cluster.hasQuorum(Set(config.localId))
                      leaseValid =
                        readyInTerm && (singleVoter || now < leaseUntil)
                      action <-
                        if !leaseValid then F.pure(None)
                        else
                          lastAppliedRef.get.flatMap { applied =>
                            if applied >= l.commitIndex then
                              F.pure(
                                Some(Left(l.commitIndex)): Option[
                                  Either[
                                    Long,
                                    Deferred[F, Either[RaftError, Long]]
                                  ]
                                ]
                              )
                            else
                              Deferred[F, Either[RaftError, Long]].flatMap {
                                d =>
                                  readApplyWaitersRef
                                    .update(_ :+ (l.commitIndex -> d))
                                    .as(Some(Right(d)))
                              }
                          }
                    yield action
                case f: Follower =>
                  F.raiseError(RaftError.NotLeader(f.leaderId))
                case _: Candidate | _: PreCandidate =>
                  F.raiseError(RaftError.ElectionInProgress)
              }
          }
        }
        .flatMap {
          case None               => F.pure(None)
          case Some(Left(idx))    => F.pure(Some(idx))
          case Some(Right(await)) =>
            poll(await.get).flatMap {
              case Right(idx) => F.pure(Some(idx))
              case Left(err)  => F.raiseError(err)
            }
        }
    }

  def proposeConfChange(newConfig: ClusterConfig): F[LogId] =
    F.uncancelable { poll =>
      mutex.lock
        .surround {
          stateRef.get.flatMap {
            case l: Leader if l.transferTarget.isDefined =>
              F.raiseError(
                RaftError.LeadershipTransferInProgress(l.transferTarget.get)
              )
            case l: Leader =>
              for
                readyInTerm <- hasCommittedInCurrentTerm(l)
                _ <-
                  if !readyInTerm then
                    F.raiseError(RaftError.LeaderNotReadyInTerm(l.term))
                  else F.unit
                currentCluster <- clusterRef.get
                hasUncommittedCfg <- hasPendingConfigAfter(l.commitIndex)
                _ <-
                  if currentCluster.isInJointConsensus || hasUncommittedCfg then
                    F.raiseError(
                      RaftError.ConfigurationChangeInProgress(currentCluster)
                    )
                  else F.unit
                entersJoint =
                  !newConfig.isInJointConsensus &&
                    newConfig.voters != currentCluster.voters
                effectiveCfg =
                  if entersJoint then
                    currentCluster.beginJointConsensus(newConfig.members)
                  else newConfig
                waiter <- Deferred[F, Either[RaftError, LogId]]
                lastCoord <- effectiveLastLog
                lastIdx = lastCoord._1
                newIndex = lastIdx + 1L
                entry = Log.configuration(newIndex, l.term, effectiveCfg)
                _ <- logStore.append(Seq(entry))
                _ <- termRingRef.update(_.append(Seq(entry)))
                _ <- clusterRef.set(effectiveCfg)
                _ <- reconcilePeerProgress(effectiveCfg, newIndex)
                addedPeers =
                  (effectiveCfg.allNodes - config.localId) -- l.nextIndex.keySet
                updatedLeader = addedPeers.foldLeft(l) { (acc, peer) =>
                  acc.withNextIndex(peer, newIndex).withMatchIndex(peer, 0L)
                }
                _ <- stateRef.set(updatedLeader)
                _ <-
                  if config.checkQuorumEnabled && addedPeers.nonEmpty then
                    resetCheckQuorumTimer
                  else F.unit
                _ <-
                  if entersJoint then
                    jointWaiterRef.update(_.updated(newIndex, waiter))
                  else
                    pendingProposalsRef.update(
                      _.updated(
                        newIndex,
                        (l.term, CommitWaiter.ForLogId(waiter))
                      )
                    )
                _ <- metrics.onEntriesAppended(config.localId, 1)
                outActions <-
                  if effectiveCfg.hasQuorum(Set(config.localId)) then
                    val committed = LogIndex.unsafeFrom(newIndex)
                    for
                      _ <- stateRef.update {
                        case cur: Leader => cur.withCommitIndex(newIndex)
                        case other       => other
                      }
                      applyOut <- applyCommittedEntriesLocked(committed)
                      _ <- metrics.onEntriesCommitted(config.localId, newIndex)
                    yield applyOut
                  else
                    prepareReplicatePeers(
                      updatedLeader,
                      (currentCluster.allNodes ++ effectiveCfg.allNodes) - config.localId,
                      LogIndex.unsafeFrom(newIndex)
                    )
              yield (newIndex, entersJoint, waiter, outActions)
            case f: Follower =>
              F.raiseError(RaftError.NotLeader(f.leaderId))
            case _: Candidate | _: PreCandidate =>
              F.raiseError(RaftError.ElectionInProgress)
          }
        }
        .flatMap { case (idx, entersJoint, waiter, outActions) =>
          dispatchOutbound(outActions) *>
            poll(waiter.get)
              .onCancel(
                if entersJoint then jointWaiterRef.update(_ - idx)
                else pendingProposalsRef.update(_ - idx)
              )
              .flatMap(F.fromEither)
        }
    }

  def transferLeadership(target: NodeId): F[Unit] =
    mutex.lock
      .surround {
        stateRef.get.flatMap {
          case l: Leader =>
            if target == config.localId then F.pure(Vector.empty)
            else
              clusterRef.get.flatMap { cluster =>
                if !cluster.canCampaign(target) then
                  F.raiseError(
                    RaftError.ProposalDropped(
                      s"Transfer target ${target.value} is not an eligible voter"
                    )
                  )
                else
                  for
                    out1 <- processEventLocked(
                      None,
                      TransferLeadershipRequest(l.term, target)
                    )
                    out2 <- stateRef.get.flatMap {
                      case cur: Leader if cur.transferTarget.contains(target) =>
                        effectiveLastLog.flatMap { case (lastIdx, _) =>
                          val nextIdx =
                            cur.nextIndex.getOrElse(target, lastIdx + 1L)
                          val sendMissing =
                            if lastIdx > 0L && nextIdx <= lastIdx then
                              preparePeerReplication(
                                cur,
                                target,
                                LogIndex.unsafeFrom(lastIdx),
                                forceUnpause = true
                              )
                            else F.pure(Vector.empty)
                          sendMissing <* scheduleTransferTimeout(
                            cur.term,
                            target
                          )
                        }
                      case _ => F.pure(Vector.empty)
                    }
                  yield out1 ++ out2
              }
          case f: Follower =>
            F.raiseError(RaftError.NotLeader(f.leaderId))
          case _: Candidate | _: PreCandidate =>
            F.raiseError(RaftError.ElectionInProgress)
        }
      }
      .flatMap(dispatchOutbound)

  def getState: F[NodeState] = stateRef.get

  def getClusterConfig: F[ClusterConfig] = clusterRef.get

  def shutdown: F[Unit] =
    for
      _ <- logger.info(s"Shutting down RAFT node ${config.localId.value}")
      _ <- shutdownRef.set(true)
      _ <- cancelFiber(batcherFiberRef)
      _ <- cancelFiber(electionFiberRef)
      _ <- cancelFiber(heartbeatFiberRef)
      _ <- cancelFiber(checkQuorumFiberRef)
      _ <- cancelFiber(transferFiberRef)
      _ <- cancelFiber(eventLoopFiberRef)
      _ <- cancelPeerMailboxes
      _ <- cleanupPendingSnapshot
      _ <- peerProgressRef.set(Map.empty)
      _ <- leaseUntilRef.set(Duration.Zero)
      _ <- peerLastAckRef.set(Map.empty)
      _ <- peerPendingSendsRef.set(Map.empty)
      _ <- stateRef.update(s => Follower(s.term, None, None, s.commitIndex))
      _ <- drainProposalQueue(RaftError.ProposalDropped("Node shutting down"))
      _ <- failAllPending(RaftError.ProposalDropped("Node shutting down"))
    yield ()

  private def cleanupPendingSnapshot: F[Unit] =
    pendingSnapRef.getAndSet(None).flatMap(_.traverse_(_.cleanup))

  private def recoverSnapshotAndConfig: F[Unit] =
    for
      snapOpt <- snapshotStoreOpt.flatTraverse(_.loadLatest)
      _ <- termRingRef.set(TermRingBuffer.empty())
      _ <- snapOpt.traverse_ { snap =>
        for
          _ <- stateMachine.restore(snap.data)
          _ <- lastAppliedRef.set(snap.lastIncludedIndex)
          _ <- snap.config.traverse_(clusterRef.set)
          _ <- latestSnapMetaRef.set(
            Some(
              SnapshotMetadata(
                snap.lastIncludedIndex,
                snap.lastIncludedTerm,
                snap.config
              )
            )
          )
          _ <- termRingRef.update(
            _.withSnapshot(snap.lastIncludedIndex, snap.lastIncludedTerm)
          )
        yield ()
      }
      storeLast <- logStore.lastIndex
      startScan = snapOpt.map(_.lastIncludedIndex + 1L).getOrElse(1L)
      _ <-
        if storeLast.value >= startScan then
          logStore
            .getRange(LogIndex.unsafeFrom(startScan), storeLast)
            .flatMap { entries =>
              val seedRing = termRingRef.update(_.seedFromEntries(entries))
              val recoverCfg = entries
                .filter(_.logType == LogType.Configuration)
                .lastOption
                .traverse_ { cfgEntry =>
                  ClusterConfig.decodeBytes(cfgEntry.payload) match
                    case Right(cfg) => clusterRef.set(cfg)
                    case Left(_)    => F.unit
                }
              seedRing *> recoverCfg
            }
        else F.unit
    yield ()

  private def effectiveLastLog: F[(Long, Long)] =
    for
      storeLast <- logStore.lastIndex
      snapOpt <- latestSnapMetaRef.get
      ring <- termRingRef.get
      res <- snapOpt match
        case Some(snap) if storeLast.value <= snap.lastIncludedIndex =>
          F.pure((snap.lastIncludedIndex, snap.lastIncludedTerm))
        case _ =>
          if storeLast.value <= 0L then F.pure((0L, 0L))
          else
            ring.termAt(storeLast.value) match
              case Some(cachedTerm) => F.pure((storeLast.value, cachedTerm))
              case None             =>
                logStore
                  .termAt(storeLast)
                  .map(tOpt =>
                    (storeLast.value, tOpt.map(_.value).getOrElse(0L))
                  )
    yield res

  private def termAtWithSnapshot(idx: Long): F[Option[Long]] =
    if idx <= 0L then F.pure(Some(0L))
    else
      termRingRef.get.flatMap { ring =>
        ring.termAt(idx) match
          case some @ Some(_) => F.pure(some)
          case None           =>
            latestSnapMetaRef.get.flatMap {
              case Some(snap) if idx == snap.lastIncludedIndex =>
                F.pure(Some(snap.lastIncludedTerm))
              case _ =>
                logStore.termAt(LogIndex.unsafeFrom(idx)).map(_.map(_.value))
            }
      }

  private def hasCommittedInCurrentTerm(leader: Leader): F[Boolean] =
    effectiveLastLog.flatMap { case (lastIdx, _) =>
      if leader.commitIndex == 0L then F.pure(lastIdx == 0L)
      else termAtWithSnapshot(leader.commitIndex).map(_.contains(leader.term))
    }

  private def hasPendingConfigAfter(commitIndex: Long): F[Boolean] =
    logStore.lastIndex.flatMap { lastIdx =>
      val start = commitIndex + 1L
      if lastIdx.value < start then F.pure(false)
      else
        logStore
          .getRange(LogIndex.unsafeFrom(start), lastIdx)
          .map(_.exists(_.logType == LogType.Configuration))
    }

  private def drainQueue: F[Unit] =
    msgQueue.tryTake.flatMap {
      case Some(_) => drainQueue
      case None    => F.unit
    }

  private def enqueueInternalSignal(msg: RaftMessage): F[Unit] =
    msgQueue.tryOffer(msg).void

  private def runEventLoop: F[Unit] =
    val networkStream = transport.receive.evalMap { case (from, msg) =>
      processEvent(Some(from), msg)
    }
    val timerStream = Stream
      .repeatEval(msgQueue.take)
      .evalMap(msg => processEvent(None, msg))

    networkStream
      .merge(timerStream)
      .interruptWhen(shutdownRef)
      .compile
      .drain

  private def processEvent(
      senderOpt: Option[NodeId],
      msg: RaftMessage
  ): F[Unit] =
    mutex.lock
      .surround(processEventLocked(senderOpt, msg))
      .flatMap(dispatchOutbound)

  private val stickinessWindow: FiniteDuration =
    config.electionTimeoutMin

  private val trackStickiness: Boolean =
    config.leaderStickinessEnabled || config.checkQuorumEnabled || config.leaseReadEnabled

  private def isStickyFollowerOrLeader(
      state: NodeState,
      msg: RaftMessage
  ): F[Boolean] =
    msg match
      case req: RequestVoteRequest if !req.isTransfer =>
        val shouldGuard =
          if req.isPreVote then config.leaderStickinessEnabled
          else config.checkQuorumEnabled || config.leaseReadEnabled
        if !shouldGuard then F.pure(false)
        else
          state match
            case _: Leader =>
              if req.isPreVote || config.checkQuorumEnabled then F.pure(true)
              else
                for
                  now <- Clock[F].monotonic
                  until <- leaseUntilRef.get
                yield now < until
            case f: Follower if f.leaderId.isDefined =>
              for
                now <- Clock[F].monotonic
                until <- stickinessUntilRef.get
              yield now < until
            case _ => F.pure(false)
      case _ => F.pure(false)

  private def updateLeaderStickiness(
      msg: RaftMessage,
      priorState: NodeState,
      newState: NodeState
  ): F[Unit] =
    msg match
      case ElectionTimeout =>
        stickinessUntilRef.set(Duration.Zero)
      case ae: AppendEntriesRequest if ae.term >= priorState.term =>
        if trackStickiness && stickinessWindow > Duration.Zero then
          Clock[F].monotonic.flatMap(now =>
            stickinessUntilRef.set(now + stickinessWindow)
          )
        else F.unit
      case snap: InstallSnapshotRequest if snap.term >= priorState.term =>
        if trackStickiness && stickinessWindow > Duration.Zero then
          Clock[F].monotonic.flatMap(now =>
            stickinessUntilRef.set(now + stickinessWindow)
          )
        else F.unit
      case _ =>
        newState match
          case _: Candidate | _: PreCandidate =>
            stickinessUntilRef.set(Duration.Zero)
          case _ => F.unit

  private def processEventLocked(
      senderOpt: Option[NodeId],
      msg: RaftMessage
  ): F[Vector[OutboundAction]] =
    for
      chunkValid <- accumulateSnapshotChunk(msg)
      state <- stateRef.get
      stickyPreVote <- isStickyFollowerOrLeader(state, msg)
      out <-
        if stickyPreVote then
          msg match
            case req: RequestVoteRequest =>
              F.pure(
                Vector(
                  OutboundAction.SendOne(
                    req.candidateId,
                    RequestVoteResponse(
                      state.term,
                      voteGranted = false,
                      isPreVote = req.isPreVote
                    )
                  )
                )
              )
            case _ => F.pure(Vector.empty)
        else if !chunkValid then
          msg match
            case req: InstallSnapshotRequest =>
              val stepDownEffs =
                if req.term > state.term then
                  val stopEff = state match
                    case _: Leader => handleLeaderStepDown
                    case _         => F.unit
                  stopEff *>
                    stableStore.setHardState(Term.unsafeFrom(req.term), None) *>
                    stateRef.set(
                      Follower(
                        req.term,
                        None,
                        Some(req.leaderId),
                        state.commitIndex
                      )
                    )
                else F.unit
              val replyTerm = math.max(state.term, req.term)
              stepDownEffs *>
                (if req.term >= state.term then resetElectionTimer
                 else F.unit)
                  .as(
                    Vector(
                      OutboundAction.SendOne(
                        req.leaderId,
                        InstallSnapshotResponse(
                          replyTerm,
                          success = false,
                          matchIndex = 0L
                        )
                      )
                    )
                  )
            case _ => F.pure(Vector.empty)
        else
          for
            cluster <- clusterRef.get
            lastCoord <- effectiveLastLog
            lastLogIndex = lastCoord._1
            lastLogTerm = lastCoord._2
            snapOpt <- latestSnapMetaRef.get
            getTermAt <- resolveTermLookup(state, msg, lastLogIndex, snapOpt)
            transition = RaftLogic.onMessage(
              state = state,
              message = msg,
              config = config,
              lastLogIndex = lastLogIndex,
              lastLogTerm = lastLogTerm,
              clusterSize = cluster.voters.size,
              getTermAt = getTermAt,
              sender = senderOpt,
              clusterOpt = Some(cluster)
            )
            _ <- stateRef.set(transition.state)
            _ <- updateLeaderStickiness(msg, state, transition.state)
            _ <- updatePeerProgressAfterTransition(
              msg,
              senderOpt,
              transition.state,
              lastLogIndex,
              cluster
            )
            _ <- maybeRefreshLeaderLease(
              msg,
              senderOpt,
              transition.state,
              cluster
            )
            _ <- maybeRearmCheckQuorum(msg, transition.state)
            effOut <- executeEffectsLocked(transition.effects)
            catchUpOut <- prepareCatchUpEntries(msg, senderOpt)
          yield effOut ++ catchUpOut
    yield out

  private def recordLeaseSend(to: NodeId, term: Long): F[Unit] =
    if !config.leaseReadEnabled then F.unit
    else
      Clock[F].monotonic.flatMap { now =>
        peerPendingSendsRef.update { m =>
          val cur = m.getOrElse(to, Vector.empty).dropWhile(_._1 < term)
          val next =
            if cur.size >= 256 then cur
            else cur :+ (term -> now)
          m.updated(to, next)
        }
      }

  private def popLeaseSendTime(
      peerId: NodeId,
      term: Long
  ): F[Option[FiniteDuration]] =
    peerPendingSendsRef.modify { m =>
      val cur = m.getOrElse(peerId, Vector.empty).dropWhile(_._1 < term)
      cur.headOption match
        case Some((t, sendTime)) if t == term =>
          val tail = cur.tail
          val updated =
            if tail.isEmpty then m - peerId else m.updated(peerId, tail)
          (updated, Some(sendTime))
        case _ =>
          val updated =
            if cur.isEmpty then m - peerId else m.updated(peerId, cur)
          (updated, None)
    }

  private def maybeRefreshLeaderLease(
      msg: RaftMessage,
      senderOpt: Option[NodeId],
      newState: NodeState,
      cluster: ClusterConfig
  ): F[Unit] =
    (msg, senderOpt, newState) match
      case (resp: AppendEntriesResponse, Some(peerId), l: Leader)
          if resp.term == l.term && resp.success && config.leaseReadEnabled =>
        for
          now <- Clock[F].monotonic
          sendTimeOpt <- popLeaseSendTime(peerId, l.term)
          _ <- sendTimeOpt match
            case None           => F.unit
            case Some(sendTime) =>
              peerLastAckRef
                .updateAndGet { acks =>
                  val prev = acks.getOrElse(peerId, Duration.Zero)
                  val effective = if sendTime > prev then sendTime else prev
                  acks.updated(peerId, effective)
                }
                .flatMap { acks =>
                  def quorumAckFor(vs: Set[NodeId]): Option[FiniteDuration] =
                    if vs.isEmpty then None
                    else
                      val times = vs.toVector.flatMap { v =>
                        if v == config.localId then Some(now) else acks.get(v)
                      }
                      val majIdx = vs.size / 2
                      if times.size <= majIdx then None
                      else
                        val desc =
                          times.sorted(Ordering[FiniteDuration].reverse)
                        Some(desc(majIdx))

                  val quorumAckOpt = cluster.oldVoters match
                    case None        => quorumAckFor(cluster.newVoters)
                    case Some(oldVs) =>
                      for
                        a <- quorumAckFor(cluster.newVoters)
                        b <- quorumAckFor(oldVs)
                      yield if a <= b then a else b

                  quorumAckOpt match
                    case None            => F.unit
                    case Some(quorumAck) =>
                      val candidateLease =
                        quorumAck + config.leaderLeaseDuration
                      leaseUntilRef.update(prev =>
                        if candidateLease > prev then candidateLease else prev
                      )
                }
        yield ()
      case _ => F.unit

  private def maybeRearmCheckQuorum(
      msg: RaftMessage,
      newState: NodeState
  ): F[Unit] =
    (msg, newState) match
      case (CheckQuorumTimeout, _: Leader) if config.checkQuorumEnabled =>
        resetCheckQuorumTimer
      case _ => F.unit

  /** Stream incoming `InstallSnapshotRequest` chunks to disk via
    * [[FilePendingSnapshot]], cleaning up any prior temp file on offset `0` or
    * gapped chunks.
    */
  private def accumulateSnapshotChunk(msg: RaftMessage): F[Boolean] =
    msg match
      case req: InstallSnapshotRequest =>
        pendingSnapRef.get.flatMap {
          case priorOpt if req.offset == 0L =>
            for
              _ <- priorOpt.traverse_(_.cleanup)
              fresh <- FilePendingSnapshot.create[F](
                req.lastIncludedIndex,
                req.lastIncludedTerm
              )
              appended <- fresh.appendChunkBytes(req.payload)
              _ <- pendingSnapRef.set(Some(appended))
            yield true

          case Some(cur)
              if cur.lastIncludedIndex == req.lastIncludedIndex &&
                cur.lastIncludedTerm == req.lastIncludedTerm &&
                cur.offset == req.offset =>
            cur
              .appendChunkBytes(req.payload)
              .flatMap(updated => pendingSnapRef.set(Some(updated)).as(true))

          case priorOpt =>
            priorOpt.traverse_(_.cleanup) *>
              pendingSnapRef.set(None).as(false)
        }
      case _ => F.pure(true)

  /** Resolve synchronous `Long => Option[Long]` term lookups using the
    * in-memory `TermRingBuffer` (`O(1)` zero-I/O on cache hit) with targeted
    * `LogStore.termAt` fallback only on deep misses.
    */
  private def resolveTermLookup(
      state: NodeState,
      msg: RaftMessage,
      lastLogIndex: Long,
      snapOpt: Option[SnapshotMetadata]
  ): F[Long => Option[Long]] =
    for
      ring <- termRingRef.get
      storeLast <- logStore.lastIndex
      fallbackMap <-
        if storeLast.value <= 0L then
          msg match
            case snap: InstallSnapshotRequest =>
              lastAppliedRef.get.map { applied =>
                val alreadyCommitted =
                  snap.lastIncludedIndex <= applied ||
                    snapOpt.exists(
                      _.lastIncludedIndex >= snap.lastIncludedIndex
                    )
                if alreadyCommitted then
                  Map(snap.lastIncludedIndex -> snap.lastIncludedTerm)
                else Map.empty[Long, Long]
              }
            case _ => F.pure(Map.empty[Long, Long])
        else
          val minRetained = snapOpt match
            case Some(s) =>
              math.max(
                1L,
                s.lastIncludedIndex - config.compaction.retainedEntries
              )
            case None => 1L
          fetchMissingTermsForEvent(
            ring,
            state,
            msg,
            lastLogIndex,
            storeLast.value,
            minRetained,
            snapOpt
          )
    yield (idx: Long) =>
      if idx == 0L then Some(0L)
      else
        ring
          .termAt(idx)
          .orElse(fallbackMap.get(idx))
          .orElse(
            snapOpt
              .filter(_.lastIncludedIndex == idx)
              .map(_.lastIncludedTerm)
          )

  private def fetchMissingTermsForEvent(
      ring: TermRingBuffer,
      state: NodeState,
      msg: RaftMessage,
      lastLogIndex: Long,
      storeLast: Long,
      minRetained: Long,
      snapOpt: Option[SnapshotMetadata]
  ): F[Map[Long, Long]] =
    def fetchRangeMissing(from: Long, to: Long): F[Map[Long, Long]] =
      val lo = math.max(minRetained, from)
      val hi = math.min(storeLast, to)
      if lo > hi then F.pure(Map.empty)
      else if ring.termAt(lo).isDefined && ring.termAt(hi).isDefined then
        F.pure(Map.empty)
      else
        val count = hi - lo + 1L
        if count <= 16L then
          (lo to hi).toList
            .traverse { idx =>
              ring.termAt(idx) match
                case Some(_) => F.pure(None)
                case None    =>
                  logStore
                    .termAt(LogIndex.unsafeFrom(idx))
                    .map(_.map(t => idx -> t.value))
            }
            .map(_.flatten.toMap)
        else
          logStore
            .getRange(LogIndex.unsafeFrom(lo), LogIndex.unsafeFrom(hi))
            .map(_.iterator.map(e => e.index -> e.term).toMap)

    (state, msg) match
      case (_, ae: AppendEntriesRequest) =>
        if ae.prevLogIndex <= 0L then
          val endCheck =
            math.min(storeLast, math.max(1L, ae.entries.size.toLong))
          fetchRangeMissing(1L, endCheck)
        else if ae.prevLogIndex > lastLogIndex then F.pure(Map.empty)
        else
          termAtWithSnapshot(ae.prevLogIndex).flatMap { prevTermOpt =>
            val anchorEntry =
              prevTermOpt
                .map(t => Map(ae.prevLogIndex -> t))
                .getOrElse(Map.empty)
            if prevTermOpt.contains(ae.prevLogTerm) then
              val end =
                math.min(storeLast, ae.prevLogIndex + ae.entries.size.toLong)
              fetchRangeMissing(ae.prevLogIndex, end).map(anchorEntry ++ _)
            else
              fetchRangeMissing(
                minRetained,
                math.min(storeLast, ae.prevLogIndex)
              ).map(anchorEntry ++ _)
          }

      case (l: Leader, resp: AppendEntriesResponse) if !resp.success =>
        val minFollower =
          if l.nextIndex.isEmpty then minRetained
          else math.max(minRetained, l.nextIndex.values.min - 1L)
        val minConflict =
          resp.conflictIndex
            .map(ci => math.max(minRetained, ci))
            .getOrElse(minFollower)
        val start = math.min(
          storeLast,
          math.min(minConflict, math.max(minRetained, l.commitIndex))
        )
        fetchRangeMissing(start, storeLast)

      case (l: Leader, _) =>
        val minFollower =
          if l.nextIndex.isEmpty then minRetained
          else math.max(minRetained, l.nextIndex.values.min - 1L)
        val minCommit = math.max(minRetained, l.commitIndex)
        val start = math.min(storeLast, math.min(minFollower, minCommit))
        fetchRangeMissing(start, storeLast)

      case (_, snap: InstallSnapshotRequest) =>
        lastAppliedRef.get.flatMap { applied =>
          val alreadyCommitted =
            snap.lastIncludedIndex <= applied ||
              snapOpt.exists(_.lastIncludedIndex >= snap.lastIncludedIndex)
          if alreadyCommitted then
            F.pure(Map(snap.lastIncludedIndex -> snap.lastIncludedTerm))
          else
            val idx = math.min(storeLast, math.max(1L, snap.lastIncludedIndex))
            termAtWithSnapshot(idx).map {
              case Some(t) => Map(idx -> t)
              case None    => Map.empty
            }
        }

      case _ =>
        termAtWithSnapshot(storeLast).map {
          case Some(t) => Map(storeLast -> t)
          case None    => Map.empty
        }

  private def reconcilePeerProgress(
      cfg: ClusterConfig,
      defaultNextIdx: Long
  ): F[Unit] =
    val peers = cfg.allNodes - config.localId
    peerProgressRef.update { current =>
      peers.map { id =>
        val isLrn = cfg.isLearner(id)
        current.get(id) match
          case Some(existing) => id -> existing.copy(isLearner = isLrn)
          case None           =>
            id -> Progress(
              matched = 0L,
              nextIdx = math.max(1L, defaultNextIdx),
              state = ProgressState.Probe,
              probeSent = false,
              isLearner = isLrn,
              inflights = Inflights(effectiveMaxInflight)
            )
      }.toMap
    }

  private def updatePeerProgressAfterTransition(
      msg: RaftMessage,
      senderOpt: Option[NodeId],
      newState: NodeState,
      lastLogIndex: Long,
      cluster: ClusterConfig
  ): F[Unit] =
    (msg, senderOpt, newState) match
      case (resp: AppendEntriesResponse, Some(peerId), l: Leader)
          if resp.term == l.term =>
        if resp.success then
          val leaderMatch = l.matchIndex.getOrElse(peerId, 0L)
          val fallbackNext = l.nextIndex.getOrElse(peerId, leaderMatch + 1L)
          peerProgressRef.update { cur =>
            val base = cur.getOrElse(
              peerId,
              Progress(
                matched = 0L,
                nextIdx = fallbackNext,
                state = ProgressState.Probe,
                isLearner = cluster.isLearner(peerId),
                inflights = Inflights(effectiveMaxInflight)
              )
            )
            val (u0, moved) = base.maybeUpdate(leaderMatch)
            val u1 = u0.state match
              case ProgressState.Probe =>
                if moved || (resp.matchIndex == u0.matched && u0.nextIdx == u0.matched + 1L)
                then u0.becomeReplicate()
                else u0
              case ProgressState.Snapshot(snapIdx) if u0.matched >= snapIdx =>
                u0.becomeProbe()
              case _ => u0
            cur.updated(peerId, u1)
          }
        else
          val backtrackedNext = l.nextIndex.getOrElse(peerId, 1L)
          val curMatched = l.matchIndex.getOrElse(peerId, 0L)
          peerProgressRef.update { cur =>
            val isLrn = cur
              .get(peerId)
              .map(_.isLearner)
              .getOrElse(cluster.isLearner(peerId))
            cur.updated(
              peerId,
              Progress(
                matched = curMatched,
                nextIdx = backtrackedNext,
                state = ProgressState.Probe,
                probeSent = true,
                isLearner = isLrn,
                inflights = Inflights(effectiveMaxInflight)
              )
            )
          } *> metrics.onReplicationFailure(config.localId, peerId)

      case (resp: InstallSnapshotResponse, Some(peerId), l: Leader)
          if resp.term == l.term =>
        if resp.success && resp.matchIndex > 0L then
          val newMatch =
            math.max(l.matchIndex.getOrElse(peerId, 0L), resp.matchIndex)
          val newNext =
            math.max(l.nextIndex.getOrElse(peerId, 1L), newMatch + 1L)
          peerProgressRef.update { cur =>
            cur.updated(
              peerId,
              Progress(
                matched = newMatch,
                nextIdx = newNext,
                state = ProgressState.Probe,
                probeSent = false,
                isLearner = cluster.isLearner(peerId),
                inflights = Inflights(effectiveMaxInflight)
              )
            )
          }
        else if !resp.success then
          peerProgressRef.update { cur =>
            cur.get(peerId) match
              case Some(pr) => cur.updated(peerId, pr.becomeProbe())
              case None     => cur
          }
        else F.unit

      case (HeartbeatTimeout, _, l: Leader) =>
        snapshotInFlightRef.get.flatMap { activeSnaps =>
          peerProgressRef.update { cur =>
            l.nextIndex.foldLeft(cur) { case (acc, (peerId, leaderNext)) =>
              if leaderNext > lastLogIndex then acc
              else
                acc.get(peerId) match
                  case None =>
                    acc.updated(
                      peerId,
                      Progress(
                        matched = l.matchIndex.getOrElse(peerId, 0L),
                        nextIdx = leaderNext,
                        state = ProgressState.Probe,
                        probeSent = false,
                        isLearner = cluster.isLearner(peerId),
                        inflights = Inflights(effectiveMaxInflight)
                      )
                    )
                  case Some(pr) =>
                    val updatedPr = pr.state match
                      case ProgressState.Snapshot(_)
                          if !activeSnaps.contains(peerId) =>
                        pr.copy(
                          state = ProgressState.Probe,
                          nextIdx = leaderNext,
                          probeSent = false,
                          inflights = pr.inflights.reset
                        )
                      case ProgressState.Probe =>
                        pr.copy(nextIdx = leaderNext, probeSent = false)
                      case ProgressState.Replicate
                          if pr.inflights.isFull || pr.nextIdx > lastLogIndex =>
                        pr.copy(
                          state = ProgressState.Probe,
                          nextIdx = leaderNext,
                          probeSent = false,
                          inflights = pr.inflights.reset
                        )
                      case _ => pr
                    acc.updated(peerId, updatedPr)
            }
          }
        }

      case _ => F.unit

  private def prepareCatchUpEntries(
      triggerMsg: RaftMessage,
      senderOpt: Option[NodeId]
  ): F[Vector[OutboundAction]] =
    stateRef.get.flatMap {
      case l: Leader =>
        triggerMsg match
          case HeartbeatTimeout =>
            effectiveLastLog.flatMap { case (lastIdx, _) =>
              if lastIdx > 0L then
                val targetLast = LogIndex.unsafeFrom(lastIdx)
                l.nextIndex.toList
                  .flatTraverse { case (peerId, nextIdx) =>
                    if nextIdx <= lastIdx then
                      preparePeerReplication(l, peerId, targetLast).map(
                        _.toList
                      )
                    else F.pure(Nil)
                  }
                  .map(_.toVector)
              else F.pure(Vector.empty)
            }

          case resp: AppendEntriesResponse if resp.success =>
            senderOpt match
              case Some(peerId) =>
                effectiveLastLog.flatMap { case (lastIdx, _) =>
                  val nextIdx = l.nextIndex.getOrElse(peerId, lastIdx + 1L)
                  if lastIdx > 0L && nextIdx <= lastIdx then
                    preparePeerReplication(
                      l,
                      peerId,
                      LogIndex.unsafeFrom(lastIdx)
                    )
                  else F.pure(Vector.empty)
                }
              case None => F.pure(Vector.empty)

          case resp: InstallSnapshotResponse
              if resp.success && resp.matchIndex > 0L =>
            senderOpt match
              case Some(peerId) =>
                effectiveLastLog.flatMap { case (lastIdx, _) =>
                  val nextIdx = l.nextIndex.getOrElse(peerId, lastIdx + 1L)
                  if lastIdx > 0L && nextIdx <= lastIdx then
                    preparePeerReplication(
                      l,
                      peerId,
                      LogIndex.unsafeFrom(lastIdx)
                    )
                  else F.pure(Vector.empty)
                }
              case None => F.pure(Vector.empty)

          case _ => F.pure(Vector.empty)

      case _ => F.pure(Vector.empty)
    }

  private def prepareReplicateAllPeers(
      leader: Leader,
      lastIdx: LogIndex
  ): F[Vector[OutboundAction]] =
    clusterRef.get.flatMap { cluster =>
      prepareReplicatePeers(leader, cluster.allNodes - config.localId, lastIdx)
    }

  private def prepareReplicatePeers(
      leader: Leader,
      peers: Set[NodeId],
      lastIdx: LogIndex
  ): F[Vector[OutboundAction]] =
    peers.toList
      .flatTraverse { peerId =>
        preparePeerReplication(leader, peerId, lastIdx).map(_.toList)
      }
      .map(_.toVector)

  /** Build pipelined `AppendEntriesRequest` batches inside `mutex` (stripping
    * `Command` payloads for `Witness` peers) and return [[OutboundAction]]s to
    * be dispatched **outside** `mutex`.
    */
  private def preparePeerReplication(
      leader: Leader,
      peerId: NodeId,
      lastIdx: LogIndex,
      forceUnpause: Boolean = false
  ): F[Vector[OutboundAction]] =
    for
      cluster <- clusterRef.get
      progressMap <- peerProgressRef.get
      defaultNext = math.max(
        1L,
        leader.nextIndex.getOrElse(peerId, lastIdx.value)
      )
      basePr = progressMap.getOrElse(
        peerId,
        Progress(
          matched = leader.matchIndex.getOrElse(peerId, 0L),
          nextIdx = defaultNext,
          state = ProgressState.Probe,
          isLearner = cluster.isLearner(peerId),
          inflights = Inflights(effectiveMaxInflight)
        )
      )
      pr =
        if forceUnpause then
          basePr.copy(
            state = ProgressState.Probe,
            probeSent = false,
            nextIdx = math.min(basePr.nextIdx, defaultNext),
            inflights = basePr.inflights.reset
          )
        else basePr
      safeFrom = math.max(1L, pr.nextIdx)
      isWitnessPeer = cluster.isWitness(peerId)
      actions <-
        if pr.isPaused || safeFrom > lastIdx.value then F.pure(Vector.empty)
        else
          for
            snapMetaOpt <- latestSnapMetaRef.get
            firstEntryOpt <- logStore.get(LogIndex.unsafeFrom(safeFrom))
            prevIdx = safeFrom - 1L
            prevTermOpt <- termAtWithSnapshot(prevIdx)
            needsSnapshot =
              (firstEntryOpt.isEmpty || prevTermOpt.isEmpty) &&
                snapMetaOpt.exists(_.lastIncludedIndex >= safeFrom)
            res <-
              if needsSnapshot then
                val snapIdx =
                  snapMetaOpt.map(_.lastIncludedIndex).getOrElse(safeFrom)
                peerProgressRef.update(
                  _.updated(peerId, pr.becomeSnapshot(snapIdx))
                ) *> triggerSnapshotTransfer(peerId, leader.term)
                  .as(Vector.empty)
              else
                buildPipelinedBatches(
                  leader,
                  pr,
                  lastIdx.value,
                  isWitnessPeer,
                  Vector.empty
                ).flatMap { case (updatedPr, requests) =>
                  if requests.isEmpty then F.pure(Vector.empty)
                  else
                    peerProgressRef
                      .update(_.updated(peerId, updatedPr))
                      .as(
                        if requests.size == 1 then
                          Vector(OutboundAction.SendOne(peerId, requests.head))
                        else Vector(OutboundAction.SendBatch(peerId, requests))
                      )
                }
          yield res
    yield actions

  private def buildPipelinedBatches(
      leader: Leader,
      curPr: Progress,
      lastIdx: Long,
      stripWitnessPayloads: Boolean,
      acc: Vector[RaftMessage]
  ): F[(Progress, Vector[RaftMessage])] =
    val batchStart = math.max(1L, curPr.nextIdx)
    if curPr.isPaused || batchStart > lastIdx then F.pure((curPr, acc))
    else
      val batchEnd = math.min(
        lastIdx,
        batchStart + config.maxEntriesPerRequest.toLong - 1L
      )
      val prevIdx = batchStart - 1L
      for
        prevTermOpt <- termAtWithSnapshot(prevIdx)
        entries <- logStore.getRange(
          LogIndex.unsafeFrom(batchStart),
          LogIndex.unsafeFrom(batchEnd)
        )
        res <-
          if entries.isEmpty then F.pure((curPr, acc))
          else
            val wireEntries =
              if stripWitnessPayloads then entries.map(_.stripForWitness)
              else entries
            val req = AppendEntriesRequest(
              term = leader.term,
              leaderId = config.localId,
              prevLogIndex = prevIdx,
              prevLogTerm = prevTermOpt.getOrElse(0L),
              entries = wireEntries,
              leaderCommit = math.min(
                leader.commitIndex,
                prevIdx + entries.size.toLong
              )
            )
            val nextPr = curPr.optimisticUpdate(entries.last.index)
            buildPipelinedBatches(
              leader,
              nextPr,
              lastIdx,
              stripWitnessPayloads,
              acc :+ req
            )
      yield res

  /** Stream `Snapshot` chunks on a supervised background fiber without
    * `compile.to(Array)` (`SnapshotMetadata.encodeStreamingHeader`).
    */
  private def triggerSnapshotTransfer(
      peerId: NodeId,
      leaderTerm: Long
  ): F[Unit] =
    snapshotStoreOpt match
      case None        => F.unit
      case Some(store) =>
        snapshotInFlightRef
          .modify { active =>
            if active.contains(peerId) then (active, false)
            else (active + peerId, true)
          }
          .flatMap {
            case false => F.unit
            case true  =>
              val runTransfer =
                store.loadLatest
                  .flatMap {
                    case None       => F.unit
                    case Some(snap) =>
                      val wireSnap = snap.config match
                        case Some(cfg) =>
                          val hdr =
                            SnapshotMetadata.encodeStreamingHeader(Some(cfg))
                          val hdrChunk =
                            Chunk.byteBuffer(hdr.asReadOnlyByteBuffer)
                          snap.copy(
                            data = Stream.chunk(hdrChunk).covary[F] ++ snap.data
                          )
                        case None => snap
                      snapshotTransfer
                        .sendSnapshot(
                          leaderId = config.localId,
                          target = peerId,
                          snapshot = wireSnap,
                          leaderTerm = leaderTerm
                        )
                        .void
                  }
                  .handleErrorWith { err =>
                    logger.warn(err)(
                      s"Snapshot transfer to ${peerId.value} failed"
                    )
                  }
                  .guarantee(snapshotInFlightRef.update(_ - peerId))

              supervisor.supervise(runTransfer).void
          }

  private def cancelPeerMailboxes: F[Unit] =
    peerMailboxesCell
      .evalModify(existing => F.pure((Map.empty, existing.values.toList)))
      .flatMap(_.traverse_(_._2.cancel))

  private def runPeerSender(to: NodeId, q: Queue[F, PeerSend]): F[Unit] =
    Stream
      .repeatEval(q.take)
      .interruptWhen(shutdownRef)
      .evalMap {
        case PeerSend.Single(m) =>
          sendMessageWithMetrics(to, m).handleErrorWith { err =>
            logger.debug(err)(s"Outbound send to ${to.value} failed")
          }
        case PeerSend.Batch(ms) =>
          transport.sendBatch(to, ms).handleErrorWith { err =>
            logger.debug(err)(s"Outbound sendBatch to ${to.value} failed")
          }
      }
      .compile
      .drain

  private def enqueuePeerSend(to: NodeId, item: PeerSend): F[Unit] =
    if !config.parallelReplicationEnabled then
      item match
        case PeerSend.Single(m) => sendMessageWithMetrics(to, m)
        case PeerSend.Batch(ms) => transport.sendBatch(to, ms)
    else
      peerMailboxesCell
        .evalModify { map =>
          map.get(to) match
            case Some((q, _)) => F.pure((map, q))
            case None         =>
              for
                q <- Queue.bounded[F, PeerSend](config.maxInboundQueueSize)
                worker <- supervisor.supervise(runPeerSender(to, q))
              yield (map.updated(to, (q, worker)), q)
        }
        .flatMap(_.tryOffer(item).void)

  private def dispatchOutbound(actions: Vector[OutboundAction]): F[Unit] =
    if actions.isEmpty then F.unit
    else
      actions.traverse_ {
        case OutboundAction.SendOne(to, msg) =>
          val leaseRec = msg match
            case ae: AppendEntriesRequest => recordLeaseSend(to, ae.term)
            case _                        => F.unit
          val hbMetric = msg match
            case ae: AppendEntriesRequest if ae.entries.isEmpty =>
              metrics.onHeartbeatSent(config.localId, 1)
            case _ => F.unit
          leaseRec *> hbMetric *> enqueuePeerSend(to, PeerSend.Single(msg))

        case OutboundAction.SendBatch(to, msgs) =>
          val leaseRec = msgs.traverse_ {
            case ae: AppendEntriesRequest => recordLeaseSend(to, ae.term)
            case _                        => F.unit
          }
          leaseRec *> enqueuePeerSend(to, PeerSend.Batch(msgs))

        case OutboundAction.SendGroup(byPeer, hbPeers) =>
          val hbMetric =
            if hbPeers > 0 then metrics.onHeartbeatSent(config.localId, hbPeers)
            else F.unit
          hbMetric *> byPeer.traverse_ { case (peer, msgs) =>
            msgs.traverse_ { m =>
              val leaseRec = m match
                case ae: AppendEntriesRequest => recordLeaseSend(peer, ae.term)
                case _                        => F.unit
              leaseRec *> enqueuePeerSend(peer, PeerSend.Single(m))
            }
          }

        case OutboundAction.BroadcastMsg(msg, hbPeers) =>
          val leaseRec = msg match
            case ae: AppendEntriesRequest if config.leaseReadEnabled =>
              clusterRef.get.flatMap { cluster =>
                (cluster.newVoters ++ cluster.oldVoters.getOrElse(
                  Set.empty
                )).toList
                  .filterNot(_ == config.localId)
                  .traverse_(p => recordLeaseSend(p, ae.term))
              }
            case _ => F.unit
          val bcastMetric = msg match
            case req: RequestVoteRequest =>
              metrics.onElectionStarted(config.localId, req.term)
            case _: AppendEntriesRequest if hbPeers > 0 =>
              metrics.onHeartbeatSent(config.localId, hbPeers)
            case _ => F.unit
          val runBcast = transport.broadcast(msg).handleErrorWith { err =>
            logger.debug(err)("Outbound broadcast failed")
          }
          if config.parallelReplicationEnabled then
            leaseRec *> bcastMetric *> supervisor.supervise(runBcast).void
          else leaseRec *> runBcast *> bcastMetric
      }

  private def sendMessageWithMetrics(to: NodeId, msg: RaftMessage): F[Unit] =
    val voteMetric = msg match
      case RequestVoteResponse(term, true, false) =>
        metrics.onVoteGranted(config.localId, to, term)
      case _ => F.unit
    transport.send(to, msg) *> voteMetric

  @tailrec
  private def spanSendMessages(
      rem: List[Eff],
      accRev: List[Eff.SendMessage]
  ): (List[Eff.SendMessage], List[Eff]) =
    rem match
      case (sm: Eff.SendMessage) :: tail =>
        spanSendMessages(tail, sm :: accRev)
      case other =>
        (accRev.reverse, other)

  private def executeEffectsLocked(
      effects: List[Eff]
  ): F[Vector[OutboundAction]] =
    effects match
      case Nil => F.pure(Vector.empty)

      case (s: Eff.SendMessage) :: tail =>
        val (moreSends, rest) = spanSendMessages(tail, Nil)
        val allSends = s :: moreSends
        val heartbeatPeerCount = allSends
          .collect {
            case Eff.SendMessage(to, ae: AppendEntriesRequest)
                if ae.entries.isEmpty =>
              to
          }
          .distinct
          .size
        val byPeer = allSends
          .groupBy(_.to)
          .toList
          .map { case (p, sms) => p -> sms.map(_.message) }
        val groupAct = OutboundAction.SendGroup(byPeer, heartbeatPeerCount)
        executeEffectsLocked(rest).map(groupAct +: _)

      case head :: tail =>
        for
          firstOut <- executeSingleEffectLocked(head)
          restOut <- executeEffectsLocked(tail)
        yield firstOut ++ restOut

  private def executeSingleEffectLocked(
      effect: Eff
  ): F[Vector[OutboundAction]] =
    effect match
      case Eff.SendMessage(to, msg) =>
        F.pure(Vector(OutboundAction.SendOne(to, msg)))

      case Eff.Broadcast(msg) =>
        msg match
          case ae: AppendEntriesRequest if ae.entries.isEmpty =>
            clusterRef.get.map { cl =>
              val peerCount = (cl.allNodes - config.localId).size
              Vector(OutboundAction.BroadcastMsg(msg, peerCount))
            }
          case _ =>
            F.pure(Vector(OutboundAction.BroadcastMsg(msg, 0)))

      case Eff.PersistHardState(term, vf) =>
        stableStore
          .setHardState(Term.unsafeFrom(term), vf)
          .as(Vector.empty)

      case Eff.AppendLogs(entries) =>
        val updateCfg = entries
          .filter(_.logType == LogType.Configuration)
          .lastOption
          .traverse_ { cfgEntry =>
            ClusterConfig.decodeBytes(cfgEntry.payload) match
              case Right(cfg) => clusterRef.set(cfg)
              case Left(_)    => F.unit
          }
        logStore.append(entries) *>
          termRingRef.update(_.append(entries)) *>
          updateCfg *>
          metrics
            .onEntriesAppended(config.localId, entries.size)
            .as(Vector.empty)

      case Eff.TruncateLog(fromIndex) =>
        for
          _ <- logStore.truncateFrom(LogIndex.unsafeFrom(fromIndex))
          _ <- termRingRef.update(_.truncateFrom(fromIndex))
          _ <- failPendingProposalsFrom(
            fromIndex,
            RaftError.ProposalDropped(s"Log truncated from index $fromIndex")
          )
          _ <- rebuildClusterConfigAfterTruncation
        yield Vector.empty

      case Eff.ResetElectionTimer =>
        resetElectionTimer.as(Vector.empty)

      case Eff.ResetHeartbeatTimer =>
        resetHeartbeatTimer.as(Vector.empty)

      case Eff.RequestSnapshot(lastApplied, _) =>
        for
          _ <- logger.info(s"Snapshot requested at index=$lastApplied")
          termOpt <- termAtWithSnapshot(lastApplied)
          term = termOpt.getOrElse(0L)
          cluster <- clusterRef.get
          data <- stateMachine.snapshot
          snapshot = Snapshot[F](lastApplied, term, data, Some(cluster))
          discardCut = math.max(
            1L,
            lastApplied - config.compaction.retainedEntries
          )
          _ <- snapshotStoreOpt.traverse_ { store =>
            store.save(snapshot) *>
              latestSnapMetaRef.set(
                Some(SnapshotMetadata(lastApplied, term, Some(cluster)))
              ) *>
              logStore.discardBefore(LogIndex.unsafeFrom(discardCut)) *>
              termRingRef.update(
                _.withSnapshot(lastApplied, term).discardBefore(discardCut)
              )
          }
          _ <- lastAppliedRef.set(lastApplied)
          _ <- metrics.onSnapshotTaken(config.localId, lastApplied)
        yield Vector.empty

      case Eff.InstallSnapshot(metadata) =>
        for
          pendingOpt <- pendingSnapRef.getAndSet(None)
          currentApplied <- lastAppliedRef.get
          curSnapOpt <- latestSnapMetaRef.get
          isStale =
            metadata.lastIncludedIndex <= currentApplied ||
              curSnapOpt.exists(
                _.lastIncludedIndex >= metadata.lastIncludedIndex
              )
          _ <-
            if isStale then
              pendingOpt.traverse_(_.cleanup) *>
                logger.debug(
                  s"Ignoring stale snapshot at index=${metadata.lastIncludedIndex} (lastApplied=$currentApplied)"
                )
            else installStreamingSnapshot(metadata, pendingOpt)
        yield Vector.empty

      case Eff.BecomeLeader =>
        for
          _ <- cancelFiber(electionFiberRef)
          st <- stateRef.get
          term = st.term
          _ <- logger.info(
            s"Node ${config.localId.value} became leader at term $term"
          )
          _ <- metrics.onBecomeLeader(config.localId, term)
          _ <-
            if config.checkQuorumEnabled then resetCheckQuorumTimer
            else F.unit
          out <- initializeLeaderAndNoOpLocked(term)
        yield out

      case Eff.StopLeader =>
        handleLeaderStepDown.as(Vector.empty)

      case Eff.TransferLeadership(target) =>
        logger
          .info(s"Transferring leadership to ${target.value}")
          .as(Vector.empty)

      case Eff.CommitEntries(upToIndex) =>
        for
          out <- applyCommittedEntriesLocked(upToIndex)
          _ <- metrics.onEntriesCommitted(config.localId, upToIndex.value)
        yield out

      case Eff.ReadIndexReady(id, index) =>
        pendingReadsRef
          .modify(m => (m - id, m.get(id)))
          .flatMap {
            case None    => F.unit
            case Some(d) =>
              lastAppliedRef.get.flatMap { applied =>
                if applied >= index then d.complete(Right(index)).void
                else readApplyWaitersRef.update(_ :+ (index -> d))
              }
          }
          .as(Vector.empty)

      case Eff.ReadIndexRejected(id, _, leaderHint) =>
        pendingReadsRef
          .modify(m => (m - id, m.get(id)))
          .flatMap {
            case None    => F.unit
            case Some(d) =>
              stateRef.get.flatMap {
                case l: Leader =>
                  d.complete(Left(RaftError.LeaderNotReadyInTerm(l.term))).void
                case _: Candidate | _: PreCandidate =>
                  d.complete(Left(RaftError.ElectionInProgress)).void
                case _ =>
                  d.complete(Left(RaftError.NotLeader(leaderHint))).void
              }
          }
          .as(Vector.empty)

      case Eff.ConfirmLeadership(_, _) =>
        F.pure(Vector.empty)

      case Eff.TimeoutNow(target) =>
        (cancelFiber(transferFiberRef) *>
          logger.info(
            s"TimeoutNow -> ${target.value}: triggering immediate election"
          )).as(Vector.empty)

      case Eff.ApplyClusterConfig(newConfig, _, _) =>
        (clusterRef.set(newConfig) *>
          effectiveLastLog.flatMap { case (lastIdx, _) =>
            reconcilePeerProgress(newConfig, lastIdx + 1L)
          }).as(Vector.empty)

  private def installStreamingSnapshot(
      metadata: SnapshotMetadata,
      pendingOpt: Option[FilePendingSnapshot[F]]
  ): F[Unit] =
    val runInstall = pendingOpt match
      case Some(filePending) =>
        filePending.decodeStreamingPayload.flatMap {
          case (decodedCfg, restoreStream) =>
            val effectiveCfg = decodedCfg.orElse(metadata.config)
            for
              _ <- logger.info(
                s"Installing disk-streamed snapshot at index=${metadata.lastIncludedIndex} term=${metadata.lastIncludedTerm}"
              )
              _ <- stateMachine.restore(restoreStream)
              _ <- snapshotStoreOpt.traverse_ { store =>
                filePending.decodeStreamingPayload.flatMap {
                  case (_, persistStream) =>
                    store.save(
                      Snapshot[F](
                        metadata.lastIncludedIndex,
                        metadata.lastIncludedTerm,
                        persistStream,
                        effectiveCfg
                      )
                    )
                }
              }
              _ <- finalizeSnapshotInstall(metadata, effectiveCfg)
            yield ()
        }

      case None =>
        val (decodedCfg, stateBytes) =
          SnapshotMetadata.decodePayloadBytes(metadata.payload)
        val effectiveCfg = decodedCfg.orElse(metadata.config)
        def mkStream: Stream[F, Byte] =
          if stateBytes.nonEmpty then
            Stream
              .chunk(Chunk.byteBuffer(stateBytes.asReadOnlyByteBuffer))
              .covary[F]
          else Stream.empty.covary[F]
        for
          _ <- logger.info(
            s"Installing snapshot at index=${metadata.lastIncludedIndex} term=${metadata.lastIncludedTerm}"
          )
          _ <- stateMachine.restore(mkStream)
          _ <- snapshotStoreOpt.traverse_ { store =>
            store.save(
              Snapshot[F](
                metadata.lastIncludedIndex,
                metadata.lastIncludedTerm,
                mkStream,
                effectiveCfg
              )
            )
          }
          _ <- finalizeSnapshotInstall(metadata, effectiveCfg)
        yield ()

    runInstall.guarantee(pendingOpt.traverse_(_.cleanup))

  private def finalizeSnapshotInstall(
      metadata: SnapshotMetadata,
      effectiveCfg: Option[ClusterConfig]
  ): F[Unit] =
    for
      _ <- compactionMgrOpt.traverse_(
        _.recordInstalledSnapshot(metadata.lastIncludedIndex)
      )
      _ <- effectiveCfg.traverse_(clusterRef.set)
      _ <- latestSnapMetaRef.set(
        Some(
          SnapshotMetadata(
            metadata.lastIncludedIndex,
            metadata.lastIncludedTerm,
            effectiveCfg
          )
        )
      )
      _ <- logStore.clear
      _ <- termRingRef.update(
        _.withSnapshot(
          metadata.lastIncludedIndex,
          metadata.lastIncludedTerm
        ).clearLogOnly
      )
      _ <- lastAppliedRef.set(metadata.lastIncludedIndex)
      _ <- completeReadApplyWaitersUpTo(metadata.lastIncludedIndex)
      _ <- metrics.onSnapshotTaken(config.localId, metadata.lastIncludedIndex)
    yield ()

  private def handleLeaderStepDown: F[Unit] =
    for
      st <- stateRef.get
      _ <- cancelFiber(heartbeatFiberRef)
      _ <- cancelFiber(checkQuorumFiberRef)
      _ <- cancelFiber(transferFiberRef)
      _ <- peerProgressRef.set(Map.empty)
      _ <- leaseUntilRef.set(Duration.Zero)
      _ <- peerLastAckRef.set(Map.empty)
      _ <- peerPendingSendsRef.set(Map.empty)
      err = st match
        case f: Follower => RaftError.NotLeader(f.leaderId)
        case _           => RaftError.NotLeader(None)
      _ <- drainProposalQueue(err)
      _ <- failPendingLeaderRequests(err)
      _ <- metrics.onStepDown(config.localId, st.term)
    yield ()

  private def failPendingProposalsFrom(
      fromIndex: Long,
      err: RaftError
  ): F[Unit] =
    for
      droppedProps <- pendingProposalsRef.modify { m =>
        val (drop, keep) = m.partition(_._1 >= fromIndex)
        (keep, drop.values.map(_._2).toList)
      }
      droppedJoints <- jointWaiterRef.modify { m =>
        val (drop, keep) = m.partition(_._1 >= fromIndex)
        (keep, drop.values.toList)
      }
      _ <- droppedProps.traverse_(_.fail(err))
      _ <- droppedJoints.traverse_(d => d.complete(Left(err)).void)
    yield ()

  private def failPendingLeaderRequests(err: RaftError): F[Unit] =
    for
      props <- pendingProposalsRef.getAndSet(Map.empty)
      joints <- jointWaiterRef.getAndSet(Map.empty)
      reads <- pendingReadsRef.getAndSet(Map.empty)
      _ <- props.values.toList.traverse_ { case (_, w) => w.fail(err) }
      _ <- joints.values.toList.traverse_(d => d.complete(Left(err)).void)
      _ <- reads.values.toList.traverse_(d => d.complete(Left(err)).void)
    yield ()

  private def failAllPending(err: RaftError): F[Unit] =
    for
      _ <- failPendingLeaderRequests(err)
      applyWaiters <- readApplyWaitersRef.getAndSet(Vector.empty)
      _ <- applyWaiters.traverse_ { case (_, d) => d.complete(Left(err)).void }
    yield ()

  private def rebuildClusterConfigAfterTruncation: F[Unit] =
    for
      snapOpt <- latestSnapMetaRef.get
      storeLast <- logStore.lastIndex
      startScan = snapOpt.map(_.lastIncludedIndex + 1L).getOrElse(1L)
      logCfgOpt <-
        if storeLast.value >= startScan then
          logStore
            .getRange(LogIndex.unsafeFrom(startScan), storeLast)
            .map { entries =>
              entries
                .filter(_.logType == LogType.Configuration)
                .lastOption
                .flatMap(e => ClusterConfig.decodeBytes(e.payload).toOption)
            }
        else F.pure(None)
      resolved = logCfgOpt
        .orElse(snapOpt.flatMap(_.config))
        .getOrElse(bootstrapCluster)
      _ <- clusterRef.set(resolved)
    yield ()

  private def resetElectionTimer: F[Unit] =
    for
      _ <- cancelFiber(electionFiberRef)
      timeout <- timerService.randomElectionTimeout(config.electionTimeoutMin)
      fiber <- timerService.scheduleElection(
        timeout,
        enqueueInternalSignal(ElectionTimeout)
      )
      _ <- electionFiberRef.set(Some(fiber))
    yield ()

  private def resetHeartbeatTimer: F[Unit] =
    for
      _ <- cancelFiber(heartbeatFiberRef)
      fiber <- timerService.scheduleHeartbeat(
        config.heartbeatInterval,
        enqueueInternalSignal(HeartbeatTimeout)
      )
      _ <- heartbeatFiberRef.set(Some(fiber))
    yield ()

  private def resetCheckQuorumTimer: F[Unit] =
    for
      _ <- cancelFiber(checkQuorumFiberRef)
      fiber <- timerService.scheduleElection(
        config.electionTimeoutMax,
        enqueueInternalSignal(CheckQuorumTimeout)
      )
      _ <- checkQuorumFiberRef.set(Some(fiber))
    yield ()

  private def scheduleTransferTimeout(
      expectedTerm: Long,
      target: NodeId
  ): F[Unit] =
    val abortAction = mutex.lock.surround {
      stateRef.update {
        case l: Leader
            if l.term == expectedTerm && l.transferTarget.contains(target) =>
          l.copy(transferTarget = None)
        case other => other
      }
    }
    for
      _ <- cancelFiber(transferFiberRef)
      fiber <- timerService.scheduleElection(
        config.electionTimeoutMax,
        abortAction
      )
      _ <- transferFiberRef.set(Some(fiber))
    yield ()

  private def cancelFiber(
      ref: Ref[F, Option[Fiber[F, Throwable, Unit]]]
  ): F[Unit] =
    ref.getAndSet(None).flatMap {
      case Some(fiber) => fiber.cancel
      case None        => F.unit
    }

  private def initializeLeaderAndNoOpLocked(
      term: Long
  ): F[Vector[OutboundAction]] =
    for
      lastCoord <- effectiveLastLog
      lastLogIndex = lastCoord._1
      snapOpt <- latestSnapMetaRef.get
      snapIdx = snapOpt.map(_.lastIncludedIndex).getOrElse(0L)
      cluster <- clusterRef.get
      followers = cluster.allNodes - config.localId
      noOpIndex = lastLogIndex + 1L
      noOpEntry = Log.noOp(noOpIndex, term)
      _ <- logStore.append(Seq(noOpEntry))
      _ <- termRingRef.update(_.append(Seq(noOpEntry)))
      initProgress = followers.map { id =>
        id -> Progress(
          matched = 0L,
          nextIdx = noOpIndex,
          state = ProgressState.Probe,
          probeSent = false,
          isLearner = cluster.isLearner(id),
          inflights = Inflights(effectiveMaxInflight)
        )
      }.toMap
      _ <- peerProgressRef.set(initProgress)
      _ <- peerLastAckRef.set(Map.empty)
      _ <- peerPendingSendsRef.set(Map.empty)
      _ <- stateRef.update {
        case l: Leader =>
          l.initializeIndices(followers, lastLogIndex)
            .withCommitIndex(math.max(l.commitIndex, snapIdx))
        case s => s
      }
      _ <-
        if cluster.hasQuorum(Set(config.localId)) && config.leaseReadEnabled
        then
          Clock[F].monotonic
            .flatMap(now => leaseUntilRef.set(now + config.leaderLeaseDuration))
        else F.unit
      out <- stateRef.get.flatMap {
        case l: Leader =>
          if cluster.hasQuorum(Set(config.localId)) then
            val committed = LogIndex.unsafeFrom(noOpIndex)
            stateRef.set(l.withCommitIndex(noOpIndex)) *>
              applyCommittedEntriesLocked(committed)
          else prepareReplicateAllPeers(l, LogIndex.unsafeFrom(noOpIndex))
        case _ => F.pure(Vector.empty)
      }
    yield out

  private def applyEntry(
      entry: Log
  ): F[(Option[R], Option[ClusterConfig])] =
    entry.logType match
      case LogType.Command =>
        stateMachine.apply(entry).map(r => (Some(r), None))
      case LogType.Configuration =>
        ClusterConfig.decodeBytes(entry.payload) match
          case Right(cfg) => clusterRef.set(cfg).as((None, Some(cfg)))
          case Left(_)    => F.pure((None, None))
      case LogType.NoOp =>
        F.pure((None, None))

  /** Fetch `[lastApplied + 1 .. upToIndex]` in a **single batched `getRange`**
    * per commit wave, capture typed `R` outputs from `StateMachine`, and return
    * any follow-up Joint Consensus outbound actions.
    */
  private def applyCommittedEntriesLocked(
      upToIndex: LogIndex
  ): F[Vector[OutboundAction]] =
    lastAppliedRef.get.flatMap { initialApplied =>
      if upToIndex.value <= initialApplied then F.pure(Vector.empty)
      else
        for
          batch <- logStore.getRange(
            LogIndex.unsafeFrom(initialApplied + 1L),
            upToIndex
          )
          outActions <- batch.flatTraverse { entry =>
            lastAppliedRef.get.flatMap { curApplied =>
              if entry.index <= curApplied then
                F.pure(Vector.empty[OutboundAction])
              else
                for
                  appliedPair <- applyEntry(entry)
                  (cmdOutOpt, committedCfgOpt) = appliedPair
                  _ <- lastAppliedRef.set(entry.index)
                  _ <- metrics.onEntriesApplied(config.localId, entry.index)
                  _ <- completeProposalAt(
                    entry.index,
                    entry.term,
                    cmdOutOpt
                  )
                  _ <- completeReadApplyWaitersUpTo(entry.index)
                  cfgOut <- committedCfgOpt match
                    case Some(cfg) =>
                      onCommittedConfigurationLocked(entry.index, cfg)
                    case None => F.pure(Vector.empty[OutboundAction])
                yield cfgOut
            }
          }
          _ <- maybeRunCompaction(upToIndex.value)
        yield outActions
    }

  private def completeProposalAt(
      idx: Long,
      actualTerm: Long,
      cmdResultOpt: Option[R]
  ): F[Unit] =
    pendingProposalsRef
      .modify(m => (m - idx, m.get(idx)))
      .flatMap {
        case None                         => F.unit
        case Some((expectedTerm, waiter)) =>
          if expectedTerm != actualTerm then
            waiter.fail(
              RaftError.ProposalDropped(
                s"Entry at index $idx overwritten by term $actualTerm"
              )
            )
          else
            val logId = LogId(actualTerm, idx)
            waiter match
              case CommitWaiter.ForLogId(d) =>
                d.complete(Right(logId)).void
              case CommitWaiter.ForApplied(d) =>
                cmdResultOpt match
                  case Some(value) =>
                    d.complete(Right(Applied(logId, value))).void
                  case None =>
                    d.complete(
                      Left(
                        RaftError.ProposalDropped(
                          s"Non-command entry committed at index $idx"
                        )
                      )
                    ).void
      }

  private def completeReadApplyWaitersUpTo(appliedIdx: Long): F[Unit] =
    readApplyWaitersRef
      .modify { waiters =>
        val (ready, remaining) = waiters.partition(_._1 <= appliedIdx)
        (remaining, ready)
      }
      .flatMap { ready =>
        ready.traverse_ { case (idx, d) => d.complete(Right(idx)).void }
      }

  private def onCommittedConfigurationLocked(
      committedIdx: Long,
      cfg: ClusterConfig
  ): F[Vector[OutboundAction]] =
    stateRef.get.flatMap {
      case l: Leader if cfg.isInJointConsensus =>
        hasPendingConfigAfter(committedIdx).flatMap { alreadyPending =>
          if alreadyPending then F.pure(Vector.empty)
          else
            val finalCfg = cfg.completeJointConsensus
            for
              lastCoord <- effectiveLastLog
              lastIdx = lastCoord._1
              cNewIdx = lastIdx + 1L
              cNewEntry = Log.configuration(cNewIdx, l.term, finalCfg)
              _ <- logStore.append(Seq(cNewEntry))
              _ <- termRingRef.update(_.append(Seq(cNewEntry)))
              _ <- clusterRef.set(finalCfg)
              _ <- reconcilePeerProgress(finalCfg, cNewIdx)
              _ <-
                if config.checkQuorumEnabled then resetCheckQuorumTimer
                else F.unit
              transferredWaiterOpt <- jointWaiterRef
                .modify(m => (m - committedIdx, m.get(committedIdx)))
              _ <- transferredWaiterOpt.traverse_ { w =>
                pendingProposalsRef.update(
                  _.updated(cNewIdx, (l.term, CommitWaiter.ForLogId(w)))
                )
              }
              _ <- metrics.onEntriesAppended(config.localId, 1)
              out <-
                if finalCfg.hasQuorum(Set(config.localId)) then
                  val committed = LogIndex.unsafeFrom(cNewIdx)
                  for
                    _ <- stateRef.update {
                      case cur: Leader => cur.withCommitIndex(cNewIdx)
                      case other       => other
                    }
                    applyOut <- applyCommittedEntriesLocked(committed)
                    _ <- metrics.onEntriesCommitted(config.localId, cNewIdx)
                  yield applyOut
                else
                  prepareReplicatePeers(
                    l,
                    (cfg.allNodes ++ finalCfg.allNodes) - config.localId,
                    LogIndex.unsafeFrom(cNewIdx)
                  )
            yield out
        }

      case l: Leader if !cfg.isVoter(config.localId) =>
        (logger.info(
          s"Leader ${config.localId.value} removed from voters at index $committedIdx; stepping down"
        ) *>
          stateRef.set(
            Follower(l.term, None, None, math.max(l.commitIndex, committedIdx))
          ) *>
          handleLeaderStepDown).as(Vector.empty)

      case _ =>
        F.pure(Vector.empty)
    }

  private def maybeRunCompaction(lastApplied: Long): F[Unit] =
    compactionMgrOpt match
      case None      => F.unit
      case Some(mgr) =>
        for
          snapOpt <- latestSnapMetaRef.get
          lastCoord <- effectiveLastLog
          lastIdx = lastCoord._1
          snapIdx = snapOpt.map(_.lastIncludedIndex).getOrElse(0L)
          approxLogSize = math.max(0L, lastIdx - snapIdx)
          _ <- mgr.maybeCompact(lastApplied, approxLogSize)
        yield ()
