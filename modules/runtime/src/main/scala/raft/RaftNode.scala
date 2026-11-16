package raft

import cats.effect.*
import cats.effect.implicits.*
import cats.effect.std.{Mutex, Queue}
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.Effect as Eff
import raft.logic.RaftLogic
import raft.spi.*
import raft.metrics.RaftMetrics

/** Event-driven Cats Effect adapter around the pure [[raft.logic.RaftLogic]]
  * consensus engine.
  *
  * Host systems that manage their own threads, event loops, or storage directly
  * can embed zero-dependency `raft-core` (`RawNode` / `RaftLogic`) without this
  * runtime; systems built on Cats Effect + FS2 can use `RaftNode` as a turnkey
  * `Resource`-managed consensus actor.
  */
trait RaftNode[F[_]]:
  /** Start (or restart) the RAFT node event loop. */
  def start: F[Unit]

  /** Propose a client command payload and return its assigned `(term, index)`
    * coordinate once appended to the local leader log.
    */
  def propose(data: Array[Byte]): F[LogId]

  /** Submit a client command payload to be replicated across the cluster. */
  def submit(data: Array[Byte]): F[Unit]

  /** Inspect the current consensus role, term, and leader state. */
  def getState: F[NodeState]

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
        pendingSnapRef <- Ref.of[F, Option[PendingSnapshot]](None)
        eventLoopFiberRef <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](None)
        electionFiberRef <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](None)
        heartbeatFiberRef <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](None)
      yield new RaftNodeImpl[F](
        config,
        clusterRef,
        transport,
        logStore,
        stableStore,
        snapshotStoreOpt,
        stateMachine,
        timerService,
        resolvedMetrics,
        mutex,
        stateRef,
        shutdownRef,
        msgQueue,
        lastAppliedRef,
        pendingSnapRef,
        eventLoopFiberRef,
        electionFiberRef,
        heartbeatFiberRef,
        resolvedLogger
      )
    Resource.make(acquire)(_.shutdown)

