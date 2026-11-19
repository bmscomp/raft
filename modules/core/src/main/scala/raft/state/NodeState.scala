package raft.state

import java.nio.charset.StandardCharsets
import scala.collection.immutable.Set as ImmutableSet

/** Unique, opaque identifier for a RAFT node in the cluster. */
opaque type NodeId = String

object NodeId:
  /** Maximum UTF-8 byte length supported by on-disk `hard_state.bin`. */
  val MaxUtf8Bytes: Int = 256

  def apply(id: String): NodeId = id

  /** Pure, stack-trace-free smart constructor validating non-emptiness, absence
    * of control characters, and the 256-byte UTF-8 persistence bound.
    */
  def validate(raw: String): Validated[ConfigError, NodeId] =
    if raw.isEmpty then Validated.invalid(ConfigError.EmptyNodeId)
    else
      val hasControl = raw.exists(c => c < 0x20 || c == 0x7f)
      val utf8Len = raw.getBytes(StandardCharsets.UTF_8).length
      Validated
        .mapN(
          Validated.check(
            !hasControl,
            ConfigError.ControlCharacterInNodeId(raw)
          ),
          Validated.check(
            utf8Len <= MaxUtf8Bytes,
            ConfigError.OversizedNodeId(utf8Len, MaxUtf8Bytes)
          )
        )((_, _) => raw)

  given Ordering[NodeId] = Ordering.String

  extension (id: NodeId) def value: String = id

/** Pure, immutable consensus role state machine (§5.1).
  *
  * Every role carries `commitIndex` so a node's committed watermark remains
  * monotonically non-decreasing across Follower updates, elections, leadership,
  * and higher-term step-downs.
  */
enum NodeState:
  /** Passive follower state (§5.2). */
  case Follower(
      term: Long,
      votedFor: Option[NodeId] = None,
      leaderId: Option[NodeId] = None,
      commitIndex: Long = 0L
  )

  /** Speculative Pre-Vote state (§9.6) — never increments persistent term. */
  case PreCandidate(
      term: Long,
      preVotesReceived: ImmutableSet[NodeId] = ImmutableSet.empty,
      commitIndex: Long = 0L
  )

  /** Active election candidate state (§5.2). */
  case Candidate(
      term: Long,
      votesReceived: ImmutableSet[NodeId] = ImmutableSet.empty,
      commitIndex: Long = 0L
  )

  /** Authoritative cluster leader state (§5.3, §6.4, §3.10). */
  case Leader(
      term: Long,
      nextIndex: Map[NodeId, Long] = Map.empty,
      matchIndex: Map[NodeId, Long] = Map.empty,
      commitIndex: Long = 0L,
      transferTarget: Option[NodeId] = None,
      pendingReads: Map[String, PendingRead] = Map.empty,
      leaseUntil: Long = 0L,
      recentlyActive: ImmutableSet[NodeId] = ImmutableSet.empty
  )

