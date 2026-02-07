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
    * @return
    *   a managed resource wrapping the created RAFT node
    */
  def apply[F[_]: Async](
      config: RaftConfig,
      cluster: ClusterConfig,
      transport: RaftTransport[F],
      logStore: LogStore[F],
      stableStore: StableStore[F],
      stateMachine: StateMachine[F, ?]
  ): Resource[F, RaftNode[F]] = Resource.eval {
    for
      logger <- Slf4jLogger.create[F]
      stateRef <- Ref.of[F, NodeState](Follower(0, None, None))
      shutdownRef <- SignallingRef.of[F, Boolean](false)
      msgQueue <- Queue.unbounded[F, RaftMessage]
    yield new RaftNodeImpl[F](
      config,
      cluster,
      transport,
      logStore,
      stableStore,
      stateMachine,
      stateRef,
      shutdownRef,
      msgQueue,
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
    stateRef: Ref[F, NodeState],
    shutdownRef: SignallingRef[F, Boolean],
    msgQueue: Queue[F, RaftMessage],
    logger: Logger[F]
) extends RaftNode[F]:

  def start: F[Unit] =
    for
      _ <- logger.info(s"Starting RAFT node ${config.localId.value}")
      // Initialize from stable storage
      term <- stableStore.currentTerm
      votedFor <- stableStore.votedFor
      _ <- stateRef.set(Follower(term.value, votedFor, None))
      // Start event loop
      _ <- runEventLoop.start
    yield ()

  def submit(data: Array[Byte]): F[Unit] =
    logger.info("Submitting command") // TODO: implement replication

  def getState: F[NodeState] = stateRef.get

  def shutdown: F[Unit] =
    for
      _ <- logger.info("Shutting down RAFT node")
      _ <- shutdownRef.set(true)
    yield ()

  private def runEventLoop: F[Unit] =
    val messageStream = transport.receive.evalMap { case (from, msg) =>
      processMessage(msg)
    }

    val electionTimer = Stream
      .awakeEvery[F](config.electionTimeoutMin)
      .evalMap(_ => processMessage(ElectionTimeout))

    // Run both streams concurrently
    messageStream
      .merge(electionTimer)
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
      // Create getTermAt callback for log matching validation
      getTermAt = (idx: Long) =>
        None // Simplified - runtime should look up from logStore
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
      case Eff.AppendLogs(entries)    => logStore.append(entries)
      case Eff.TruncateLog(fromIndex) =>
        logStore.truncateFrom(LogIndex.unsafeFrom(fromIndex))
      case Eff.ApplyToStateMachine(entry) => stateMachine.apply(entry).void
      case Eff.ResetElectionTimer         =>
        Async[F].unit // TODO: integrate TimerService
      case Eff.ResetHeartbeatTimer =>
        Async[F].unit // TODO: integrate TimerService
      case Eff.TakeSnapshot(_, _) => Async[F].unit
      case Eff.BecomeLeader       =>
        logger.info("Became leader!") *> initializeLeaderState
      case Eff.TransferLeadership(_)                     => Async[F].unit
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
        applyCommittedEntries(upToIndex)
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
    // Apply all entries up to upToIndex to the state machine
    // This is a simplified version - should track last applied
    Async[F].unit // TODO: implement proper entry application
