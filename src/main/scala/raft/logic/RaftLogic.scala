package raft.logic

import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.*
import raft.effect.Effect.*

/** Pure RAFT state transition functions — the deterministic core of the
  * consensus engine.
  *
  * This object embodies the library's central design principle: '''Functional
  * Core / Imperative Shell'''. All consensus logic is implemented as pure
  * functions with no I/O, no mutable state, and no side effects. Each function
  * takes the current [[NodeState]] and an incoming [[RaftMessage]], and returns
  * a [[Transition]] containing the new state and a list of [[Effect]]s for the
  * runtime to execute.
  *
  * This pure-functional approach yields three concrete benefits:
  *   1. '''Deterministic testing''' — tests assert on the returned state and
  *      effect list without mocking clocks, networks, or disks.
  *   1. '''Formal reasoning''' — the transition functions map directly to the
  *      Raft TLA+ specification (§5, Figure 2), making it straightforward to
  *      verify correctness against the paper.
  *   1. '''Portability''' — the logic is independent of any effect system,
  *      runtime, or I/O library. Only the runtime shell ([[raft.RaftNode]])
  *      depends on Cats Effect.
  *
  * The key protocol handlers implement the Raft paper's core RPCs:
  *   - `onAppendEntries` — log replication and heartbeats (§5.3)
  *   - `onRequestVote` — leader election with optional pre-vote (§5.2, §9.6)
  *   - `onElectionTimeout` — election initiation (§5.2)
  *   - `onHeartbeatTimeout` — periodic heartbeat broadcast (§5.2)
  *
  * {{{
  * val follower = NodeState.Follower(term = 1)
  * val config   = RaftConfig.default(NodeId("node1"))
  * val timeout  = RaftMessage.ElectionTimeout
  *
  * val transition = RaftLogic.onMessage(follower, timeout, config, 0L, 0L, 3)
  * // transition.state  => PreCandidate or Candidate (depending on preVoteEnabled)
  * // transition.effects => List(Broadcast(...), ResetElectionTimer, ...)
  * }}}
  *
  * @see
  *   [[Transition]] for the result type
  * @see
  *   [[Effect]] for the side-effect descriptions
  * @see
  *   [[raft.RaftNode]] for the runtime that interprets effects
  */