object NodeState:
  extension (state: NodeState)
    def term: Long = state match
      case f: Follower     => f.term
      case p: PreCandidate => p.term
      case c: Candidate    => c.term
      case l: Leader       => l.term

    def typedTerm: Term = Term.unsafeFrom(state.term)

    def commitIndex: Long = state match
      case f: Follower     => f.commitIndex
      case p: PreCandidate => p.commitIndex
      case c: Candidate    => c.commitIndex
      case l: Leader       => l.commitIndex

    def typedCommitIndex: LogIndex = LogIndex.unsafeFrom(state.commitIndex)

    def isLeader: Boolean = state match
      case _: Leader => true
      case _         => false

    def isFollower: Boolean = state match
      case _: Follower => true
      case _           => false

    def leaderHint(selfId: NodeId): Option[NodeId] = state match
      case _: Leader   => Some(selfId)
      case f: Follower => f.leaderId
      case _           => None

    def stepDown(newTerm: Long): Follower =
      Follower(newTerm, None, None, state.commitIndex)

  extension (state: Follower)
    def withFollowerCommitIndex(index: Long): Follower =
      if index > state.commitIndex then state.copy(commitIndex = index)
      else state

  extension (state: PreCandidate)
    def withPreVote(voterId: NodeId): PreCandidate =
      state.copy(preVotesReceived = state.preVotesReceived + voterId)

    def hasPreVoteMajority(clusterSize: Int): Boolean =
      state.preVotesReceived.size > clusterSize / 2

    def hasPreVoteQuorum(cluster: ClusterConfig): Boolean =
      cluster.hasQuorum(state.preVotesReceived)

  extension (state: Candidate)
    def withVote(voterId: NodeId): Candidate =
      state.copy(votesReceived = state.votesReceived + voterId)

    def hasMajority(clusterSize: Int): Boolean =
      state.votesReceived.size > clusterSize / 2

    def hasQuorum(cluster: ClusterConfig): Boolean =
      cluster.hasQuorum(state.votesReceived)

  extension (state: Leader)
    def withNextIndex(followerId: NodeId, index: Long): Leader =
      state.copy(nextIndex = state.nextIndex.updated(followerId, index))

    def withMatchIndex(followerId: NodeId, index: Long): Leader =
      state.copy(matchIndex = state.matchIndex.updated(followerId, index))

    def withCommitIndex(index: Long): Leader =
      state.copy(commitIndex = index)

    def initializeIndices(followers: Set[NodeId], lastLogIndex: Long): Leader =
      val next = followers.map(_ -> (lastLogIndex + 1)).toMap
      val matched = followers.map(_ -> 0L).toMap
      state.copy(nextIndex = next, matchIndex = matched)

    /** Compute the highest commit index replicated on a majority of voters
      * whose entry term equals `state.term` (Raft §5.4.2).
      */
    def calculateCommitIndex(
        clusterSize: Int,
        getTermAt: Long => Option[Long],
        leaderLastIndex: Long = -1L
    ): Long =
      if clusterSize <= 0 then state.commitIndex
      else if clusterSize == 1 then
        val effectiveSelf =
          if leaderLastIndex >= 0L then leaderLastIndex
          else state.matchIndex.values.maxOption.getOrElse(state.commitIndex)
        (effectiveSelf to (state.commitIndex + 1L) by -1L)
          .find(idx => getTermAt(idx).contains(state.term))
          .getOrElse(state.commitIndex)
      else
        val inferredSelf =
          if leaderLastIndex >= 0L then leaderLastIndex
          else state.matchIndex.values.maxOption.getOrElse(state.commitIndex)
        val followerIndices = state.matchIndex.values.toSeq
        val allIndices =
          if followerIndices.size >= clusterSize then followerIndices
          else followerIndices :+ inferredSelf
        val sorted = allIndices.sorted(Ordering[Long].reverse)
        val majorityPos = clusterSize / 2
        val quorumReplicated =
          sorted.lift(majorityPos).getOrElse(state.commitIndex)
        (quorumReplicated to (state.commitIndex + 1L) by -1L)
          .find(idx => getTermAt(idx).contains(state.term))
          .getOrElse(state.commitIndex)

    /** Joint-consensus-aware commit calculation using [[ClusterConfig]]. */
    def calculateCommitIndexForCluster(
        localId: NodeId,
        cluster: ClusterConfig,
        leaderLastIndex: Long,
        getTermAt: Long => Option[Long]
    ): Long =
      def medianFor(voterSet: Set[NodeId]): Long =
        if voterSet.isEmpty then state.commitIndex
        else
          val indices = voterSet.toSeq.map { id =>
            if id == localId then leaderLastIndex
            else state.matchIndex.getOrElse(id, 0L)
          }
          val sorted = indices.sorted(Ordering[Long].reverse)
          val majPos = voterSet.size / 2
          sorted.lift(majPos).getOrElse(state.commitIndex)

      val newCommit = medianFor(cluster.newVoters)
      val quorumIndex = cluster.oldVoters match
        case None      => newCommit
        case Some(old) => math.min(newCommit, medianFor(old))

      (quorumIndex to (state.commitIndex + 1L) by -1L)
        .find(idx => getTermAt(idx).contains(state.term))
        .getOrElse(state.commitIndex)
