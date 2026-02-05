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

/**
 * Event-driven RAFT node runtime.
 * 
 * Orchestrates state transitions and effect execution.
 * No tick polling - reacts only to messages and timers.
 * 
 * @tparam F effect type (e.g., IO)
 */
trait RaftNode[F[_]]:
  /**
   * Start the RAFT node.
   */
  def start: F[Unit]
  
  /**
   * Submit a command to be replicated.
   */
  def submit(data: Array[Byte]): F[Unit]
  
  /**
   * Get current state (role, term, leader).
   */
  def getState: F[NodeState]
  
  /**
   * Gracefully shutdown.
   */
  def shutdown: F[Unit]

object RaftNode:
  /**
   * Create a new RaftNode.
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
      logger      <- Slf4jLogger.create[F]
      stateRef    <- Ref.of[F, NodeState](Follower(0, None, None))
      shutdownRef <- SignallingRef.of[F, Boolean](false)
      msgQueue    <- Queue.unbounded[F, RaftMessage]
    yield new RaftNodeImpl[F](
      config, cluster, transport, logStore, stableStore, 
      stateMachine, stateRef, shutdownRef, msgQueue, logger
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
      term     <- stableStore.currentTerm
      votedFor <- stableStore.votedFor
      _        <- stateRef.set(Follower(term.value, votedFor, None))
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
    
    val electionTimer = Stream.awakeEvery[F](config.electionTimeoutMin)
      .evalMap(_ => processMessage(ElectionTimeout))
    
    // Run both streams concurrently
    messageStream
      .merge(electionTimer)
      .interruptWhen(shutdownRef)
      .compile
      .drain
  
  private def processMessage(msg: RaftMessage): F[Unit] =
    for
      state        <- stateRef.get
      lastLogIndex <- logStore.lastIndex
      lastLogTerm  <- logStore.termAt(lastLogIndex).map(_.map(_.value).getOrElse(0L))
      // Create getTermAt callback for log matching validation
      getTermAt = (idx: Long) => None // Simplified - runtime should look up from logStore
      transition   = RaftLogic.onMessage(state, msg, config, lastLogIndex.value, lastLogTerm, cluster.voters.size, getTermAt)
      _            <- stateRef.set(transition.state)
      _            <- executeEffects(transition.effects)
    yield ()
  
  private def executeEffects(effects: List[Eff]): F[Unit] =
    effects.traverse_ {
      case Eff.SendMessage(to, msg)       => transport.send(to, msg)
      case Eff.Broadcast(msg)             => transport.broadcast(msg)
      case Eff.PersistHardState(term, vf) => 
        stableStore.setCurrentTerm(Term.unsafeFrom(term)) *> stableStore.setVotedFor(vf)
      case Eff.AppendLogs(entries)        => logStore.append(entries)
      case Eff.TruncateLog(fromIndex)     => logStore.truncateFrom(LogIndex.unsafeFrom(fromIndex))
      case Eff.ApplyToStateMachine(entry) => stateMachine.apply(entry).void
      case Eff.ResetElectionTimer         => Async[F].unit // TODO: integrate TimerService
      case Eff.ResetHeartbeatTimer        => Async[F].unit // TODO: integrate TimerService
      case Eff.TakeSnapshot(_, _)         => Async[F].unit
      case Eff.BecomeLeader               => 
        logger.info("Became leader!") *> initializeLeaderState
      case Eff.TransferLeadership(_)      => Async[F].unit
      case Eff.InitializeLeaderState(followers, lastLog) =>
        stateRef.update {
          case l: Leader => l.initializeIndices(followers, lastLog)
          case s => s
        }
      case Eff.UpdateFollowerIndex(followerId, matchIdx, nextIdx) =>
        stateRef.update {
          case l: Leader => l.withMatchIndex(followerId, matchIdx).withNextIndex(followerId, nextIdx)
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
        case s => s
      }
    yield ()
  
  private def applyCommittedEntries(upToIndex: Long): F[Unit] =
    // Apply all entries up to upToIndex to the state machine
    // This is a simplified version - should track last applied
    Async[F].unit // TODO: implement proper entry application
