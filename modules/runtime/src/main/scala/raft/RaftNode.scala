package raft

import scala.concurrent.duration.*

import cats.effect.*
import cats.effect.implicits.*
import cats.effect.std.{Mutex, Queue}
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

import raft.compaction.{CompactionManager, SnapshotTransfer}
import raft.effect.Effect as Eff
import raft.logic.RaftLogic
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

/** Event-driven Cats Effect adapter around the pure [[raft.logic.RaftLogic]]
  * consensus engine.
  *
  * Host systems that manage their own threads, event loops, or storage directly
  * can embed zero-dependency `raft-core` (`RawNode` / `RaftLogic`) without this
  * runtime; systems built on Cats Effect + FS2 can use `RaftNode` as a turnkey
  * `Resource`-managed consensus actor supporting linearizable writes, quorum
  * `ReadIndex`, zero-RTT `leaseRead`, automatic Joint Consensus (`C_old,new ->
  * C_new`), leadership transfer, `CheckQuorum`, and chunked snapshot streaming.
  */
trait RaftNode[F[_]]:
  /** Start (or restart) the RAFT node event loop. */
  def start: F[Unit]

  /** Propose a client command payload and return its assigned `(term, index)`
    * coordinate once appended to the local leader log.
    */
  def propose(data: Array[Byte]): F[LogId]

  /** Propose a client command payload and suspend until it is committed by a
    * cluster quorum and applied to the local [[raft.spi.StateMachine]].
    *
    * Fails with [[raft.state.RaftError]] if this node is not the leader, steps
    * down before commitment, truncates the entry, or shuts down.
    */
  def proposeAndCommit(data: Array[Byte]): F[LogId]

  /** Submit a client command payload to be replicated across the cluster. */
  def submit(data: Array[Byte]): F[Unit]

  /** Confirm leadership with a voting quorum (§6.4 `ReadIndex`) and suspend
    * until `lastApplied >= confirmedCommitIndex`, returning the linearizable
    * read index.
    */
  def readIndex: F[Long]

  /** Attempt a zero-RTT linearizable read index check (§6.4 Lease Read) while
    * `config.leaseReadEnabled` is enabled and this leader's monotonic lease is
    * valid.
    *
    * Returns `Some(readIndex)` once `lastApplied >= commitIndex` if the lease
    * holds, `None` if lease reads are disabled, the leader has not yet
    * committed an entry in its current term, or the lease has expired (allowing
    * callers to fall back to [[readIndex]]), and raises
    * [[raft.state.RaftError]] when invoked on a non-leader.
    */
  def leaseRead: F[Option[Long]]

  /** Propose a cluster membership transition (§6).
    *
    * Enforces Ongaro §4.1's current-term commit barrier and the single-pending
    * configuration rule. When `newConfig.voters` differs from the active voter
    * set (and `newConfig` is not already a joint configuration), `RaftNode`
    * automatically enters Joint Consensus (`C_old,new`), commits `C_old,new`,
    * appends and commits `C_new`, steps down if `C_new` removes this leader,
    * and resolves with `C_new`'s committed [[raft.state.LogId]].
    */
  def proposeConfChange(newConfig: ClusterConfig): F[LogId]

  /** Initiate graceful leadership transfer to `target` (§3.10). */
  def transferLeadership(target: NodeId): F[Unit]

  /** Inspect the current consensus role, term, and leader state. */
  def getState: F[NodeState]

  /** Inspect the currently active [[raft.state.ClusterConfig]]. */
  def getClusterConfig: F[ClusterConfig]

  /** Gracefully stop timers and the event loop fiber. */
  def shutdown: F[Unit]

object RaftNode:
  /** Create a managed [[RaftNode]] resource.
    *
    * Releasing the resource automatically invokes [[RaftNode.shutdown]],
    * cancelling all background timer and event-loop fibers.
    */
  def apply[F[_]: Async](
      config: RaftConfig,
      cluster: ClusterConfig,
      transport: RaftTransport[F],
      logStore: LogStore[F],
      stableStore: StableStore[F],
      stateMachine: StateMachine[F, ?],
      timerService: TimerService[F],
      metrics: Option[RaftMetrics[F]] = None,
      snapshotStoreOpt: Option[SnapshotStore[F]] = None,
      loggerOpt: Option[Logger[F]] = None
  ): Resource[F, RaftNode[F]] =
    val acquire: F[RaftNode[F]] =
      val resolvedMetrics = metrics.getOrElse(RaftMetrics.noop[F])
      val resolvedLogger = loggerOpt.getOrElse(NoOpLogger[F])
      for
        mutex <- Mutex[F]
        stateRef <- Ref.of[F, NodeState](Follower(0L, None, None))
        clusterRef <- Ref.of[F, ClusterConfig](cluster)
        shutdownRef <- SignallingRef.of[F, Boolean](false)
        msgQueue <- Queue.unbounded[F, RaftMessage]
        lastAppliedRef <- Ref.of[F, Long](0L)
        latestSnapMetaRef <- Ref.of[F, Option[SnapshotMetadata]](None)
        pendingSnapRef <- Ref.of[F, Option[PendingSnapshot]](None)
        pendingProposalsRef <- Ref.of[
          F,
          Map[Long, (Long, Deferred[F, Either[RaftError, LogId]])]
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
        snapshotInFlightRef <- Ref.of[F, Set[NodeId]](Set.empty)
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
            onCompacted = Some(meta => latestSnapMetaRef.set(Some(meta)))
          )
        }
        snapshotTransfer = new SnapshotTransfer[F](
          chunkSize = math.max(1, config.compaction.snapshotChunkSize),
          transport = transport,
          logger = resolvedLogger
        )
        eventLoopFiberRef <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](None)
        electionFiberRef <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](None)
        heartbeatFiberRef <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](None)
        checkQuorumFiberRef <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](
          None
        )
        transferFiberRef <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](None)
      yield new RaftNodeImpl[F](
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
        mutex = mutex,
        stateRef = stateRef,
        shutdownRef = shutdownRef,
        msgQueue = msgQueue,
        lastAppliedRef = lastAppliedRef,
        latestSnapMetaRef = latestSnapMetaRef,
        pendingSnapRef = pendingSnapRef,
        pendingProposalsRef = pendingProposalsRef,
        jointWaiterRef = jointWaiterRef,
        readSeqRef = readSeqRef,
        pendingReadsRef = pendingReadsRef,
        readApplyWaitersRef = readApplyWaitersRef,
        leaseUntilRef = leaseUntilRef,
        snapshotInFlightRef = snapshotInFlightRef,
        eventLoopFiberRef = eventLoopFiberRef,
        electionFiberRef = electionFiberRef,
        heartbeatFiberRef = heartbeatFiberRef,
        checkQuorumFiberRef = checkQuorumFiberRef,
        transferFiberRef = transferFiberRef,
        logger = resolvedLogger
      )
    Resource.make(acquire)(_.shutdown)

