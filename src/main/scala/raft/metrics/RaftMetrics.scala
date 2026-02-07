package raft.metrics

import raft.state.NodeId

/** Observability hook for RAFT protocol events.
  *
  * Implement this trait to collect metrics from the RAFT consensus engine. Each
  * callback corresponds to a significant protocol event (elections,
  * replication, commits, etc.). All methods receive the local node's ID for
  * correlation in multi-node deployments.
  *
  * A no-op implementation is provided via [[RaftMetrics.noop]] for environments
  * where metrics collection is not needed.
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @see
  *   [[RaftMetrics.noop]] for a no-op implementation
  */
trait RaftMetrics[F[_]]:

  /** Called when this node becomes the leader.
    *
    * @param nodeId
    *   this node's identifier
    * @param term
    *   the term in which leadership was won
    */
  def onBecomeLeader(nodeId: NodeId, term: Long): F[Unit]

  /** Called when this node steps down from being leader.
    *
    * @param nodeId
    *   this node's identifier
    * @param term
    *   the term in which the step-down occurred
    */
  def onStepDown(nodeId: NodeId, term: Long): F[Unit]

  /** Called when this node starts an election (or pre-election).
    *
    * @param nodeId
    *   this node's identifier
    * @param term
    *   the term for the new election
    */
  def onElectionStarted(nodeId: NodeId, term: Long): F[Unit]

  /** Called when this node grants a vote to a candidate.
    *
    * @param nodeId
    *   this node's identifier
    * @param to
    *   the candidate that received the vote
    * @param term
    *   the term of the election
    */
  def onVoteGranted(nodeId: NodeId, to: NodeId, term: Long): F[Unit]

  /** Called when log entries are appended to storage.
    *
    * @param nodeId
    *   this node's identifier
    * @param count
    *   the number of entries appended
    */
  def onEntriesAppended(nodeId: NodeId, count: Int): F[Unit]

  /** Called when entries are committed (replicated to a majority).
    *
    * @param nodeId
    *   this node's identifier
    * @param commitIndex
    *   the new commit index after advancement
    */
  def onEntriesCommitted(nodeId: NodeId, commitIndex: Long): F[Unit]

  /** Called when entries are applied to the state machine.
    *
    * @param nodeId
    *   this node's identifier
    * @param lastApplied
    *   the index of the last applied entry
    */
  def onEntriesApplied(nodeId: NodeId, lastApplied: Long): F[Unit]

  /** Called when a snapshot is taken for log compaction.
    *
    * @param nodeId
    *   this node's identifier
    * @param lastIndex
    *   the last log index included in the snapshot
    */
  def onSnapshotTaken(nodeId: NodeId, lastIndex: Long): F[Unit]

  /** Called when the leader sends heartbeats to followers.
    *
    * @param nodeId
    *   this node's identifier
    * @param followerCount
    *   the number of followers that heartbeats were sent to
    */
  def onHeartbeatSent(nodeId: NodeId, followerCount: Int): F[Unit]

  /** Called when replication to a follower fails.
    *
    * @param nodeId
    *   this node's identifier
    * @param followerId
    *   the follower that replication failed for
    */
  def onReplicationFailure(nodeId: NodeId, followerId: NodeId): F[Unit]

/** Companion for [[RaftMetrics]] providing built-in implementations. */
object RaftMetrics:

  /** No-op metrics implementation that discards all events.
    *
    * Useful for testing or when metrics collection is not required.
    *
    * @tparam F
    *   the effect type (requires `Applicative`)
    * @return
    *   a metrics instance where every callback returns `F.unit`
    */
  def noop[F[_]](using F: cats.Applicative[F]): RaftMetrics[F] =
    new RaftMetrics[F]:
      def onBecomeLeader(nodeId: NodeId, term: Long): F[Unit] = F.unit
      def onStepDown(nodeId: NodeId, term: Long): F[Unit] = F.unit
      def onElectionStarted(nodeId: NodeId, term: Long): F[Unit] = F.unit
      def onVoteGranted(nodeId: NodeId, to: NodeId, term: Long): F[Unit] =
        F.unit
      def onEntriesAppended(nodeId: NodeId, count: Int): F[Unit] = F.unit
      def onEntriesCommitted(nodeId: NodeId, commitIndex: Long): F[Unit] =
        F.unit
      def onEntriesApplied(nodeId: NodeId, lastApplied: Long): F[Unit] = F.unit
      def onSnapshotTaken(nodeId: NodeId, lastIndex: Long): F[Unit] = F.unit
      def onHeartbeatSent(nodeId: NodeId, followerCount: Int): F[Unit] = F.unit
      def onReplicationFailure(nodeId: NodeId, followerId: NodeId): F[Unit] =
        F.unit
