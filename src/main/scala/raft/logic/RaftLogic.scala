package raft.logic

import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.*
import raft.effect.Effect.*

/** Pure RAFT state transition functions. No I/O - effects are data for runtime. */
object RaftLogic:
  
  /** Main message dispatcher. Routes to appropriate handler. */
  def onMessage(
    state: NodeState,
    msg: RaftMessage,
    config: RaftConfig,
    lastLogIndex: Long,
    lastLogTerm: Long,
    clusterSize: Int,
    getTermAt: Long => Option[Long] = _ => None
  ): Transition = msg match
    case req: AppendEntriesRequest   => onAppendEntries(state, req, config, getTermAt)
    case resp: AppendEntriesResponse => onAppendEntriesResponse(state, resp, clusterSize, getTermAt)
    case req: RequestVoteRequest     => onRequestVote(state, req, config, lastLogIndex, lastLogTerm)
    case resp: RequestVoteResponse   => handleVoteResponse(state, resp, clusterSize)
    case ElectionTimeout             => onElectionTimeout(state, config, lastLogIndex, lastLogTerm)
    case HeartbeatTimeout            => onHeartbeatTimeout(state, config, lastLogIndex)
    case _                           => Transition.pure(state)
  
  /** Handle vote response with voter tracking. Used for testing vote accumulation. */
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
      else
        Transition.pure(newCandidate)
    else if resp.term > state.term then
      Transition.pure(state.stepDown(resp.term))
    else
      Transition.pure(state)
  
  // === AppendEntries ===
  
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
    val currentState = if req.term > state.term then state.stepDown(req.term) else state
    
    // Log matching validation
    val logMatches = 
      if req.prevLogIndex == 0 then true  // Empty log case
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
          val response = AppendEntriesResponse(req.term, success = true, matchIndex)
          
          // Build effects: reset timer, persist entries, respond, apply committed
          val baseEffects = List(ResetElectionTimer)
          val logEffects = if req.entries.nonEmpty then List(AppendLogs(req.entries)) else Nil
          val responseEffect = SendMessage(req.leaderId, response)
          val commitEffects = if req.leaderCommit > 0 then List(CommitEntries(req.leaderCommit)) else Nil
          
          Transition(newFollower, baseEffects ++ logEffects ++ List(responseEffect) ++ commitEffects)
      
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
          val response = AppendEntriesResponse(req.term, success = true, matchIndex)
          Transition(newFollower, List(ResetElectionTimer, SendMessage(req.leaderId, response)))
      
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
      else
        Transition.pure(l)
    
    case l: Leader if !resp.success =>
      // Failure: leader should decrement nextIndex and retry
      // This is handled by the runtime layer tracking per-follower state
      Transition.pure(l)
    
    case _ =>
      Transition.pure(state)
  
  // === RequestVote ===
  
  private def onRequestVote(
    state: NodeState,
    req: RequestVoteRequest,
    config: RaftConfig,
    lastLogIndex: Long,
    lastLogTerm: Long
  ): Transition =
    // Check if we should step down
    val currentState = if req.term > state.term then state.stepDown(req.term) else state
    
    currentState match
      case f: Follower =>
        val canVote = f.votedFor.isEmpty || f.votedFor.contains(req.candidateId)
        val logOk = isLogUpToDate(req.lastLogIndex, req.lastLogTerm, lastLogIndex, lastLogTerm)
        
        // Leader stickiness check
        val leaderAlive = config.leaderStickinessEnabled && f.leaderId.isDefined && !req.isPreVote
        
        if canVote && logOk && !leaderAlive then
          val newFollower = if req.isPreVote then f else f.copy(votedFor = Some(req.candidateId))
          val response = RequestVoteResponse(req.term, voteGranted = true, req.isPreVote)
          val effects = List(
            SendMessage(req.candidateId, response)
          ) ++ (if req.isPreVote then Nil else List(PersistHardState(req.term, Some(req.candidateId))))
          Transition(newFollower, effects)
        else
          val response = RequestVoteResponse(f.term, voteGranted = false, req.isPreVote)
          Transition.withEffect(f, SendMessage(req.candidateId, response))
      
      case _ =>
        val response = RequestVoteResponse(currentState.term, voteGranted = false, req.isPreVote)
        Transition.withEffect(currentState, SendMessage(req.candidateId, response))
  
  private def handleVoteResponse(
    state: NodeState,
    resp: RequestVoteResponse,
    clusterSize: Int
  ): Transition = 
    // Handle pre-vote responses for PreCandidate
    if resp.isPreVote then
      state match
        case p: PreCandidate if resp.voteGranted =>
          // Pre-vote granted - check for majority
          val newPreCandidate = p.withPreVote(NodeId("voter")) // simplified voter tracking
          if newPreCandidate.hasPreVoteMajority(clusterSize) then
            // Pre-vote majority achieved - start real election
            val newTerm = p.term + 1
            val candidate = Candidate(newTerm, Set(NodeId("self"))) // votes for self
            val voteReq = RequestVoteRequest(
              term = newTerm,
              candidateId = NodeId("self"), // will be replaced by config in runtime
              lastLogIndex = 0, // will be filled by runtime
              lastLogTerm = 0,
              isPreVote = false
            )
            Transition(
              candidate,
              List(
                PersistHardState(newTerm, Some(NodeId("self"))),
                Broadcast(voteReq),
                ResetElectionTimer
              )
            )
          else
            Transition.pure(newPreCandidate)
        case p: PreCandidate =>
          // Pre-vote rejected - stay PreCandidate
          Transition.pure(p)
        case _ =>
          Transition.pure(state)
    else
      // Handle real vote responses for Candidate
      state match
        case c: Candidate if resp.voteGranted && resp.term == c.term =>
          val newCandidate = c.withVote(NodeId("voter")) // simplified
          if newCandidate.hasMajority(clusterSize) then
            Transition.withEffect(Leader(c.term), BecomeLeader)
          else
            Transition.pure(newCandidate)
        case c: Candidate if resp.term > c.term =>
          // Higher term - step down
          Transition.pure(c.stepDown(resp.term))
        case _ =>
          Transition.pure(state)
  
  // === Timeouts ===
  
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
        term = f.term + 1, // Request votes for NEXT term, but don't persist it yet
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
    lastLogIndex: Long
  ): Transition = state match
    case l: Leader =>
      // Send heartbeats with actual commit index
      val heartbeat = AppendEntriesRequest(
        term = l.term,
        leaderId = config.localId,
        prevLogIndex = lastLogIndex,
        prevLogTerm = l.term, // simplified - should lookup actual term
        entries = Seq.empty,
        leaderCommit = l.commitIndex
      )
      Transition(l, List(Broadcast(heartbeat), ResetHeartbeatTimer))
    case _ =>
      Transition.pure(state)
  
  // === Helpers ===
  
  private def isLogUpToDate(
    candidateLastIndex: Long,
    candidateLastTerm: Long,
    myLastIndex: Long,
    myLastTerm: Long
  ): Boolean =
    candidateLastTerm > myLastTerm || 
      (candidateLastTerm == myLastTerm && candidateLastIndex >= myLastIndex)