private final class RaftNodeImpl[F[_]: Async](
    config: RaftConfig,
    bootstrapCluster: ClusterConfig,
    clusterRef: Ref[F, ClusterConfig],
    transport: RaftTransport[F],
    logStore: LogStore[F],
    stableStore: StableStore[F],
    snapshotStoreOpt: Option[SnapshotStore[F]],
    compactionMgrOpt: Option[CompactionManager[F]],
    snapshotTransfer: SnapshotTransfer[F],
    stateMachine: StateMachine[F, ?],
    timerService: TimerService[F],
    metrics: RaftMetrics[F],
    mutex: Mutex[F],
    stateRef: Ref[F, NodeState],
    shutdownRef: SignallingRef[F, Boolean],
    msgQueue: Queue[F, RaftMessage],
    lastAppliedRef: Ref[F, Long],
    latestSnapMetaRef: Ref[F, Option[SnapshotMetadata]],
    pendingSnapRef: Ref[F, Option[PendingSnapshot]],
    pendingProposalsRef: Ref[
      F,
      Map[Long, (Long, Deferred[F, Either[RaftError, LogId]])]
    ],
    jointWaiterRef: Ref[F, Map[Long, Deferred[F, Either[RaftError, LogId]]]],
    readSeqRef: Ref[F, Long],
    pendingReadsRef: Ref[F, Map[String, Deferred[F, Either[RaftError, Long]]]],
    readApplyWaitersRef: Ref[
      F,
      Vector[(Long, Deferred[F, Either[RaftError, Long]])]
    ],
    leaseUntilRef: Ref[F, FiniteDuration],
    snapshotInFlightRef: Ref[F, Set[NodeId]],
    eventLoopFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]],
    electionFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]],
    heartbeatFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]],
    checkQuorumFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]],
    transferFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]],
    logger: Logger[F]
) extends RaftNode[F]:

  def start: F[Unit] =
    for
      _ <- cancelFiber(eventLoopFiberRef)
      _ <- cancelFiber(electionFiberRef)
      _ <- cancelFiber(heartbeatFiberRef)
      _ <- cancelFiber(checkQuorumFiberRef)
      _ <- cancelFiber(transferFiberRef)
      _ <- drainQueue
      _ <- shutdownRef.set(false)
      _ <- leaseUntilRef.set(Duration.Zero)
      _ <- logger.info(s"Starting RAFT node ${config.localId.value}")
      hs <- stableStore.hardState
      _ <- recoverSnapshotAndConfig
      _ <- stateRef.set(Follower(hs.term, hs.votedFor, None))
      _ <- resetElectionTimer
      fiber <- runEventLoop.start
      _ <- eventLoopFiberRef.set(Some(fiber))
    yield ()

  def propose(data: Array[Byte]): F[LogId] =
    mutex.lock.surround {
      stateRef.get.flatMap {
        case l: Leader if l.transferTarget.isDefined =>
          Async[F].raiseError(
            RaftError.LeadershipTransferInProgress(l.transferTarget.get)
          )
        case l: Leader =>
          for
            lastCoord <- effectiveLastLog
            lastIdx = lastCoord._1
            newIndex = lastIdx + 1L
            entry = Log.command(newIndex, l.term, data)
            _ <- logStore.append(Seq(entry))
            _ <- metrics.onEntriesAppended(config.localId, 1)
            _ <- logger.debug(s"Leader appended command at index $newIndex")
            cluster <- clusterRef.get
            _ <-
              if cluster.hasQuorum(Set(config.localId)) then
                val committed = LogIndex.unsafeFrom(newIndex)
                stateRef.set(l.withCommitIndex(newIndex)) *>
                  applyCommittedEntries(committed) *>
                  metrics.onEntriesCommitted(config.localId, newIndex)
              else replicateAllPeers(l, LogIndex.unsafeFrom(newIndex))
          yield LogId(l.term, newIndex)
        case f: Follower =>
          Async[F].raiseError(RaftError.NotLeader(f.leaderId))
        case _: Candidate | _: PreCandidate =>
          Async[F].raiseError(RaftError.ElectionInProgress)
      }
    }

  def proposeAndCommit(data: Array[Byte]): F[LogId] =
    Async[F].uncancelable { poll =>
      mutex.lock
        .surround {
          stateRef.get.flatMap {
            case l: Leader if l.transferTarget.isDefined =>
              Async[F].raiseError(
                RaftError.LeadershipTransferInProgress(l.transferTarget.get)
              )
            case l: Leader =>
              for
                waiter <- Deferred[F, Either[RaftError, LogId]]
                lastCoord <- effectiveLastLog
                lastIdx = lastCoord._1
                newIndex = lastIdx + 1L
                entry = Log.command(newIndex, l.term, data)
                _ <- logStore.append(Seq(entry))
                _ <- pendingProposalsRef.update(
                  _.updated(newIndex, (l.term, waiter))
                )
                _ <- metrics.onEntriesAppended(config.localId, 1)
                cluster <- clusterRef.get
                _ <-
                  if cluster.hasQuorum(Set(config.localId)) then
                    val committed = LogIndex.unsafeFrom(newIndex)
                    stateRef.set(l.withCommitIndex(newIndex)) *>
                      applyCommittedEntries(committed) *>
                      metrics.onEntriesCommitted(config.localId, newIndex)
                  else replicateAllPeers(l, LogIndex.unsafeFrom(newIndex))
              yield (newIndex, waiter)
            case f: Follower =>
              Async[F].raiseError(RaftError.NotLeader(f.leaderId))
            case _: Candidate | _: PreCandidate =>
              Async[F].raiseError(RaftError.ElectionInProgress)
          }
        }
        .flatMap { case (idx, waiter) =>
          poll(waiter.get)
            .onCancel(pendingProposalsRef.update(_ - idx))
            .flatMap(Async[F].fromEither)
        }
    }

  def submit(data: Array[Byte]): F[Unit] =
    propose(data).void

  def readIndex: F[Long] =
    Async[F].uncancelable { poll =>
      mutex.lock
        .surround {
          for
            seq <- readSeqRef.updateAndGet(_ + 1L)
            reqId = s"${config.localId.value}-read-$seq"
            waiter <- Deferred[F, Either[RaftError, Long]]
            _ <- pendingReadsRef.update(_.updated(reqId, waiter))
            _ <- processEventUnlocked(None, ReadIndexRequest(reqId))
          yield (reqId, waiter)
        }
        .flatMap { case (reqId, waiter) =>
          poll(waiter.get)
            .onCancel(pendingReadsRef.update(_ - reqId))
            .flatMap(Async[F].fromEither)
        }
    }

  def leaseRead: F[Option[Long]] =
    Async[F].uncancelable { poll =>
      mutex.lock
        .surround {
          stateRef.get.flatMap {
            case l: Leader =>
              if !config.leaseReadEnabled then Async[F].pure(None)
              else
                for
                  readyInTerm <- hasCommittedInCurrentTerm(l)
                  cluster <- clusterRef.get
                  now <- Clock[F].monotonic
                  leaseUntil <- leaseUntilRef.get
                  singleVoter = cluster.hasQuorum(Set(config.localId))
                  leaseValid = readyInTerm && (singleVoter || now < leaseUntil)
                  action <-
                    if !leaseValid then Async[F].pure(None)
                    else
                      lastAppliedRef.get.flatMap { applied =>
                        if applied >= l.commitIndex then
                          Async[F].pure(
                            Some(Left(l.commitIndex)): Option[
                              Either[Long, Deferred[F, Either[RaftError, Long]]]
                            ]
                          )
                        else
                          Deferred[F, Either[RaftError, Long]].flatMap { d =>
                            readApplyWaitersRef
                              .update(_ :+ (l.commitIndex -> d))
                              .as(Some(Right(d)))
                          }
                      }
                yield action
            case f: Follower =>
              Async[F].raiseError(RaftError.NotLeader(f.leaderId))
            case _: Candidate | _: PreCandidate =>
              Async[F].raiseError(RaftError.ElectionInProgress)
          }
        }
        .flatMap {
          case None               => Async[F].pure(None)
          case Some(Left(idx))    => Async[F].pure(Some(idx))
          case Some(Right(await)) =>
            poll(await.get).flatMap {
              case Right(idx) => Async[F].pure(Some(idx))
              case Left(err)  => Async[F].raiseError(err)
            }
        }
    }

  def proposeConfChange(newConfig: ClusterConfig): F[LogId] =
    Async[F].uncancelable { poll =>
      mutex.lock
        .surround {
          stateRef.get.flatMap {
            case l: Leader if l.transferTarget.isDefined =>
              Async[F].raiseError(
                RaftError.LeadershipTransferInProgress(l.transferTarget.get)
              )
            case l: Leader =>
              for
                readyInTerm <- hasCommittedInCurrentTerm(l)
                _ <-
                  if !readyInTerm then
                    Async[F].raiseError(RaftError.LeaderNotReadyInTerm(l.term))
                  else Async[F].unit
                currentCluster <- clusterRef.get
                hasUncommittedCfg <- hasPendingConfigAfter(l.commitIndex)
                _ <-
                  if currentCluster.isInJointConsensus || hasUncommittedCfg then
                    Async[F].raiseError(
                      RaftError.ConfigurationChangeInProgress(currentCluster)
                    )
                  else Async[F].unit
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
                entry = Log(
                  index = newIndex,
                  term = l.term,
                  logType = LogType.Configuration,
                  data = ClusterConfig.encode(effectiveCfg)
                )
                _ <- logStore.append(Seq(entry))
                _ <- clusterRef.set(effectiveCfg)
                addedPeers =
                  (effectiveCfg.allNodes - config.localId) -- l.nextIndex.keySet
                updatedLeader = addedPeers.foldLeft(l) { (acc, peer) =>
                  acc.withNextIndex(peer, newIndex).withMatchIndex(peer, 0L)
                }
                _ <- stateRef.set(updatedLeader)
                _ <-
                  if config.checkQuorumEnabled && addedPeers.nonEmpty then
                    resetCheckQuorumTimer
                  else Async[F].unit
                _ <-
                  if entersJoint then
                    jointWaiterRef.update(_.updated(newIndex, waiter))
                  else
                    pendingProposalsRef.update(
                      _.updated(newIndex, (l.term, waiter))
                    )
                _ <- metrics.onEntriesAppended(config.localId, 1)
                _ <-
                  if effectiveCfg.hasQuorum(Set(config.localId)) then
                    val committed = LogIndex.unsafeFrom(newIndex)
                    stateRef.update {
                      case cur: Leader => cur.withCommitIndex(newIndex)
                      case other       => other
                    } *>
                      applyCommittedEntries(committed) *>
                      metrics.onEntriesCommitted(config.localId, newIndex)
                  else
                    replicatePeers(
                      updatedLeader,
                      (currentCluster.allNodes ++ effectiveCfg.allNodes) - config.localId,
                      LogIndex.unsafeFrom(newIndex)
                    )
              yield (newIndex, entersJoint, waiter)
            case f: Follower =>
              Async[F].raiseError(RaftError.NotLeader(f.leaderId))
            case _: Candidate | _: PreCandidate =>
              Async[F].raiseError(RaftError.ElectionInProgress)
          }
        }
        .flatMap { case (idx, entersJoint, waiter) =>
          poll(waiter.get)
            .onCancel(
              if entersJoint then jointWaiterRef.update(_ - idx)
              else pendingProposalsRef.update(_ - idx)
            )
            .flatMap(Async[F].fromEither)
        }
    }

  def transferLeadership(target: NodeId): F[Unit] =
    mutex.lock.surround {
      stateRef.get.flatMap {
        case l: Leader =>
          if target == config.localId then Async[F].unit
          else
            clusterRef.get.flatMap { cluster =>
              if !cluster.isVoter(target) then
                Async[F].raiseError(
                  RaftError.ProposalDropped(
                    s"Transfer target ${target.value} is not a voting member"
                  )
                )
              else
                for
                  _ <- processEventUnlocked(
                    None,
                    TransferLeadershipRequest(l.term, target)
                  )
                  _ <- stateRef.get.flatMap {
                    case cur: Leader if cur.transferTarget.contains(target) =>
                      effectiveLastLog.flatMap { case (lastIdx, _) =>
                        val nextIdx =
                          cur.nextIndex.getOrElse(target, lastIdx + 1L)
                        val sendMissing =
                          if lastIdx > 0L && nextIdx <= lastIdx then
                            replicatePeerRange(
                              cur,
                              target,
                              nextIdx,
                              LogIndex.unsafeFrom(lastIdx)
                            )
                          else Async[F].unit
                        sendMissing *> scheduleTransferTimeout(cur.term, target)
                      }
                    case _ => Async[F].unit
                  }
                yield ()
            }
        case f: Follower =>
          Async[F].raiseError(RaftError.NotLeader(f.leaderId))
        case _: Candidate | _: PreCandidate =>
          Async[F].raiseError(RaftError.ElectionInProgress)
      }
    }

  def getState: F[NodeState] = stateRef.get

  def getClusterConfig: F[ClusterConfig] = clusterRef.get

  def shutdown: F[Unit] =
    for
      _ <- logger.info(s"Shutting down RAFT node ${config.localId.value}")
      _ <- shutdownRef.set(true)
      _ <- cancelFiber(electionFiberRef)
      _ <- cancelFiber(heartbeatFiberRef)
      _ <- cancelFiber(checkQuorumFiberRef)
      _ <- cancelFiber(transferFiberRef)
      _ <- cancelFiber(eventLoopFiberRef)
      _ <- failAllPending(RaftError.ProposalDropped("Node shutting down"))
    yield ()

  private def recoverSnapshotAndConfig: F[Unit] =
    for
      snapOpt <- snapshotStoreOpt.flatTraverse(_.loadLatest)
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
        yield ()
      }
      storeLast <- logStore.lastIndex
      startScan = snapOpt.map(_.lastIncludedIndex + 1L).getOrElse(1L)
      _ <-
        if storeLast.value >= startScan then
          logStore
            .getRange(LogIndex.unsafeFrom(startScan), storeLast)
            .flatMap { entries =>
              entries
                .filter(_.logType == LogType.Configuration)
                .lastOption
                .traverse_ { cfgEntry =>
                  ClusterConfig.decode(cfgEntry.data) match
                    case Right(cfg) => clusterRef.set(cfg)
                    case Left(_)    => Async[F].unit
                }
            }
        else Async[F].unit
    yield ()

  private def effectiveLastLog: F[(Long, Long)] =
    for
      storeLast <- logStore.lastIndex
      snapOpt <- latestSnapMetaRef.get
      res <- snapOpt match
        case Some(snap) if storeLast.value <= snap.lastIncludedIndex =>
          Async[F].pure((snap.lastIncludedIndex, snap.lastIncludedTerm))
        case _ =>
          if storeLast.value <= 0L then Async[F].pure((0L, 0L))
          else
            logStore
              .termAt(storeLast)
              .map(tOpt => (storeLast.value, tOpt.map(_.value).getOrElse(0L)))
    yield res

  private def termAtWithSnapshot(idx: Long): F[Option[Long]] =
    if idx <= 0L then Async[F].pure(Some(0L))
    else
      latestSnapMetaRef.get.flatMap {
        case Some(snap) if idx == snap.lastIncludedIndex =>
          Async[F].pure(Some(snap.lastIncludedTerm))
        case Some(snap) if idx < snap.lastIncludedIndex =>
          logStore.termAt(LogIndex.unsafeFrom(idx)).map(_.map(_.value))
        case _ =>
          logStore.termAt(LogIndex.unsafeFrom(idx)).map(_.map(_.value))
      }

  private def hasCommittedInCurrentTerm(leader: Leader): F[Boolean] =
    effectiveLastLog.flatMap { case (lastIdx, _) =>
      if leader.commitIndex == 0L then Async[F].pure(lastIdx == 0L)
      else termAtWithSnapshot(leader.commitIndex).map(_.contains(leader.term))
    }

  private def hasPendingConfigAfter(commitIndex: Long): F[Boolean] =
    logStore.lastIndex.flatMap { lastIdx =>
      val start = commitIndex + 1L
      if lastIdx.value < start then Async[F].pure(false)
      else
        logStore
          .getRange(LogIndex.unsafeFrom(start), lastIdx)
          .map(_.exists(_.logType == LogType.Configuration))
    }

  private def drainQueue: F[Unit] =
    msgQueue.tryTake.flatMap {
      case Some(_) => drainQueue
      case None    => Async[F].unit
    }

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
    mutex.lock.surround(processEventUnlocked(senderOpt, msg))

  private def processEventUnlocked(
      senderOpt: Option[NodeId],
      msg: RaftMessage
  ): F[Unit] =
    for
      chunkValid <- accumulateSnapshotChunk(msg)
      state <- stateRef.get
      _ <-
        if !chunkValid then
          msg match
            case req: InstallSnapshotRequest =>
              val stepDownEffs =
                if req.term > state.term then
                  val stopEff = state match
                    case _: Leader => handleLeaderStepDown
                    case _         => Async[F].unit
                  stopEff *>
                    stableStore.setHardState(Term.unsafeFrom(req.term), None) *>
                    stateRef.set(Follower(req.term, None, Some(req.leaderId)))
                else Async[F].unit
              val replyTerm = math.max(state.term, req.term)
              stepDownEffs *>
                (if req.term >= state.term then resetElectionTimer
                 else Async[F].unit) *>
                transport.send(
                  req.leaderId,
                  InstallSnapshotResponse(
                    replyTerm,
                    success = false,
                    matchIndex = 0L
                  )
                )
            case _ => Async[F].unit
        else
          for
            cluster <- clusterRef.get
            lastCoord <- effectiveLastLog
            lastLogIndex = lastCoord._1
            lastLogTerm = lastCoord._2
            snapOpt <- latestSnapMetaRef.get
            termCache <- prefetchTerms(state, msg, lastLogIndex, snapOpt)
            getTermAt = (idx: Long) => termCache.get(idx)
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
            _ <- maybeRefreshLeaderLease(msg, transition.state, cluster)
            _ <- maybeRearmCheckQuorum(msg, transition.state)
            _ <- executeEffects(transition.effects)
            _ <- maybeSendCatchUpEntries(msg, senderOpt)
          yield ()
    yield ()

  private def maybeRefreshLeaderLease(
      msg: RaftMessage,
      newState: NodeState,
      cluster: ClusterConfig
  ): F[Unit] =
    (msg, newState) match
      case (resp: AppendEntriesResponse, l: Leader)
          if resp.success && config.leaseReadEnabled =>
        val active = l.recentlyActive + config.localId
        if cluster.hasQuorum(active) then
          Clock[F].monotonic.flatMap(now =>
            leaseUntilRef.set(now + config.leaderLeaseDuration)
          )
        else Async[F].unit
      case _ => Async[F].unit

  private def maybeRearmCheckQuorum(
      msg: RaftMessage,
      newState: NodeState
  ): F[Unit] =
    (msg, newState) match
      case (CheckQuorumTimeout, _: Leader) if config.checkQuorumEnabled =>
        resetCheckQuorumTimer
      case _ => Async[F].unit

  /** Buffer incoming `InstallSnapshotRequest` chunks prior to final
    * installation, returning `false` if a non-initial chunk arrives with a gap
    * (e.g., when earlier chunks were dropped across a network partition).
    */
  private def accumulateSnapshotChunk(msg: RaftMessage): F[Boolean] =
    msg match
      case req: InstallSnapshotRequest =>
        pendingSnapRef.modify {
          case _ if req.offset == 0L =>
            (
              Some(
                PendingSnapshot(
                  lastIncludedIndex = req.lastIncludedIndex,
                  lastIncludedTerm = req.lastIncludedTerm,
                  offset = 0L,
                  chunks = Vector.empty
                ).appendChunk(req.data)
              ),
              true
            )
          case Some(pending)
              if pending.lastIncludedIndex == req.lastIncludedIndex &&
                pending.lastIncludedTerm == req.lastIncludedTerm &&
                pending.offset == req.offset =>
            (Some(pending.appendChunk(req.data)), true)
          case _ =>
            (None, false)
        }
      case _ => Async[F].pure(true)

  /** Pre-fetch log terms needed for pure transition validation (`AppendEntries`
    * consistency, full-term fast conflict backtracking, snapshot boundaries,
    * and leader commit calculation).
    */
  private def prefetchTerms(
      state: NodeState,
      msg: RaftMessage,
      lastLogIndex: Long,
      snapOpt: Option[SnapshotMetadata]
  ): F[Map[Long, Long]] =
    val snapSeed: Map[Long, Long] =
      snapOpt
        .filter(_.lastIncludedIndex > 0L)
        .map(s => s.lastIncludedIndex -> s.lastIncludedTerm)
        .toMap

    logStore.lastIndex.flatMap { storeLast =>
      if storeLast.value <= 0L then
        msg match
          case snap: InstallSnapshotRequest =>
            lastAppliedRef.get.map { applied =>
              val alreadyCommitted =
                snap.lastIncludedIndex <= applied ||
                  snapOpt.exists(_.lastIncludedIndex >= snap.lastIncludedIndex)
              if alreadyCommitted then
                snapSeed + (snap.lastIncludedIndex -> snap.lastIncludedTerm)
              else snapSeed
            }
          case _ => Async[F].pure(snapSeed)
      else
        val minRetained = snapOpt match
          case Some(s) =>
            math.max(
              1L,
              s.lastIncludedIndex - config.compaction.retainedEntries
            )
          case None => 1L

        (state, msg) match
          case (_, ae: AppendEntriesRequest) =>
            if ae.prevLogIndex <= 0L then
              val endCheck =
                math.min(storeLast.value, math.max(1L, ae.entries.size.toLong))
              logStore
                .getRange(
                  LogIndex.unsafeFrom(1L),
                  LogIndex.unsafeFrom(endCheck)
                )
                .map(es =>
                  snapSeed ++ es.iterator.map(e => e.index -> e.term).toMap
                )
            else if ae.prevLogIndex > lastLogIndex then Async[F].pure(snapSeed)
            else
              termAtWithSnapshot(ae.prevLogIndex).flatMap { prevTermOpt =>
                if prevTermOpt.contains(ae.prevLogTerm) then
                  val start = math.max(minRetained, ae.prevLogIndex)
                  val end = math.min(
                    storeLast.value,
                    ae.prevLogIndex + ae.entries.size.toLong
                  )
                  val base = snapSeed + (ae.prevLogIndex -> ae.prevLogTerm)
                  if start <= end then
                    logStore
                      .getRange(
                        LogIndex.unsafeFrom(start),
                        LogIndex.unsafeFrom(end)
                      )
                      .map(es =>
                        base ++ es.iterator.map(e => e.index -> e.term).toMap
                      )
                  else Async[F].pure(base)
                else
                  val end = math.min(storeLast.value, ae.prevLogIndex)
                  val start = math.min(minRetained, end)
                  logStore
                    .getRange(
                      LogIndex.unsafeFrom(start),
                      LogIndex.unsafeFrom(end)
                    )
                    .map(es =>
                      snapSeed ++ es.iterator.map(e => e.index -> e.term).toMap
                    )
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
              storeLast.value,
              math.min(minConflict, math.max(minRetained, l.commitIndex))
            )
            logStore
              .getRange(LogIndex.unsafeFrom(start), storeLast)
              .map(es =>
                snapSeed ++ es.iterator.map(e => e.index -> e.term).toMap
              )

          case (l: Leader, _) =>
            val minFollower =
              if l.nextIndex.isEmpty then minRetained
              else math.max(minRetained, l.nextIndex.values.min - 1L)
            val minCommit = math.max(minRetained, l.commitIndex)
            val start =
              math.min(storeLast.value, math.min(minFollower, minCommit))
            logStore
              .getRange(LogIndex.unsafeFrom(start), storeLast)
              .map(es =>
                snapSeed ++ es.iterator.map(e => e.index -> e.term).toMap
              )

          case (_, snap: InstallSnapshotRequest) =>
            lastAppliedRef.get.flatMap { applied =>
              val alreadyCommitted =
                snap.lastIncludedIndex <= applied ||
                  snapOpt.exists(_.lastIncludedIndex >= snap.lastIncludedIndex)
              if alreadyCommitted then
                Async[F].pure(
                  snapSeed + (snap.lastIncludedIndex -> snap.lastIncludedTerm)
                )
              else
                val idx =
                  math.min(
                    storeLast.value,
                    math.max(1L, snap.lastIncludedIndex)
                  )
                logStore
                  .termAt(LogIndex.unsafeFrom(idx))
                  .map {
                    case Some(t) => snapSeed + (idx -> t.value)
                    case None    => snapSeed
                  }
            }

          case _ =>
            logStore
              .termAt(storeLast)
              .map {
                case Some(t) => snapSeed + (storeLast.value -> t.value)
                case None    => snapSeed
              }
    }

  /** Replicate missing log entries `[nextIdx .. lastIdx]` (or trigger chunked
    * snapshot transfer) to followers on heartbeat ticks, `AppendEntries` ACKs,
    * or `InstallSnapshot` ACKs.
    */
  private def maybeSendCatchUpEntries(
      trigger: RaftMessage,
      senderOpt: Option[NodeId]
  ): F[Unit] =
    stateRef.get.flatMap {
      case l: Leader =>
        trigger match
          case HeartbeatTimeout =>
            effectiveLastLog.flatMap { case (lastIdx, _) =>
              if lastIdx > 0L then
                val targetLast = LogIndex.unsafeFrom(lastIdx)
                l.nextIndex.toList.parTraverse_ { case (peerId, nextIdx) =>
                  if nextIdx <= lastIdx then
                    replicatePeerRange(l, peerId, nextIdx, targetLast)
                  else Async[F].unit
                }
              else Async[F].unit
            }
          case resp: AppendEntriesResponse if resp.success =>
            senderOpt match
              case Some(peerId) =>
                effectiveLastLog.flatMap { case (lastIdx, _) =>
                  val nextIdx = l.nextIndex.getOrElse(peerId, lastIdx + 1L)
                  if lastIdx > 0L && nextIdx <= lastIdx then
                    replicatePeerRange(
                      l,
                      peerId,
                      nextIdx,
                      LogIndex.unsafeFrom(lastIdx)
                    )
                  else Async[F].unit
                }
              case None => Async[F].unit
          case resp: InstallSnapshotResponse
              if resp.success && resp.matchIndex > 0L =>
            senderOpt match
              case Some(peerId) =>
                effectiveLastLog.flatMap { case (lastIdx, _) =>
                  val nextIdx = l.nextIndex.getOrElse(peerId, lastIdx + 1L)
                  if lastIdx > 0L && nextIdx <= lastIdx then
                    replicatePeerRange(
                      l,
                      peerId,
                      nextIdx,
                      LogIndex.unsafeFrom(lastIdx)
                    )
                  else Async[F].unit
                }
              case None => Async[F].unit
          case _ => Async[F].unit
      case _ => Async[F].unit
    }

  private def replicateAllPeers(leader: Leader, lastIdx: LogIndex): F[Unit] =
    clusterRef.get.flatMap { cluster =>
      replicatePeers(leader, cluster.allNodes - config.localId, lastIdx)
    }

  private def replicatePeers(
      leader: Leader,
      peers: Set[NodeId],
      lastIdx: LogIndex
  ): F[Unit] =
    peers.toList.parTraverse_ { peerId =>
      val nextIdx = leader.nextIndex.getOrElse(peerId, lastIdx.value)
      replicatePeerRange(leader, peerId, math.max(1L, nextIdx), lastIdx)
    }

  private def replicatePeerRange(
      leader: Leader,
      peerId: NodeId,
      fromIndex: Long,
      lastIdx: LogIndex
  ): F[Unit] =
    val safeFrom = math.max(1L, fromIndex)
    if safeFrom > lastIdx.value then Async[F].unit
    else
      for
        snapMetaOpt <- latestSnapMetaRef.get
        firstEntryOpt <- logStore.get(LogIndex.unsafeFrom(safeFrom))
        prevIdx = safeFrom - 1L
        prevTermOpt <- termAtWithSnapshot(prevIdx)
        needsSnapshot =
          (firstEntryOpt.isEmpty || prevTermOpt.isEmpty) &&
            snapMetaOpt.exists(_.lastIncludedIndex >= safeFrom)
        _ <-
          if needsSnapshot then triggerSnapshotTransfer(peerId, leader.term)
          else
            val batchEnd = LogIndex.unsafeFrom(
              math.min(
                lastIdx.value,
                safeFrom + config.maxEntriesPerRequest.toLong - 1L
              )
            )
            val prevTerm = prevTermOpt.getOrElse(0L)
            for
              entries <- logStore.getRange(
                LogIndex.unsafeFrom(safeFrom),
                batchEnd
              )
              _ <-
                if entries.nonEmpty then
                  val req = AppendEntriesRequest(
                    term = leader.term,
                    leaderId = config.localId,
                    prevLogIndex = prevIdx,
                    prevLogTerm = prevTerm,
                    entries = entries,
                    leaderCommit = math.min(
                      leader.commitIndex,
                      prevIdx + entries.size.toLong
                    )
                  )
                  transport.send(peerId, req)
                else Async[F].unit
            yield ()
      yield ()

  private def triggerSnapshotTransfer(
      peerId: NodeId,
      leaderTerm: Long
  ): F[Unit] =
    snapshotStoreOpt match
      case None        => Async[F].unit
      case Some(store) =>
        snapshotInFlightRef
          .modify { active =>
            if active.contains(peerId) then (active, false)
            else (active + peerId, true)
          }
          .flatMap {
            case false => Async[F].unit
            case true  =>
              val runTransfer =
                store.loadLatest
                  .flatMap {
                    case None       => Async[F].unit
                    case Some(snap) =>
                      for
                        wireSnap <-
                          snap.config match
                            case Some(cfg) =>
                              snap.data.compile.to(Array).map { rawBytes =>
                                val encoded =
                                  SnapshotMetadata.encodePayload(
                                    Some(cfg),
                                    rawBytes
                                  )
                                snap.copy(
                                  data = Stream
                                    .chunk(Chunk.array(encoded))
                                    .covary[F]
                                )
                              }
                            case None => Async[F].pure(snap)
                        _ <- snapshotTransfer.sendSnapshot(
                          leaderId = config.localId,
                          target = peerId,
                          snapshot = wireSnap,
                          leaderTerm = leaderTerm
                        )
                      yield ()
                  }
                  .handleErrorWith { err =>
                    logger.warn(err)(
                      s"Snapshot transfer to ${peerId.value} failed"
                    )
                  }
                  .guarantee(snapshotInFlightRef.update(_ - peerId))

              runTransfer.start.void
          }

  private def executeEffects(effects: List[Eff]): F[Unit] =
    effects match
      case Nil                          => Async[F].unit
      case (s: Eff.SendMessage) :: tail =>
        val (moreSends, rest) = tail.span(_.isInstanceOf[Eff.SendMessage])
        val allSends =
          s :: moreSends.collect { case sm: Eff.SendMessage => sm }
        val byPeer = allSends.groupBy(_.to).toList
        byPeer.parTraverse_ { case (peer, msgs) =>
          msgs.traverse_(m => transport.send(peer, m.message))
        } *> executeEffects(rest)
      case head :: tail =>
        executeSingleEffect(head) *> executeEffects(tail)

  private def executeSingleEffect(effect: Eff): F[Unit] =
    effect match
      case Eff.SendMessage(to, msg) =>
        transport.send(to, msg)

      case Eff.Broadcast(msg) =>
        transport.broadcast(msg)

      case Eff.PersistHardState(term, vf) =>
        stableStore.setHardState(Term.unsafeFrom(term), vf)

      case Eff.AppendLogs(entries) =>
        val updateCfg = entries
          .filter(_.logType == LogType.Configuration)
          .lastOption
          .traverse_ { cfgEntry =>
            ClusterConfig.decode(cfgEntry.data) match
              case Right(cfg) => clusterRef.set(cfg)
              case Left(_)    => Async[F].unit
          }
        logStore.append(entries) *>
          updateCfg *>
          metrics.onEntriesAppended(config.localId, entries.size)

      case Eff.TruncateLog(fromIndex) =>
        for
          _ <- logStore.truncateFrom(LogIndex.unsafeFrom(fromIndex))
          _ <- failPendingProposalsFrom(
            fromIndex,
            RaftError.ProposalDropped(s"Log truncated from index $fromIndex")
          )
          _ <- rebuildClusterConfigAfterTruncation
        yield ()

      case Eff.ResetElectionTimer =>
        resetElectionTimer

      case Eff.ResetHeartbeatTimer =>
        resetHeartbeatTimer

      case Eff.RequestSnapshot(lastApplied, _) =>
        for
          _ <- logger.info(s"Snapshot requested at index=$lastApplied")
          termOpt <- termAtWithSnapshot(lastApplied)
          term = termOpt.getOrElse(0L)
          cluster <- clusterRef.get
          data <- stateMachine.snapshot
          snapshot = Snapshot[F](lastApplied, term, data, Some(cluster))
          _ <- snapshotStoreOpt.traverse_ { store =>
            store.save(snapshot) *>
              latestSnapMetaRef.set(
                Some(SnapshotMetadata(lastApplied, term, Some(cluster)))
              ) *>
              logStore.discardBefore(
                LogIndex.unsafeFrom(
                  math.max(1L, lastApplied - config.compaction.retainedEntries)
                )
              )
          }
          _ <- lastAppliedRef.set(lastApplied)
          _ <- metrics.onSnapshotTaken(config.localId, lastApplied)
        yield ()

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
              logger.debug(
                s"Ignoring stale snapshot at index=${metadata.lastIncludedIndex} (lastApplied=$currentApplied)"
              )
            else
              val rawBytes =
                pendingOpt.map(_.toBytes).getOrElse(Array.emptyByteArray)
              val (decodedCfg, stateBytes) =
                SnapshotMetadata.decodePayload(rawBytes)
              val effectiveCfg = decodedCfg.orElse(metadata.config)
              val restoreStream =
                if stateBytes.nonEmpty then
                  Stream.chunk(Chunk.array(stateBytes)).covary[F]
                else Stream.empty.covary[F]
              for
                _ <- logger.info(
                  s"Installing snapshot at index=${metadata.lastIncludedIndex} term=${metadata.lastIncludedTerm}"
                )
                _ <- stateMachine.restore(restoreStream)
                _ <- snapshotStoreOpt.traverse_ { store =>
                  val persistStream =
                    if stateBytes.nonEmpty then
                      Stream.chunk(Chunk.array(stateBytes)).covary[F]
                    else Stream.empty.covary[F]
                  store.save(
                    Snapshot[F](
                      metadata.lastIncludedIndex,
                      metadata.lastIncludedTerm,
                      persistStream,
                      effectiveCfg
                    )
                  )
                }
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
                _ <- lastAppliedRef.set(metadata.lastIncludedIndex)
                _ <- completeReadApplyWaitersUpTo(metadata.lastIncludedIndex)
                _ <- metrics.onSnapshotTaken(
                  config.localId,
                  metadata.lastIncludedIndex
                )
              yield ()
        yield ()

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
            else Async[F].unit
          _ <- initializeLeaderAndNoOp(term)
        yield ()

      case Eff.StopLeader =>
        handleLeaderStepDown

      case Eff.TransferLeadership(target) =>
        logger.info(s"Transferring leadership to ${target.value}")

      case Eff.CommitEntries(upToIndex) =>
        applyCommittedEntries(upToIndex) *>
          metrics.onEntriesCommitted(config.localId, upToIndex.value)

      case Eff.ReadIndexReady(id, index) =>
        pendingReadsRef
          .modify(m => (m - id, m.get(id)))
          .flatMap {
            case None    => Async[F].unit
            case Some(d) =>
              lastAppliedRef.get.flatMap { applied =>
                if applied >= index then d.complete(Right(index)).void
                else readApplyWaitersRef.update(_ :+ (index -> d))
              }
          }

      case Eff.ReadIndexRejected(id, _, leaderHint) =>
        pendingReadsRef
          .modify(m => (m - id, m.get(id)))
          .flatMap {
            case None    => Async[F].unit
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

      case Eff.ConfirmLeadership(_, _) =>
        Async[F].unit

      case Eff.TimeoutNow(target) =>
        cancelFiber(transferFiberRef) *>
          logger.info(
            s"TimeoutNow -> ${target.value}: triggering immediate election"
          )

      case Eff.ApplyClusterConfig(newConfig, _, _) =>
        clusterRef.set(newConfig)

  private def handleLeaderStepDown: F[Unit] =
    for
      st <- stateRef.get
      _ <- cancelFiber(heartbeatFiberRef)
      _ <- cancelFiber(checkQuorumFiberRef)
      _ <- cancelFiber(transferFiberRef)
      _ <- leaseUntilRef.set(Duration.Zero)
      err = st match
        case f: Follower => RaftError.NotLeader(f.leaderId)
        case _           => RaftError.NotLeader(None)
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
      _ <- droppedProps.traverse_(d => d.complete(Left(err)).void)
      _ <- droppedJoints.traverse_(d => d.complete(Left(err)).void)
    yield ()

  private def failPendingLeaderRequests(err: RaftError): F[Unit] =
    for
      props <- pendingProposalsRef.getAndSet(Map.empty)
      joints <- jointWaiterRef.getAndSet(Map.empty)
      reads <- pendingReadsRef.getAndSet(Map.empty)
      _ <- props.values.toList.traverse_ { case (_, d) =>
        d.complete(Left(err)).void
      }
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
                .flatMap(e => ClusterConfig.decode(e.data).toOption)
            }
        else Async[F].pure(None)
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
        msgQueue.offer(ElectionTimeout)
      )
      _ <- electionFiberRef.set(Some(fiber))
    yield ()

  private def resetHeartbeatTimer: F[Unit] =
    for
      _ <- cancelFiber(heartbeatFiberRef)
      fiber <- timerService.scheduleHeartbeat(
        config.heartbeatInterval,
        msgQueue.offer(HeartbeatTimeout)
      )
      _ <- heartbeatFiberRef.set(Some(fiber))
    yield ()

  private def resetCheckQuorumTimer: F[Unit] =
    for
      _ <- cancelFiber(checkQuorumFiberRef)
      fiber <- timerService.scheduleElection(
        config.electionTimeoutMax,
        msgQueue.offer(CheckQuorumTimeout)
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
      case None        => Async[F].unit
    }

  /** Initialize follower tracking and append the mandatory §8 / §5.4.2 leader
    * `NoOp` barrier entry for the new term.
    */
  private def initializeLeaderAndNoOp(term: Long): F[Unit] =
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
        else Async[F].unit
      _ <- stateRef.get.flatMap {
        case l: Leader =>
          if cluster.hasQuorum(Set(config.localId)) then
            val committed = LogIndex.unsafeFrom(noOpIndex)
            stateRef.set(l.withCommitIndex(noOpIndex)) *>
              applyCommittedEntries(committed)
          else replicateAllPeers(l, LogIndex.unsafeFrom(noOpIndex))
        case _ => Async[F].unit
      }
    yield ()

  private def applyEntry(entry: Log): F[Option[ClusterConfig]] =
    entry.logType match
      case LogType.Command =>
        stateMachine.apply(entry).as(None)
      case LogType.Configuration =>
        ClusterConfig.decode(entry.data) match
          case Right(cfg) => clusterRef.set(cfg).as(Some(cfg))
          case Left(_)    => Async[F].pure(None)
      case LogType.NoOp =>
        Async[F].pure(None)

  private def applyCommittedEntries(upToIndex: LogIndex): F[Unit] =
    for
      initialApplied <- lastAppliedRef.get
      _ <-
        if upToIndex.value > initialApplied then
          (initialApplied + 1L to upToIndex.value).toList.traverse_ { idx =>
            lastAppliedRef.get.flatMap { currentApplied =>
              if idx <= currentApplied then Async[F].unit
              else
                val logIndex = LogIndex.unsafeFrom(idx)
                logStore.get(logIndex).flatMap {
                  case Some(entry) =>
                    for
                      committedCfgOpt <- applyEntry(entry)
                      _ <- lastAppliedRef.set(idx)
                      _ <- metrics.onEntriesApplied(config.localId, idx)
                      _ <- completeProposalAt(idx, entry.term)
                      _ <- completeReadApplyWaitersUpTo(idx)
                      _ <- committedCfgOpt
                        .traverse_(cfg => onCommittedConfiguration(idx, cfg))
                    yield ()
                  case None =>
                    logger.warn(
                      s"Missing log entry at index $idx during apply"
                    )
                }
            }
          } *> maybeRunCompaction(upToIndex.value)
        else Async[F].unit
    yield ()

  private def completeProposalAt(idx: Long, actualTerm: Long): F[Unit] =
    pendingProposalsRef
      .modify(m => (m - idx, m.get(idx)))
      .flatMap {
        case None                       => Async[F].unit
        case Some((expectedTerm, defr)) =>
          if expectedTerm == actualTerm then
            defr.complete(Right(LogId(actualTerm, idx))).void
          else
            defr
              .complete(
                Left(
                  RaftError.ProposalDropped(
                    s"Entry at index $idx overwritten by term $actualTerm"
                  )
                )
              )
              .void
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

  /** Handle post-commit Joint Consensus transitions (§6):
    *   - When a joint configuration `C_old,new` commits on the active leader,
    *     automatically append & replicate `C_new` (`completeJointConsensus`).
    *   - When a finalized configuration `C_new` commits and excludes this
    *     leader from `voters`, immediately step down to `Follower`.
    */
  private def onCommittedConfiguration(
      committedIdx: Long,
      cfg: ClusterConfig
  ): F[Unit] =
    stateRef.get.flatMap {
      case l: Leader if cfg.isInJointConsensus =>
        for
          alreadyPending <- hasPendingConfigAfter(committedIdx)
          _ <-
            if alreadyPending then Async[F].unit
            else
              val finalCfg = cfg.completeJointConsensus
              for
                lastCoord <- effectiveLastLog
                lastIdx = lastCoord._1
                cNewIdx = lastIdx + 1L
                cNewEntry = Log(
                  index = cNewIdx,
                  term = l.term,
                  logType = LogType.Configuration,
                  data = ClusterConfig.encode(finalCfg)
                )
                _ <- logStore.append(Seq(cNewEntry))
                _ <- clusterRef.set(finalCfg)
                _ <-
                  if config.checkQuorumEnabled then resetCheckQuorumTimer
                  else Async[F].unit
                transferredWaiterOpt <- jointWaiterRef
                  .modify(m => (m - committedIdx, m.get(committedIdx)))
                _ <- transferredWaiterOpt.traverse_ { w =>
                  pendingProposalsRef.update(_.updated(cNewIdx, (l.term, w)))
                }
                _ <- metrics.onEntriesAppended(config.localId, 1)
                _ <-
                  if finalCfg.hasQuorum(Set(config.localId)) then
                    val committed = LogIndex.unsafeFrom(cNewIdx)
                    stateRef.update {
                      case cur: Leader => cur.withCommitIndex(cNewIdx)
                      case other       => other
                    } *>
                      applyCommittedEntries(committed) *>
                      metrics.onEntriesCommitted(config.localId, cNewIdx)
                  else
                    replicatePeers(
                      l,
                      (cfg.allNodes ++ finalCfg.allNodes) - config.localId,
                      LogIndex.unsafeFrom(cNewIdx)
                    )
              yield ()
        yield ()

      case l: Leader if !cfg.isVoter(config.localId) =>
        logger.info(
          s"Leader ${config.localId.value} removed from voters at index $committedIdx; stepping down"
        ) *>
          stateRef.set(Follower(l.term, None, None)) *>
          handleLeaderStepDown

      case _ =>
        Async[F].unit
    }

  private def maybeRunCompaction(lastApplied: Long): F[Unit] =
    compactionMgrOpt match
      case None      => Async[F].unit
      case Some(mgr) =>
        for
          snapOpt <- latestSnapMetaRef.get
          lastCoord <- effectiveLastLog
          lastIdx = lastCoord._1
          snapIdx = snapOpt.map(_.lastIncludedIndex).getOrElse(0L)
          approxLogSize = math.max(0L, lastIdx - snapIdx)
          _ <- mgr.maybeCompact(lastApplied, approxLogSize)
        yield ()
