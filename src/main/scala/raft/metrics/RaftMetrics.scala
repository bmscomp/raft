package raft.metrics

import raft.state.NodeId

/**
 * Metrics trait for observability.
 * 
 * Implement this trait to collect RAFT metrics.
 * All methods have default no-op implementations.
 */
trait RaftMetrics[F[_]]:
  
  /** Called when node becomes leader */
  def onBecomeLeader(nodeId: NodeId, term: Long): F[Unit]
  
  /** Called when node steps down from leader */
  def onStepDown(nodeId: NodeId, term: Long): F[Unit]
  
  /** Called when an election starts */
  def onElectionStarted(nodeId: NodeId, term: Long): F[Unit]
  
  /** Called when a vote is granted */
  def onVoteGranted(nodeId: NodeId, to: NodeId, term: Long): F[Unit]
  
  /** Called when entries are appended to log */
  def onEntriesAppended(nodeId: NodeId, count: Int): F[Unit]
  
  /** Called when entries are committed */
  def onEntriesCommitted(nodeId: NodeId, commitIndex: Long): F[Unit]
  
  /** Called when entries are applied to state machine */
  def onEntriesApplied(nodeId: NodeId, lastApplied: Long): F[Unit]
  
  /** Called when a snapshot is taken */
  def onSnapshotTaken(nodeId: NodeId, lastIndex: Long): F[Unit]
  
  /** Called when a heartbeat is sent */
  def onHeartbeatSent(nodeId: NodeId, followerCount: Int): F[Unit]
  
  /** Called when replication to a follower fails */
  def onReplicationFailure(nodeId: NodeId, followerId: NodeId): F[Unit]

object RaftMetrics:
  
  /** No-op metrics implementation */
  def noop[F[_]](using F: cats.Applicative[F]): RaftMetrics[F] = new RaftMetrics[F]:
    def onBecomeLeader(nodeId: NodeId, term: Long): F[Unit] = F.unit
    def onStepDown(nodeId: NodeId, term: Long): F[Unit] = F.unit
    def onElectionStarted(nodeId: NodeId, term: Long): F[Unit] = F.unit
    def onVoteGranted(nodeId: NodeId, to: NodeId, term: Long): F[Unit] = F.unit
    def onEntriesAppended(nodeId: NodeId, count: Int): F[Unit] = F.unit
    def onEntriesCommitted(nodeId: NodeId, commitIndex: Long): F[Unit] = F.unit
    def onEntriesApplied(nodeId: NodeId, lastApplied: Long): F[Unit] = F.unit
    def onSnapshotTaken(nodeId: NodeId, lastIndex: Long): F[Unit] = F.unit
    def onHeartbeatSent(nodeId: NodeId, followerCount: Int): F[Unit] = F.unit
    def onReplicationFailure(nodeId: NodeId, followerId: NodeId): F[Unit] = F.unit
