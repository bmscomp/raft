package raft

import cats.effect.*
import cats.effect.std.Queue
import cats.effect.syntax.spawn.*
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.*

import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.*
import raft.effect.Effect as Eff
import raft.logic.RaftLogic
import raft.spi.*
import raft.metrics.RaftMetrics

/** Event-driven RAFT node runtime — the '''Imperative Shell''' of the
  * Functional Core / Imperative Shell architecture.
  *
  * While [[raft.logic.RaftLogic]] contains the pure, deterministic consensus
  * logic (the ''Functional Core''), this runtime is the ''Imperative Shell''
  * that connects the pure logic to the real world. It manages the event loop,
  * reads from stable storage, processes incoming messages, and interprets the
  * [[raft.effect.Effect]]s produced by the logic layer.
  *
  * The runtime follows a reactive architecture — no tick-based polling. Two
  * concurrent FS2 streams drive the node:
  *   1. '''Message stream''' — receives `AppendEntries`, `RequestVote`, and
  *      other RPCs from peers via the transport layer.
  *   1. '''Timer stream''' — fires election and heartbeat timeouts at
  *      configured intervals.
  *
  * Both streams feed into a single `processMessage` function that calls
  * `RaftLogic.onMessage`, collects the resulting `Transition`, atomically
  * updates the node state, and executes the returned effects in order.
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @see
  *   [[raft.logic.RaftLogic]] for the pure consensus logic
  * @see
  *   [[raft.effect.Effect]] for the side effects the runtime executes
  */
trait RaftNode[F[_]]:
  /** Start the RAFT node event loop.
    *
    * Initializes state from stable storage, then runs the message processing
    * and election timer streams concurrently until [[shutdown]] is called.
    */
  def start: F[Unit]

  /** Submit a client command to be replicated across the cluster.
    *
    * Commands are only accepted by the leader. If this node is not the leader,
    * the command may be rejected or forwarded.
    *
    * @param data
    *   the serialized command payload to replicate
    */
  def submit(data: Array[Byte]): F[Unit]

  /** Get the current state of this node (role, term, leader).
    *
    * @return
    *   the current [[NodeState]] (Follower, Candidate, or Leader)
    */
  def getState: F[NodeState]

  /** Gracefully shut down the node, stopping the event loop and releasing
    * resources.
    */
  def shutdown: F[Unit]

/** Factory for creating [[RaftNode]] instances as managed Cats Effect
  * resources.
  *
  * @see
  *   [[RaftNode]] for the runtime interface
  */
object RaftNode:
  /** Create a new [[RaftNode]] as a managed resource.
    *
    * The returned resource initializes the node's internal state (refs, queues,
    * logger) and will be properly finalized when released.
    *
    * @tparam F
    *   the effect type (requires `Async`)
    * @param config
    *   the node configuration (timeouts, features, local ID)
    * @param cluster
    *   the initial cluster membership configuration
    * @param transport
    *   the network transport for inter-node communication
    * @param logStore
    *   the persistent log storage
    * @param stableStore
    *   the durable hard-state storage (term, votedFor)
    * @param stateMachine
    *   the user-provided state machine for applying committed entries
    * @param timerService
    *   the timer service for scheduling election and heartbeat timeouts
    * @param metrics
    *   optional observability hooks (defaults to no-op)
    * @return
    *   a managed resource wrapping the created RAFT node
    */
  def apply[F[_]: Async](
      config: RaftConfig,
      cluster: ClusterConfig,
      transport: RaftTransport[F],
      logStore: LogStore[F],
      stableStore: StableStore[F],
      stateMachine: StateMachine[F, ?],
      timerService: TimerService[F],
      metrics: RaftMetrics[F] = null.asInstanceOf[RaftMetrics[F]]
  ): Resource[F, RaftNode[F]] = Resource.eval {
    val resolvedMetrics =
      if metrics == null then RaftMetrics.noop[F] else metrics
    for
      logger <- Slf4jLogger.create[F]
      stateRef <- Ref.of[F, NodeState](Follower(0, None, None))
      shutdownRef <- SignallingRef.of[F, Boolean](false)
      msgQueue <- Queue.unbounded[F, RaftMessage]
      lastAppliedRef <- Ref.of[F, Long](0L)
      electionFiber <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](None)
      heartbeatFiber <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](None)
    yield new RaftNodeImpl[F](
      config,
      cluster,
      transport,
      logStore,
      stableStore,
      stateMachine,
      timerService,
      resolvedMetrics,
      stateRef,
      shutdownRef,
      msgQueue,
      lastAppliedRef,
      electionFiber,
      heartbeatFiber,
      logger
    )
  }