private final class RaftNodeImpl[F[_]: Async](
    config: RaftConfig,
    clusterRef: Ref[F, ClusterConfig],
    transport: RaftTransport[F],
    logStore: LogStore[F],
    stableStore: StableStore[F],
    snapshotStoreOpt: Option[SnapshotStore[F]],
    stateMachine: StateMachine[F, ?],
    timerService: TimerService[F],
    metrics: RaftMetrics[F],
    mutex: Mutex[F],
    stateRef: Ref[F, NodeState],
    shutdownRef: SignallingRef[F, Boolean],
    msgQueue: Queue[F, RaftMessage],
    lastAppliedRef: Ref[F, Long],
    pendingSnapRef: Ref[F, Option[PendingSnapshot]],
    eventLoopFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]],
    electionFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]],
    heartbeatFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]],
    logger: Logger[F]
) extends RaftNode[F]:

  def start: F[Unit] =
    for
      _ <- cancelFiber(eventLoopFiberRef)
      _ <- cancelFiber(electionFiberRef)
      _ <- cancelFiber(heartbeatFiberRef)
      _ <- drainQueue
      _ <- shutdownRef.set(false)
      _ <- logger.info(s"Starting RAFT node ${config.localId.value}")
      term <- stableStore.currentTerm
      votedFor <- stableStore.votedFor
      _ <- stateRef.set(Follower(term.value, votedFor, None))
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
            lastIdx <- logStore.lastIndex
            newIndex = lastIdx.value + 1L
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

  def submit(data: Array[Byte]): F[Unit] =
    propose(data).void

  def getState: F[NodeState] = stateRef.get

  def shutdown: F[Unit] =
    for
      _ <- logger.info(s"Shutting down RAFT node ${config.localId.value}")
      _ <- shutdownRef.set(true)
      _ <- cancelFiber(electionFiberRef)
      _ <- cancelFiber(heartbeatFiberRef)
      _ <- cancelFiber(eventLoopFiberRef)
    yield ()

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
    mutex.lock.surround {
      for
        _ <- accumulateSnapshotChunk(msg)
        state <- stateRef.get
        cluster <- clusterRef.get
        lastLogIndex <- logStore.lastIndex
        lastLogTerm <- logStore
          .termAt(lastLogIndex)
          .map(_.map(_.value).getOrElse(0L))
        termCache <- prefetchTerms(state, msg, lastLogIndex)
        getTermAt = (idx: Long) => termCache.get(idx)
        transition = RaftLogic.onMessage(
          state = state,
          message = msg,
          config = config,
          lastLogIndex = lastLogIndex.value,
          lastLogTerm = lastLogTerm,
          clusterSize = cluster.voters.size,
          getTermAt = getTermAt,
          sender = senderOpt,
          clusterOpt = Some(cluster)
        )
        _ <- stateRef.set(transition.state)
        _ <- executeEffects(transition.effects)
        _ <- maybeSendCatchUpEntries(msg, senderOpt)
      yield ()
    }

  /** Buffer incoming `InstallSnapshotRequest` chunks prior to final
    * installation.
    */
  private def accumulateSnapshotChunk(msg: RaftMessage): F[Unit] =
    msg match
      case req: InstallSnapshotRequest =>
        pendingSnapRef.update {
          case _ if req.offset == 0L =>
            Some(
              PendingSnapshot(
                lastIncludedIndex = req.lastIncludedIndex,
                lastIncludedTerm = req.lastIncludedTerm,
                offset = 0L,
                chunks = Vector.empty
              ).appendChunk(req.data)
            )
          case Some(pending)
              if pending.lastIncludedIndex == req.lastIncludedIndex &&
                pending.offset == req.offset =>
            Some(pending.appendChunk(req.data))
          case other => other
        }
      case _ => Async[F].unit

  /** Pre-fetch the bounded window of log terms needed for pure transition
    * validation (`AppendEntries` consistency, fast conflict backtracking, and
    * leader commit calculation).
    */
  private def prefetchTerms(
      state: NodeState,
      msg: RaftMessage,
      lastLogIndex: LogIndex
  ): F[Map[Long, Long]] =
    if lastLogIndex.value <= 0L then Async[F].pure(Map.empty)
    else
      val startIdx = (state, msg) match
        case (l: Leader, _) =>
          val minFollower =
            if l.nextIndex.isEmpty then 1L
            else math.max(1L, l.nextIndex.values.min - 1L)
          val minCommit = math.max(1L, l.commitIndex)
          math.min(minFollower, minCommit)
        case (_, ae: AppendEntriesRequest) =>
          math.max(1L, ae.prevLogIndex - 64L)
        case (_, snap: InstallSnapshotRequest) =>
          math.max(1L, snap.lastIncludedIndex)
        case _ =>
          lastLogIndex.value
      val clampedStart = math.min(startIdx, lastLogIndex.value)
      logStore
        .getRange(LogIndex.unsafeFrom(clampedStart), lastLogIndex)
        .map(_.iterator.map(e => e.index -> e.term).toMap)

  /** Replicate missing log entries `[nextIdx .. lastIdx]` to followers on
    * heartbeat ticks or after fast conflict backtracking converges.
    */
  private def maybeSendCatchUpEntries(
      trigger: RaftMessage,
      senderOpt: Option[NodeId]
  ): F[Unit] =
    stateRef.get.flatMap {
      case l: Leader =>
        trigger match
          case HeartbeatTimeout =>
            logStore.lastIndex.flatMap { lastIdx =>
              l.nextIndex.toList.parTraverse_ { case (peerId, nextIdx) =>
                if nextIdx <= lastIdx.value then
                  replicatePeerRange(l, peerId, nextIdx, lastIdx)
                else Async[F].unit
              }
            }
          case resp: AppendEntriesResponse if resp.success =>
            senderOpt match
              case Some(peerId) =>
                logStore.lastIndex.flatMap { lastIdx =>
                  val nextIdx =
                    l.nextIndex.getOrElse(peerId, lastIdx.value + 1L)
                  if nextIdx <= lastIdx.value then
                    replicatePeerRange(l, peerId, nextIdx, lastIdx)
                  else Async[F].unit
                }
              case None => Async[F].unit
          case _ => Async[F].unit
      case _ => Async[F].unit
    }

  private def replicateAllPeers(leader: Leader, lastIdx: LogIndex): F[Unit] =
    clusterRef.get.flatMap { cluster =>
      val peers = (cluster.allNodes - config.localId).toList
      peers.parTraverse_ { peerId =>
        val nextIdx = leader.nextIndex.getOrElse(peerId, lastIdx.value)
        replicatePeerRange(leader, peerId, math.max(1L, nextIdx), lastIdx)
      }
    }

  private def replicatePeerRange(
      leader: Leader,
      peerId: NodeId,
      fromIndex: Long,
      lastIdx: LogIndex
  ): F[Unit] =
    val safeFrom = math.max(1L, fromIndex)
    val prevIdx = safeFrom - 1L
    for
      prevTerm <-
        if prevIdx <= 0L then Async[F].pure(0L)
        else
          logStore
            .termAt(LogIndex.unsafeFrom(prevIdx))
            .map(_.map(_.value).getOrElse(0L))
      entries <-
        if safeFrom <= lastIdx.value then
          logStore.getRange(LogIndex.unsafeFrom(safeFrom), lastIdx)
        else Async[F].pure(Vector.empty)
      req = AppendEntriesRequest(
        term = leader.term,
        leaderId = config.localId,
        prevLogIndex = prevIdx,
        prevLogTerm = prevTerm,
        entries = entries,
        leaderCommit = math.min(leader.commitIndex, prevIdx + entries.size)
      )
      _ <- transport.send(peerId, req)
    yield ()

  private def executeEffects(effects: List[Eff]): F[Unit] =
    effects.traverse_ {
      case Eff.SendMessage(to, msg) =>
        transport.send(to, msg)

      case Eff.Broadcast(msg) =>
        transport.broadcast(msg)

      case Eff.PersistHardState(term, vf) =>
        stableStore.setCurrentTerm(Term.unsafeFrom(term)) *>
          stableStore.setVotedFor(vf)

      case Eff.AppendLogs(entries) =>
        logStore.append(entries) *>
          metrics.onEntriesAppended(config.localId, entries.size)

      case Eff.TruncateLog(fromIndex) =>
        logStore.truncateFrom(LogIndex.unsafeFrom(fromIndex))

      case Eff.ResetElectionTimer =>
        resetElectionTimer

      case Eff.ResetHeartbeatTimer =>
        resetHeartbeatTimer

      case Eff.RequestSnapshot(lastApplied, _) =>
        for
          _ <- logger.info(s"Snapshot requested at index=$lastApplied")
          termOpt <- logStore.termAt(LogIndex.unsafeFrom(lastApplied))
          term = termOpt.map(_.value).getOrElse(0L)
          cluster <- clusterRef.get
          data <- stateMachine.snapshot
          snapshot = Snapshot[F](lastApplied, term, data, Some(cluster))
          _ <- snapshotStoreOpt.traverse_ { store =>
            store.save(snapshot) *>
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
          _ <- logger.info(
            s"Installing snapshot at index=${metadata.lastIncludedIndex} term=${metadata.lastIncludedTerm}"
          )
          pendingOpt <- pendingSnapRef.getAndSet(None)
          bytesOpt = pendingOpt.map(_.toBytes)
          stream = bytesOpt match
            case Some(arr) => Stream.chunk(fs2.Chunk.array(arr)).covary[F]
            case None      => Stream.empty.covary[F]
          _ <- stateMachine.restore(stream)
          _ <- snapshotStoreOpt.traverse_ { store =>
            val persistStream = bytesOpt match
              case Some(arr) => Stream.chunk(fs2.Chunk.array(arr)).covary[F]
              case None      => Stream.empty.covary[F]
            store.save(
              Snapshot[F](
                metadata.lastIncludedIndex,
                metadata.lastIncludedTerm,
                persistStream,
                metadata.config
              )
            )
          }
          _ <- metadata.config.traverse_(clusterRef.set)
          _ <- logStore.clear
          _ <- lastAppliedRef.set(metadata.lastIncludedIndex)
          _ <- metrics.onSnapshotTaken(
            config.localId,
            metadata.lastIncludedIndex
          )
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
          _ <- initializeLeaderAndNoOp(term)
        yield ()

      case Eff.StopLeader =>
        for
          st <- stateRef.get
          _ <- cancelFiber(heartbeatFiberRef)
          _ <- metrics.onStepDown(config.localId, st.term)
        yield ()

      case Eff.TransferLeadership(target) =>
        logger.info(s"Transferring leadership to ${target.value}")

      case Eff.CommitEntries(upToIndex) =>
        applyCommittedEntries(upToIndex) *>
          metrics.onEntriesCommitted(config.localId, upToIndex.value)

      case Eff.ReadIndexReady(_, _) =>
        Async[F].unit

      case Eff.ReadIndexRejected(_, _, _) =>
        Async[F].unit

      case Eff.ConfirmLeadership(_, _) =>
        Async[F].unit

      case Eff.TimeoutNow(target) =>
        logger.info(
          s"TimeoutNow -> ${target.value}: triggering immediate election"
        )

      case Eff.ApplyClusterConfig(newConfig, _, _) =>
        clusterRef.set(newConfig)
    }

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
      lastLogIndex <- logStore.lastIndex
      cluster <- clusterRef.get
      followers = cluster.allNodes - config.localId
      noOpIndex = lastLogIndex.value + 1L
      noOpEntry = Log.noOp(noOpIndex, term)
      _ <- logStore.append(Seq(noOpEntry))
      _ <- stateRef.update {
        case l: Leader => l.initializeIndices(followers, lastLogIndex.value)
        case s         => s
      }
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

  private def applyEntry(entry: Log): F[Unit] =
    entry.logType match
      case LogType.Command =>
        stateMachine.apply(entry).void
      case LogType.Configuration =>
        ClusterConfig.decode(entry.data) match
          case Right(cfg) => clusterRef.set(cfg)
          case Left(_)    => Async[F].unit
      case LogType.NoOp =>
        Async[F].unit

  private def applyCommittedEntries(upToIndex: LogIndex): F[Unit] =
    for
      lastApplied <- lastAppliedRef.get
      _ <-
        if upToIndex.value > lastApplied then
          (lastApplied + 1L to upToIndex.value).toList.traverse_ { idx =>
            val logIndex = LogIndex.unsafeFrom(idx)
            logStore.get(logIndex).flatMap {
              case Some(entry) =>
                applyEntry(entry) *>
                  lastAppliedRef.set(idx) *>
                  metrics.onEntriesApplied(config.localId, idx)
              case None =>
                logger.warn(s"Missing log entry at index $idx during apply")
            }
          }
        else Async[F].unit
    yield ()