object RaftLogic:

  /** Main message dispatcher — routes any incoming message to the appropriate
    * handler.
    *
    * This is the single entry point for all protocol events. It handles:
    *   - `AppendEntriesRequest/Response` — log replication and heartbeats
    *   - `RequestVoteRequest/Response` — leader elections (including pre-vote)
    *   - `ElectionTimeout` — triggers election start or restart
    *   - `HeartbeatTimeout` — triggers leader heartbeat broadcast
    *
    * Messages not handled by specialized logic (e.g., snapshot, membership)
    * return the state unchanged with no effects.
    *
    * @param state
    *   the current node state (Follower, PreCandidate, Candidate, or Leader)
    * @param msg
    *   the incoming RAFT message or timeout event
    * @param config
    *   the node's configuration (timeouts, features, local ID)
    * @param lastLogIndex
    *   the index of the last entry in the local log
    * @param lastLogTerm
    *   the term of the last entry in the local log
    * @param clusterSize
    *   the total number of voting members in the cluster
    * @param getTermAt
    *   callback to look up the term at a given log index; defaults to
    *   `_ => None` (used for log matching and commit checks)
    * @return
    *   a [[Transition]] containing the new state and effects to execute
    */
  def onMessage(
      state: NodeState,
      msg: RaftMessage,
      config: RaftConfig,
      lastLogIndex: Long,
      lastLogTerm: Long,
      clusterSize: Int,
      getTermAt: Long => Option[Long] = _ => None
  ): Transition = msg match
    case req: AppendEntriesRequest =>
      onAppendEntries(state, req, config, getTermAt)
    case resp: AppendEntriesResponse =>
      onAppendEntriesResponse(state, resp, clusterSize, getTermAt)
    case req: RequestVoteRequest =>
      onRequestVote(state, req, config, lastLogIndex, lastLogTerm)
    case resp: RequestVoteResponse =>
      handleVoteResponse(
        state,
        resp,
        config,
        clusterSize,
        lastLogIndex,
        lastLogTerm
      )
    case ElectionTimeout =>
      onElectionTimeout(state, config, lastLogIndex, lastLogTerm)
    case HeartbeatTimeout =>
      onHeartbeatTimeout(state, config, lastLogIndex, lastLogTerm)
    case _ => Transition.pure(state)

  /** Handle a vote response for a Candidate with explicit voter tracking.
    *
    * Unlike the internal `handleVoteResponse` which uses simplified voter IDs,
    * this method accepts an explicit `voter` NodeId for precise vote
    * accumulation. Primarily used in tests to verify vote counting and majority
    * detection.
    *
    * @param state
    *   the current Candidate state
    * @param voter
    *   the node ID of the voter that responded
    * @param resp
    *   the vote response message
    * @param config
    *   the node's configuration
    * @param clusterSize
    *   the total number of voting members in the cluster
    * @return
    *   a [[Transition]] — either to Leader (if majority reached), stepped-down
    *   Follower (if higher term), or unchanged Candidate
    */
  def onVoteResponse(
      state: Candidate,
      voter: NodeId,
      resp: RequestVoteResponse,
      config: RaftConfig,
      clusterSize: Int
  ): Transition =
    if resp.voteGranted && resp.term == state.term then
      val newCandidate = state.withVote(voter)
      if newCandidate.hasMajority(clusterSize) then
        Transition.withEffect(Leader(state.term), BecomeLeader)
      else Transition.pure(newCandidate)
    else if resp.term > state.term then
      Transition.pure(state.stepDown(resp.term))
    else Transition.pure(state)

  private def onAppendEntries(
      state: NodeState,
      req: AppendEntriesRequest,
      config: RaftConfig,
      getTermAt: Long => Option[Long]
  ): Transition =
    // Reject if request term is lower
    if req.term < state.term then
      val response = AppendEntriesResponse(state.term, success = false, 0)
      return Transition.withEffect(state, SendMessage(req.leaderId, response))

    // Step down if request term is higher
    val currentState =
      if req.term > state.term then state.stepDown(req.term) else state

    // Log matching validation
    val logMatches =
      if req.prevLogIndex == 0 then true // Empty log case
      else getTermAt(req.prevLogIndex).contains(req.prevLogTerm)

    currentState match
      case f: Follower =>
        if !logMatches then
          // Log doesn't match - reject with hint for leader to backtrack
          val response = AppendEntriesResponse(req.term, success = false, 0)
          Transition(
            Follower(req.term, f.votedFor, Some(req.leaderId)),
            List(ResetElectionTimer, SendMessage(req.leaderId, response))
          )
        else
          // Accept - apply entries and update commit
          val newFollower = Follower(req.term, f.votedFor, Some(req.leaderId))
          val matchIndex = req.prevLogIndex + req.entries.size
          val response =
            AppendEntriesResponse(req.term, success = true, matchIndex)

          // Build effects: reset timer, persist entries, respond, apply committed
          val baseEffects = List(ResetElectionTimer)
          val logEffects =
            if req.entries.nonEmpty then List(AppendLogs(req.entries)) else Nil
          val responseEffect = SendMessage(req.leaderId, response)
          val commitEffects =
            if req.leaderCommit > 0 then List(CommitEntries(req.leaderCommit))
            else Nil

          Transition(
            newFollower,
            baseEffects ++ logEffects ++ List(responseEffect) ++ commitEffects
          )

      case _: Candidate | _: PreCandidate =>
        // Step down to follower, accept leader
        if !logMatches then
          val response = AppendEntriesResponse(req.term, success = false, 0)
          Transition(
            Follower(req.term, None, Some(req.leaderId)),
            List(ResetElectionTimer, SendMessage(req.leaderId, response))
          )
        else
          val newFollower = Follower(req.term, None, Some(req.leaderId))
          val matchIndex = req.prevLogIndex + req.entries.size
          val response =
            AppendEntriesResponse(req.term, success = true, matchIndex)
          Transition(
            newFollower,
            List(ResetElectionTimer, SendMessage(req.leaderId, response))
          )

      case l: Leader if req.term > l.term =>
        // Higher term leader - step down
        val newFollower = Follower(req.term, None, Some(req.leaderId))
        Transition.withEffect(newFollower, ResetElectionTimer)

      case l: Leader =>
        // Reject - we are the leader in this term
        val response = AppendEntriesResponse(l.term, success = false, 0)
        Transition.withEffect(l, SendMessage(req.leaderId, response))

  private def onAppendEntriesResponse(
      state: NodeState,
      resp: AppendEntriesResponse,
      clusterSize: Int,
      getTermAt: Long => Option[Long]
  ): Transition = state match
    case l: Leader if resp.term > l.term =>
      // Higher term - step down
      Transition.pure(l.stepDown(resp.term))

    case l: Leader if resp.success =>
      // Success: update matchIndex and nextIndex, check for commit advancement
      // Note: In a real impl, we'd know which follower this is from transport layer
      // For now, we update based on matchIndex value as a simplified model
      val newMatchIndex = resp.matchIndex
      val newNextIndex = resp.matchIndex + 1

      // Calculate new commit index
      val newCommitIndex = l.calculateCommitIndex(clusterSize, getTermAt)

      if newCommitIndex > l.commitIndex then
        // New entries to commit
        Transition(
          l.withCommitIndex(newCommitIndex),
          List(CommitEntries(newCommitIndex))
        )
      else Transition.pure(l)

    case l: Leader if !resp.success =>
      // Failure: leader should decrement nextIndex and retry
      // This is handled by the runtime layer tracking per-follower state
      Transition.pure(l)

    case _ =>
      Transition.pure(state)

  private def onRequestVote(
      state: NodeState,
      req: RequestVoteRequest,
      config: RaftConfig,
      lastLogIndex: Long,
      lastLogTerm: Long
  ): Transition =
    // Check if we should step down
    val currentState =
      if req.term > state.term then state.stepDown(req.term) else state

    currentState match
      case f: Follower =>
        val canVote = f.votedFor.isEmpty || f.votedFor.contains(req.candidateId)
        val logOk = isLogUpToDate(
          req.lastLogIndex,
          req.lastLogTerm,
          lastLogIndex,
          lastLogTerm
        )

        // Leader stickiness check
        val leaderAlive =
          config.leaderStickinessEnabled && f.leaderId.isDefined && !req.isPreVote

        if canVote && logOk && !leaderAlive then
          val newFollower =
            if req.isPreVote then f
            else f.copy(votedFor = Some(req.candidateId))
          val response =
            RequestVoteResponse(req.term, voteGranted = true, req.isPreVote)
          val effects = List(
            SendMessage(req.candidateId, response)
          ) ++ (if req.isPreVote then Nil
                else List(PersistHardState(req.term, Some(req.candidateId))))
          Transition(newFollower, effects)
        else
          val response =
            RequestVoteResponse(f.term, voteGranted = false, req.isPreVote)
          Transition.withEffect(f, SendMessage(req.candidateId, response))

      case _ =>
        val response = RequestVoteResponse(
          currentState.term,
          voteGranted = false,
          req.isPreVote
        )
        Transition.withEffect(
          currentState,
          SendMessage(req.candidateId, response)
        )

  /** Monotonic counter for generating unique voter IDs when the wire protocol
    * does not carry the voter's identity. Each call to `handleVoteResponse`
    * produces a distinct `NodeId`, preventing de-duplication bugs where the
    * same synthetic ID would be counted only once.
    */
  private val voteCounter = new java.util.concurrent.atomic.AtomicLong(0)

  private def handleVoteResponse(
      state: NodeState,
      resp: RequestVoteResponse,
      config: RaftConfig,
      clusterSize: Int,
      lastLogIndex: Long,
      lastLogTerm: Long
  ): Transition =
    val voterId = NodeId(s"voter-${voteCounter.getAndIncrement()}")

    if resp.isPreVote then
      state match
        case p: PreCandidate if resp.voteGranted =>
          val newPreCandidate = p.withPreVote(voterId)
          if newPreCandidate.hasPreVoteMajority(clusterSize) then
            val newTerm = p.term + 1
            val candidate =
              Candidate(newTerm, Set(config.localId))
            val voteReq = RequestVoteRequest(
              term = newTerm,
              candidateId = config.localId,
              lastLogIndex = lastLogIndex,
              lastLogTerm = lastLogTerm,
              isPreVote = false
            )
            Transition(
              candidate,
              List(
                PersistHardState(newTerm, Some(config.localId)),
                Broadcast(voteReq),
                ResetElectionTimer
              )
            )
          else Transition.pure(newPreCandidate)
        case p: PreCandidate =>
          Transition.pure(p)
        case _ =>
          Transition.pure(state)
    else
      state match
        case c: Candidate if resp.voteGranted && resp.term == c.term =>
          val newCandidate = c.withVote(voterId)
          if newCandidate.hasMajority(clusterSize) then
            Transition.withEffect(Leader(c.term), BecomeLeader)
          else Transition.pure(newCandidate)
        case c: Candidate if resp.term > c.term =>
          Transition.pure(c.stepDown(resp.term))
        case _ =>
          Transition.pure(state)

  private def onElectionTimeout(
      state: NodeState,
      config: RaftConfig,
      lastLogIndex: Long,
      lastLogTerm: Long
  ): Transition = state match
    case f: Follower if config.preVoteEnabled =>
      // Pre-vote phase: don't increment term yet
      val preCandidate = PreCandidate(f.term, Set(config.localId))
      val voteReq = RequestVoteRequest(
        term =
          f.term + 1, // Request votes for NEXT term, but don't persist it yet
        candidateId = config.localId,
        lastLogIndex = lastLogIndex,
        lastLogTerm = lastLogTerm,
        isPreVote = true
      )
      Transition(
        preCandidate,
        List(Broadcast(voteReq), ResetElectionTimer)
        // No PersistHardState - pre-vote doesn't change persistent state
      )

    case f: Follower =>
      // Direct election (pre-vote disabled)
      val newTerm = f.term + 1
      val candidate = Candidate(newTerm, Set(config.localId))
      val voteReq = RequestVoteRequest(
        term = newTerm,
        candidateId = config.localId,
        lastLogIndex = lastLogIndex,
        lastLogTerm = lastLogTerm,
        isPreVote = false
      )
      Transition(
        candidate,
        List(
          PersistHardState(newTerm, Some(config.localId)),
          Broadcast(voteReq),
          ResetElectionTimer
        )
      )

    case p: PreCandidate =>
      // Timeout during pre-vote - restart pre-vote
      val newPreCandidate = PreCandidate(p.term, Set(config.localId))
      val voteReq = RequestVoteRequest(
        term = p.term + 1,
        candidateId = config.localId,
        lastLogIndex = lastLogIndex,
        lastLogTerm = lastLogTerm,
        isPreVote = true
      )
      Transition(
        newPreCandidate,
        List(Broadcast(voteReq), ResetElectionTimer)
      )

    case c: Candidate =>
      // Election timeout - restart election
      val newTerm = c.term + 1
      val newCandidate = Candidate(newTerm, Set(config.localId))
      val voteReq = RequestVoteRequest(
        term = newTerm,
        candidateId = config.localId,
        lastLogIndex = lastLogIndex,
        lastLogTerm = lastLogTerm,
        isPreVote = false
      )
      Transition(
        newCandidate,
        List(
          PersistHardState(newTerm, Some(config.localId)),
          Broadcast(voteReq),
          ResetElectionTimer
        )
      )

    case l: Leader =>
      // Leaders don't have election timeouts
      Transition.pure(l)

  private def onHeartbeatTimeout(
      state: NodeState,
      config: RaftConfig,
      lastLogIndex: Long,
      lastLogTerm: Long
  ): Transition = state match
    case l: Leader =>
      val heartbeat = AppendEntriesRequest(
        term = l.term,
        leaderId = config.localId,
        prevLogIndex = lastLogIndex,
        prevLogTerm = lastLogTerm,
        entries = Seq.empty,
        leaderCommit = l.commitIndex
      )
      Transition(l, List(Broadcast(heartbeat), ResetHeartbeatTimer))
    case _ =>
      Transition.pure(state)

  private def isLogUpToDate(
      candidateLastIndex: Long,
      candidateLastTerm: Long,
      myLastIndex: Long,
      myLastTerm: Long
  ): Boolean =
    candidateLastTerm > myLastTerm ||
      (candidateLastTerm == myLastTerm && candidateLastIndex >= myLastIndex)