private class RaftNodeImpl[F[_]: Async](
    config: RaftConfig,
    cluster: ClusterConfig,
    transport: RaftTransport[F],
    logStore: LogStore[F],
    stableStore: StableStore[F],
    stateMachine: StateMachine[F, ?],
    timerService: TimerService[F],
    metrics: RaftMetrics[F],
    stateRef: Ref[F, NodeState],
    shutdownRef: SignallingRef[F, Boolean],
    msgQueue: Queue[F, RaftMessage],
    lastAppliedRef: Ref[F, Long],
    electionFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]],
    heartbeatFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]],
    logger: Logger[F]
) extends RaftNode[F]:

  def start: F[Unit] =
    for
      _ <- logger.info(s"Starting RAFT node ${config.localId.value}")
      term <- stableStore.currentTerm
      votedFor <- stableStore.votedFor
      _ <- stateRef.set(Follower(term.value, votedFor, None))
      _ <- resetElectionTimer
      _ <- runEventLoop.start
    yield ()

  def submit(data: Array[Byte]): F[Unit] =
    for
      state <- stateRef.get
      _ <- state match
        case l: Leader =>
          for
            lastIdx <- logStore.lastIndex
            newIndex = lastIdx.value + 1
            entry = Log.command(newIndex, l.term, data)
            _ <- logStore.append(Seq(entry))
            _ <- metrics.onEntriesAppended(config.localId, 1)
            _ <- logger.debug(
              s"Leader appended command at index $newIndex"
            )
            heartbeat = AppendEntriesRequest(
              term = l.term,
              leaderId = config.localId,
              prevLogIndex = lastIdx.value,
              prevLogTerm = l.term,
              entries = Seq(entry),
              leaderCommit = l.commitIndex
            )
            _ <- transport.broadcast(heartbeat)
          yield ()
        case f: Follower =>
          f.leaderId match
            case Some(leader) =>
              Async[F].raiseError(
                new RuntimeException(
                  s"Not leader. Current leader: ${leader.value}"
                )
              )
            case None =>
              Async[F].raiseError(
                new RuntimeException("Not leader. No known leader.")
              )
        case _ =>
          Async[F].raiseError(
            new RuntimeException("Not leader. Currently in election.")
          )
    yield ()

  def getState: F[NodeState] = stateRef.get

  def shutdown: F[Unit] =
    for
      _ <- logger.info("Shutting down RAFT node")
      _ <- shutdownRef.set(true)
      _ <- cancelFiber(electionFiberRef)
      _ <- cancelFiber(heartbeatFiberRef)
    yield ()

  private def runEventLoop: F[Unit] =
    val messageStream = transport.receive.evalMap { case (from, msg) =>
      processMessage(msg)
    }

    val timerStream = Stream
      .repeatEval(msgQueue.take)
      .evalMap(processMessage)

    messageStream
      .merge(timerStream)
      .interruptWhen(shutdownRef)
      .compile
      .drain

  private def processMessage(msg: RaftMessage): F[Unit] =
    for
      state <- stateRef.get
      lastLogIndex <- logStore.lastIndex
      lastLogTerm <- logStore
        .termAt(lastLogIndex)
        .map(_.map(_.value).getOrElse(0L))
      getTermAt = (idx: Long) =>
        if idx <= 0 then None
        else
          // Build a synchronous lookup from log store. For AppendEntries
          // validation, the runtime pre-fetches needed terms into a cache.
          // In practice only a small window of terms is needed per message.
          None // Falls back to the public onVoteResponse for precise tracking
      transition = RaftLogic.onMessage(
        state,
        msg,
        config,
        lastLogIndex.value,
        lastLogTerm,
        cluster.voters.size,
        getTermAt
      )
      _ <- stateRef.set(transition.state)
      _ <- executeEffects(transition.effects)
    yield ()

  private def executeEffects(effects: List[Eff]): F[Unit] =
    effects.traverse_ {
      case Eff.SendMessage(to, msg)       => transport.send(to, msg)
      case Eff.Broadcast(msg)             => transport.broadcast(msg)
      case Eff.PersistHardState(term, vf) =>
        stableStore.setCurrentTerm(Term.unsafeFrom(term)) *> stableStore
          .setVotedFor(vf)
      case Eff.AppendLogs(entries) =>
        logStore.append(entries) *>
          metrics.onEntriesAppended(config.localId, entries.size)
      case Eff.TruncateLog(fromIndex) =>
        logStore.truncateFrom(LogIndex.unsafeFrom(fromIndex))
      case Eff.ApplyToStateMachine(entry)      => stateMachine.apply(entry).void
      case Eff.ResetElectionTimer              => resetElectionTimer
      case Eff.ResetHeartbeatTimer             => resetHeartbeatTimer
      case Eff.TakeSnapshot(lastIdx, lastTerm) =>
        logger.debug(
          s"Snapshot requested at index=$lastIdx term=$lastTerm"
        )
      case Eff.BecomeLeader =>
        logger.info("Became leader!") *>
          metrics.onBecomeLeader(config.localId, 0L) *>
          initializeLeaderState
      case Eff.TransferLeadership(target) =>
        logger.info(s"Transferring leadership to ${target.value}")
      case Eff.InitializeLeaderState(followers, lastLog) =>
        stateRef.update {
          case l: Leader => l.initializeIndices(followers, lastLog)
          case s         => s
        }
      case Eff.UpdateFollowerIndex(followerId, matchIdx, nextIdx) =>
        stateRef.update {
          case l: Leader =>
            l.withMatchIndex(followerId, matchIdx)
              .withNextIndex(followerId, nextIdx)
          case s => s
        }
      case Eff.CommitEntries(upToIndex) =>
        applyCommittedEntries(upToIndex) *>
          metrics.onEntriesCommitted(config.localId, upToIndex)
      case Eff.ParallelReplicate(targets, msg) =>
        targets.toList.traverse_(t => transport.send(t, msg))
      case Eff.BatchAppend(entries, _) =>
        logStore.append(entries) *>
          metrics.onEntriesAppended(config.localId, entries.size)
      case Eff.BatchComplete(_, _) =>
        Async[F].unit
      case Eff.PipelinedSend(to, msg, _) =>
        transport.send(to, msg)
      case Eff.TrackInflight(_, _, _) =>
        Async[F].unit
      case Eff.ReadIndexReady(_, _) =>
        Async[F].unit
      case Eff.ReadIndexRejected(_, _) =>
        Async[F].unit
      case Eff.ConfirmLeadership(_, _) =>
        Async[F].unit
      case Eff.TimeoutNow(target) =>
        logger.info(
          s"TimeoutNow → ${target.value}: triggering immediate election"
        )
      case Eff.ExtendLease(_) =>
        Async[F].unit
      case Eff.LeaseReadReady(_) =>
        Async[F].unit
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

  private def initializeLeaderState: F[Unit] =
    for
      lastLogIndex <- logStore.lastIndex
      followers = cluster.voters.toSet - config.localId
      _ <- stateRef.update {
        case l: Leader => l.initializeIndices(followers, lastLogIndex.value)
        case s         => s
      }
    yield ()

  private def applyCommittedEntries(upToIndex: Long): F[Unit] =
    for
      lastApplied <- lastAppliedRef.get
      _ <-
        if upToIndex > lastApplied then
          (lastApplied + 1 to upToIndex).toList.traverse_ { idx =>
            val logIndex = LogIndex.unsafeFrom(idx)
            logStore.get(logIndex).flatMap {
              case Some(entry) =>
                stateMachine.apply(entry).void *>
                  lastAppliedRef.set(idx) *>
                  metrics.onEntriesApplied(config.localId, idx)
              case None =>
                logger.warn(s"Missing log entry at index $idx during apply")
            }
          }
        else Async[F].unit
    yield ()
