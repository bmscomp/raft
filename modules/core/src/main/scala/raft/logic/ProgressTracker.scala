package raft.logic

import raft.state.{ClusterConfig, NodeId}

/** Bounded window of in-flight `AppendEntries` `lastIndex` values for a single
  * follower in `Replicate` state (modeled after `tikv/raft-rs`'s `Inflights`).
  */
final case class Inflights(
    capacity: Int,
    buffer: Vector[Long] = Vector.empty
):
  require(capacity > 0, s"Inflights capacity must be > 0, got $capacity")

  def count: Int = buffer.size
  def isFull: Boolean = buffer.size >= capacity
  def isEmpty: Boolean = buffer.isEmpty

  /** Record a newly sent `AppendEntries` batch ending at `toIndex`. */
  def add(toIndex: Long): Inflights =
    if isFull then this else copy(buffer = buffer :+ toIndex)

  /** Release all in-flight batches with `lastIndex <= toIndex` on ACK. */
  def freeTo(toIndex: Long): Inflights =
    copy(buffer = buffer.dropWhile(_ <= toIndex))

  /** Release the oldest in-flight batch so probing/retry can resume. */
  def freeFirstOne: Inflights =
    copy(buffer = buffer.drop(1))

  /** Clear all in-flight tracking (e.g., on transition to `Probe`/`Snapshot`).
    */
  def reset: Inflights =
    copy(buffer = Vector.empty)

/** Per-follower replication flow-control state (`tikv/raft-rs`
  * `ProgressState`).
  */
enum ProgressState:
  /** Follower's match point is uncertain; send at most 1 probe per interval. */
  case Probe

  /** Follower is caught up; optimistically advance `nextIdx` up to `Inflights`.
    */
  case Replicate

  /** Follower requires a snapshot up to `pendingSnapshotIndex`; pause log RPCs.
    */
  case Snapshot(pendingSnapshotIndex: Long)

/** Per-peer replication tracker (`tikv/raft-rs` `Progress`). */
final case class Progress(
    matched: Long = 0L,
    nextIdx: Long = 1L,
    state: ProgressState = ProgressState.Probe,
    probeSent: Boolean = false,
    recentActive: Boolean = false,
    isLearner: Boolean = false,
    inflights: Inflights = Inflights(10)
):
  /** Whether log replication to this peer is currently throttled/paused. */
  def isPaused: Boolean = state match
    case ProgressState.Probe       => probeSent
    case ProgressState.Replicate   => inflights.isFull
    case ProgressState.Snapshot(_) => true

  def becomeProbe(): Progress = state match
    case ProgressState.Snapshot(pendingSnap) =>
      copy(
        state = ProgressState.Probe,
        nextIdx = math.max(matched + 1L, pendingSnap + 1L),
        probeSent = false,
        inflights = inflights.reset
      )
    case _ =>
      copy(
        state = ProgressState.Probe,
        nextIdx = matched + 1L,
        probeSent = false,
        inflights = inflights.reset
      )

  def becomeReplicate(): Progress =
    copy(
      state = ProgressState.Replicate,
      nextIdx = matched + 1L,
      probeSent = false,
      inflights = inflights.reset
    )

  def becomeSnapshot(snapshotIndex: Long): Progress =
    copy(
      state = ProgressState.Snapshot(snapshotIndex),
      probeSent = false,
      inflights = inflights.reset
    )

  /** Advance `matched` (and `nextIdx` if needed) monotonically on follower ACK.
    *
    * @return
    *   `(updatedProgress, didAdvanceMatched)`
    */
  def maybeUpdate(n: Long): (Progress, Boolean) =
    val updatedNext = if nextIdx < n + 1L then n + 1L else nextIdx
    if matched < n then
      val p = copy(
        matched = n,
        nextIdx = updatedNext,
        probeSent = false,
        inflights = inflights.freeTo(n)
      )
      (p, true)
    else (copy(nextIdx = updatedNext, probeSent = false), false)

  /** Optimistically advance `nextIdx` when pipelining in `Replicate` mode. */
  def optimisticUpdate(lastSentIndex: Long): Progress = state match
    case ProgressState.Replicate =>
      copy(
        nextIdx = lastSentIndex + 1L,
        inflights = inflights.add(lastSentIndex)
      )
    case ProgressState.Probe =>
      copy(probeSent = true)
    case ProgressState.Snapshot(_) =>
      this

  /** Backtrack `nextIdx` on `AppendEntries` rejection using §5.3 conflict
    * hints.
    */
  def maybeDecrTo(
      rejectedIndex: Long,
      conflictIndex: Option[Long] = None,
      conflictTerm: Option[Long] = None,
      findLastIndexOfTerm: Long => Option[Long] = _ => None
  ): (Progress, Boolean) =
    def computeBacktrackedNext: Long =
      val hinted = (conflictTerm, conflictIndex) match
        case (Some(cTerm), Some(cIdx)) if cTerm > 0L =>
          findLastIndexOfTerm(cTerm) match
            case Some(lastIdxInLeader) => lastIdxInLeader + 1L
            case None                  => cIdx
        case (_, Some(cIdx)) => cIdx
        case _               => nextIdx - 1L
      math.max(1L, math.max(matched + 1L, hinted))

    state match
      case ProgressState.Replicate =>
        if rejectedIndex <= matched then (this, false)
        else
          (
            copy(
              state = ProgressState.Probe,
              nextIdx = matched + 1L,
              probeSent = false,
              inflights = inflights.reset
            ),
            true
          )
      case ProgressState.Probe =>
        if rejectedIndex != nextIdx - 1L && rejectedIndex > 0L && rejectedIndex < matched
        then (this, false)
        else
          val newNext = math.min(nextIdx - 1L, computeBacktrackedNext)
          (
            copy(
              nextIdx = math.max(1L, math.max(matched + 1L, newNext)),
              probeSent = false
            ),
            true
          )
      case ProgressState.Snapshot(_) =>
        (this, false)

