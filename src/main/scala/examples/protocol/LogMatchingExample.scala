package examples.protocol

import cats.effect.*
import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.logic.*
import raft.effect.Effect.*

/** Example demonstrating log matching validation in RAFT.
  *
  * Shows how the protocol rejects AppendEntries requests when the follower's
  * log doesn't match the leader's prevLogIndex/prevLogTerm, ensuring consistency.
  */
object LogMatchingExample extends IOApp.Simple:
  
  val leaderId = NodeId("leader")
  val followerId = NodeId("follower")
  
  val config = RaftConfig(
    localId = followerId,
    electionTimeoutMin = scala.concurrent.duration.Duration(150, "ms"),
    electionTimeoutMax = scala.concurrent.duration.Duration(300, "ms"),
    heartbeatInterval = scala.concurrent.duration.Duration(50, "ms")
  )
  
  def run: IO[Unit] =
    IO.println("=== Log Matching Validation Example ===\n") *>
    IO(demonstrateLogMatching())
  
  private def demonstrateLogMatching(): Unit =
    println("RAFT requires log consistency: before accepting entries, a follower")
    println("must verify that its log matches the leader's at prevLogIndex.\n")
    
    // Scenario 1: Empty log - should always accept
    println("━━━ Scenario 1: Empty Log (prevLogIndex=0) ━━━")
    println("An empty log always matches prevLogIndex=0.\n")
    
    val emptyFollower = Follower(term = 1, votedFor = None, leaderId = None)
    val appendToEmpty = AppendEntriesRequest(
      term = 1,
      leaderId = leaderId,
      prevLogIndex = 0,  // Empty log indicator
      prevLogTerm = 0,
      entries = Seq(Log.command(1, 1, "first-entry".getBytes)),
      leaderCommit = 0
    )
    
    val result1 = RaftLogic.onMessage(
      emptyFollower, appendToEmpty, config,
      lastLogIndex = 0, lastLogTerm = 0, clusterSize = 3,
      getTermAt = _ => None  // No entries exist
    )
    
    val response1 = extractResponse(result1.effects)
    println(s"  Request: prevLogIndex=0, prevLogTerm=0, entries=1")
    println(s"  Follower log: empty")
    println(s"  Result: success=${response1.success} ✓")
    println(s"  New state: ${result1.state}\n")
    
    // Scenario 2: Matching log - should accept
    println("━━━ Scenario 2: Log Matches ━━━")
    println("Follower has entry at index 5 with term 2, leader sends prevLogIndex=5, prevLogTerm=2.\n")
    
    val matchingFollower = Follower(term = 3, votedFor = None, leaderId = Some(leaderId))
    val appendMatching = AppendEntriesRequest(
      term = 3,
      leaderId = leaderId,
      prevLogIndex = 5,
      prevLogTerm = 2,
      entries = Seq(Log.command(6, 3, "new-entry".getBytes)),
      leaderCommit = 4
    )
    
    // Mock: follower has entry at index 5 with term 2
    val getTermAtMatching: Long => Option[Long] = {
      case 5 => Some(2L)
      case _ => None
    }
    
    val result2 = RaftLogic.onMessage(
      matchingFollower, appendMatching, config,
      lastLogIndex = 5, lastLogTerm = 2, clusterSize = 3,
      getTermAt = getTermAtMatching
    )
    
    val response2 = extractResponse(result2.effects)
    println(s"  Request: prevLogIndex=5, prevLogTerm=2, entries=1")
    println(s"  Follower log: has entry at index 5 with term 2")
    println(s"  Result: success=${response2.success}, matchIndex=${response2.matchIndex} ✓")
    
    val hasAppendEffect = result2.effects.exists(_.isInstanceOf[AppendLogs])
    println(s"  AppendLogs effect generated: $hasAppendEffect\n")
    
    // Scenario 3: Missing entry - should reject
    println("━━━ Scenario 3: Log Too Short (Missing Entry) ━━━")
    println("Leader thinks follower has 100 entries, but follower only has 50.\n")
    
    val shortLogFollower = Follower(term = 5, votedFor = None, leaderId = None)
    val appendMissing = AppendEntriesRequest(
      term = 5,
      leaderId = leaderId,
      prevLogIndex = 100,  // Follower doesn't have this
      prevLogTerm = 4,
      entries = Seq(Log.command(101, 5, "new-entry".getBytes)),
      leaderCommit = 90
    )
    
    val result3 = RaftLogic.onMessage(
      shortLogFollower, appendMissing, config,
      lastLogIndex = 50, lastLogTerm = 4, clusterSize = 3,
      getTermAt = _ => None  // No entry at index 100
    )
    
    val response3 = extractResponse(result3.effects)
    println(s"  Request: prevLogIndex=100, prevLogTerm=4")
    println(s"  Follower log: only has entries up to index 50")
    println(s"  Result: success=${response3.success} ✗ (rejected)")
    println(s"  Leader will decrement nextIndex and retry.\n")
    
    // Scenario 4: Term mismatch - should reject and trigger truncation
    println("━━━ Scenario 4: Term Mismatch (Conflicting Entry) ━━━")
    println("Follower has entry at index 10 with term 3, but leader has term 4 there.\n")
    
    val conflictFollower = Follower(term = 5, votedFor = None, leaderId = None)
    val appendConflict = AppendEntriesRequest(
      term = 5,
      leaderId = leaderId,
      prevLogIndex = 10,
      prevLogTerm = 4,  // Leader's term at index 10
      entries = Seq.empty,
      leaderCommit = 8
    )
    
    // Mock: follower has DIFFERENT term at index 10
    val getTermAtConflict: Long => Option[Long] = {
      case 10 => Some(3L)  // Follower has term 3, not 4!
      case _ => None
    }
    
    val result4 = RaftLogic.onMessage(
      conflictFollower, appendConflict, config,
      lastLogIndex = 15, lastLogTerm = 3, clusterSize = 3,
      getTermAt = getTermAtConflict
    )
    
    val response4 = extractResponse(result4.effects)
    println(s"  Request: prevLogIndex=10, prevLogTerm=4")
    println(s"  Follower log: entry at index 10 has term 3")
    println(s"  Result: success=${response4.success} ✗ (term mismatch)")
    println(s"  The follower must truncate conflicting entries before accepting.")
    
    println("\n✅ Log matching examples complete!")
    println("\nKey takeaways:")
    println("  • prevLogIndex=0 always matches (empty log case)")
    println("  • Matching prevLogIndex AND prevLogTerm → accept entries")
    println("  • Missing entry → reject, leader decrements nextIndex")
    println("  • Term mismatch → reject, follower may need to truncate")
  
  private def extractResponse(effects: List[raft.effect.Effect]): AppendEntriesResponse =
    effects.collectFirst {
      case SendMessage(_, resp: AppendEntriesResponse) => resp
    }.getOrElse(throw new RuntimeException("No response found"))