/** Outcome of tallying votes across single or Joint Consensus configurations.
  */
enum VoteResult:
  case Won
  case Lost
  case Pending

/** Pure, immutable cluster-wide quorum, election, and replication progress
  * tracker inspired by `tikv/raft-rs`'s `ProgressTracker`.
  */
final case class ProgressTracker(
    config: ClusterConfig,
    progress: Map[NodeId, Progress],
    votes: Map[NodeId, Boolean] = Map.empty,
    maxInflight: Int = 10
):
  /** Record a vote (`granted = true` or `false`) from `voterId`. */
  def recordVote(voterId: NodeId, granted: Boolean): ProgressTracker =
    if votes.contains(voterId) then this
    else copy(votes = votes.updated(voterId, granted))

  /** Reset recorded election votes for a new campaign. */
  def resetVotes: ProgressTracker = copy(votes = Map.empty)

  /** Tally recorded votes under single-config or Joint Consensus (`C_old,new`).
    */
  def tallyVotes: VoteResult =
    def tallySingle(voterSet: Set[NodeId]): VoteResult =
      if voterSet.isEmpty then VoteResult.Won
      else
        val maj = (voterSet.size / 2) + 1
        val (yes, no) = voterSet.foldLeft((0, 0)) { case ((y, n), id) =>
          votes.get(id) match
            case Some(true)  => (y + 1, n)
            case Some(false) => (y, n + 1)
            case None        => (y, n)
        }
        if yes >= maj then VoteResult.Won
        else if (voterSet.size - no) < maj then VoteResult.Lost
        else VoteResult.Pending

    val newOutcome = tallySingle(config.newVoters)
    config.oldVoters match
      case None      => newOutcome
      case Some(old) =>
        val oldOutcome = tallySingle(old)
        (oldOutcome, newOutcome) match
          case (VoteResult.Won, VoteResult.Won)            => VoteResult.Won
          case (VoteResult.Lost, _) | (_, VoteResult.Lost) => VoteResult.Lost
          case _                                           => VoteResult.Pending

  /** Compute the highest `matched` index replicated on a valid quorum. */
  def maximalCommittedIndex: Long =
    def committedFor(voterSet: Set[NodeId]): Long =
      if voterSet.isEmpty then 0L
      else
        val matchedVals =
          voterSet.toSeq.map(id =>
            progress.get(id).map(_.matched).getOrElse(0L)
          )
        val sorted = matchedVals.sorted(Ordering[Long].reverse)
        sorted.lift(voterSet.size / 2).getOrElse(0L)

    val newCommitted = committedFor(config.newVoters)
    config.oldVoters match
      case None      => newCommitted
      case Some(old) => math.min(newCommitted, committedFor(old))

  /** True iff `localId` plus peers with `recentActive == true` form a quorum.
    */
  def quorumRecentlyActive(localId: NodeId): Boolean =
    val active = progress.collect {
      case (id, p) if id == localId || p.recentActive => id
    }.toSet + localId
    config.hasQuorum(active)

  /** Clear `recentActive` flags at the end of a `CheckQuorum` window. */
  def resetRecentActive(localId: NodeId): ProgressTracker =
    copy(progress = progress.map { case (id, p) =>
      id -> p.copy(recentActive = id == localId)
    })

  /** Update a single peer's [[Progress]], returning `(nextTracker, output)`. */
  def modifyProgress[A](
      id: NodeId,
      defaultOut: A
  )(fn: Progress => (Progress, A)): (ProgressTracker, A) =
    progress.get(id) match
      case Some(p) =>
        val (np, out) = fn(p)
        (copy(progress = progress.updated(id, np)), out)
      case None => (this, defaultOut)

  /** Update a single peer's [[Progress]]. */
  def updateProgress(id: NodeId, fn: Progress => Progress): ProgressTracker =
    progress.get(id) match
      case Some(p) => copy(progress = progress.updated(id, fn(p)))
      case None    => this

  /** Reconcile tracked peers when a new [[ClusterConfig]] takes effect. */
  def withConfig(
      newConfig: ClusterConfig,
      lastIndex: Long
  ): ProgressTracker =
    val updated = newConfig.allNodes.map { id =>
      val isLrn = newConfig.isLearner(id)
      val existing = progress.getOrElse(
        id,
        Progress(
          matched = 0L,
          nextIdx = math.max(1L, lastIndex + 1L),
          state = ProgressState.Probe,
          isLearner = isLrn,
          inflights = Inflights(maxInflight)
        )
      )
      id -> existing.copy(isLearner = isLrn)
    }.toMap
    copy(config = newConfig, progress = updated)

object ProgressTracker:
  /** Initialize a fresh [[ProgressTracker]] for a newly elected leader. */
  def forLeader(
      localId: NodeId,
      config: ClusterConfig,
      lastLogIndex: Long,
      maxInflight: Int = 10
  ): ProgressTracker =
    val entries = config.allNodes.map { id =>
      val isSelf = id == localId
      val p = Progress(
        matched = if isSelf then lastLogIndex else 0L,
        nextIdx = lastLogIndex + 1L,
        state = if isSelf then ProgressState.Replicate else ProgressState.Probe,
        recentActive = isSelf,
        isLearner = config.isLearner(id),
        inflights = Inflights(maxInflight)
      )
      id -> p
    }.toMap
    ProgressTracker(config, entries, Map.empty, maxInflight)
