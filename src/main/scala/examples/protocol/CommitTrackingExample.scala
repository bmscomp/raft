package examples.protocol

import cats.effect.*
import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.logic.*
import raft.effect.Effect.*

/** Example demonstrating commit index tracking and advancement in RAFT.
  *
  * Shows how the leader calculates commitIndex based on majority replication
  * and how followers update their commit position from leaderCommit.
  */
object CommitTrackingExample extends IOApp.Simple:
  
  val leaderId = NodeId("leader")
  
  val config = RaftConfig(
    localId = leaderId,
    electionTimeoutMin = scala.concurrent.duration.Duration(150, "ms"),
    electionTimeoutMax = scala.concurrent.duration.Duration(300, "ms"),
    heartbeatInterval = scala.concurrent.duration.Duration(50, "ms")
  )
  
  def run: IO[Unit] =
    IO.println("=== Commit Index Tracking Example ===\n") *>
    IO(demonstrateCommitTracking())
  
  private def demonstrateCommitTracking(): Unit =
    println("RAFT commits entries when they are replicated to a majority of nodes.")
    println("The leader tracks matchIndex for each follower and calculates commitIndex.\n")
    
    // Step 1: Leader initializes indices
    println("━━━ Step 1: Leader Initialization ━━━")
    println("When a node becomes leader, it initializes tracking for all followers.\n")
    
    val followers = Set(NodeId("node-2"), NodeId("node-3"), NodeId("node-4"), NodeId("node-5"))
    val lastLogIndex = 100L
    
    val newLeader: Leader = Leader(term = 5)
    val initializedLeader = newLeader.initializeIndices(followers, lastLogIndex)
    
    println(s"  Cluster size: ${followers.size + 1} (4 followers + leader)")
    println(s"  Last log index: $lastLogIndex")
    println(s"  Initialized nextIndex: all followers → ${lastLogIndex + 1}")
    println(s"  Initialized matchIndex: all followers → 0")
    println(s"  Initial commitIndex: ${initializedLeader.commitIndex}\n")
    
    for (f, idx) <- initializedLeader.nextIndex do
      println(s"    ${f.value}: nextIndex=$idx, matchIndex=${initializedLeader.matchIndex(f)}")
    
    // Step 2: Simulate replication responses
    println("\n━━━ Step 2: Processing Replication Responses ━━━")
    println("As followers acknowledge entries, leader updates matchIndex.\n")
    
    // Simulate responses from followers
    val leader1 = initializedLeader
      .withMatchIndex(NodeId("node-2"), 100)
      .withNextIndex(NodeId("node-2"), 101)
    println("  node-2 responds: matchIndex=100 ✓")
    
    val leader2 = leader1
      .withMatchIndex(NodeId("node-3"), 100)
      .withNextIndex(NodeId("node-3"), 101)
    println("  node-3 responds: matchIndex=100 ✓")
    
    val leader3 = leader2
      .withMatchIndex(NodeId("node-4"), 75)
      .withNextIndex(NodeId("node-4"), 76)
    println("  node-4 responds: matchIndex=75 (lagging)")
    
    val leader4 = leader3
      .withMatchIndex(NodeId("node-5"), 50)
      .withNextIndex(NodeId("node-5"), 51)
    println("  node-5 responds: matchIndex=50 (very lagging)\n")
    
    println("  Current matchIndex state:")
    for (f, idx) <- leader4.matchIndex do
      println(s"    ${f.value}: $idx")
    
    // Step 3: Calculate commit index
    println("\n━━━ Step 3: Calculating Commit Index ━━━")
    println("Leader finds the highest index replicated to a majority.\n")
    
    // For commit, we need term info - assume entries at index 100 are from term 5
    val getTermAt: Long => Option[Long] = {
      case idx if idx <= 100 && idx >= 75 => Some(5L)  // Current term entries
      case idx if idx < 75 => Some(4L)  // Old term entries
      case _ => None
    }
    
    val clusterSize = 5  // 1 leader + 4 followers
    val newCommitIndex = leader4.calculateCommitIndex(clusterSize, getTermAt)
    
    println(s"  matchIndex values: ${leader4.matchIndex.values.toList.sorted.reverse}")
    println(s"  Cluster size: $clusterSize")
    println(s"  Majority needed: ${clusterSize / 2 + 1}")
    println(s"  Calculated commit index: $newCommitIndex")
    
    // Explanation
    println("\n  Analysis:")
    println("    Sorted matchIndex (desc): [100, 100, 75, 50, 0]")
    println("    Majority = 3 nodes (position 2 in 0-indexed array)")
    println("    Value at position 2: 75")
    println("    Entry at 75 is from term 5 (current) → can commit")
    
    // Step 4: Heartbeat carries commit info
    println("\n━━━ Step 4: Broadcasting Commit to Followers ━━━")
    println("Leader includes leaderCommit in heartbeats.\n")
    
    val leaderWithCommit = leader4.withCommitIndex(75)
    val heartbeatTransition = RaftLogic.onMessage(
      leaderWithCommit,
      HeartbeatTimeout,
      config,
      lastLogIndex = 100, lastLogTerm = 5, clusterSize = 5
    )
    
    val heartbeat = heartbeatTransition.effects.collectFirst {
      case Broadcast(ae: AppendEntriesRequest) => ae
    }
    
    heartbeat match
      case Some(hb) =>
        println(s"  Heartbeat leaderCommit: ${hb.leaderCommit}")
        println(s"  Followers will commit entries up to index ${hb.leaderCommit}")
      case None =>
        println("  (No heartbeat generated)")
    
    // Step 5: Follower applies commit
    println("\n━━━ Step 5: Follower Receiving Commit ━━━")
    println("Follower updates its commit index from leaderCommit.\n")
    
    val followerConfig = config.copy(localId = NodeId("node-3"))
    val follower = Follower(term = 5, votedFor = None, leaderId = Some(leaderId))
    
    val appendWithCommit = AppendEntriesRequest(
      term = 5,
      leaderId = leaderId,
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq.empty,
      leaderCommit = 75
    )
    
    val followerTransition = RaftLogic.onMessage(
      follower, appendWithCommit, followerConfig,
      lastLogIndex = 100, lastLogTerm = 5, clusterSize = 5
    )
    
    val commitEffect = followerTransition.effects.collectFirst {
      case CommitEntries(idx) => idx
    }
    
    commitEffect match
      case Some(idx) =>
        println(s"  CommitEntries effect generated: upToIndex=$idx ✓")
        println(s"  Follower will apply entries 1 through $idx to state machine")
      case None =>
        println("  (No commit effect - leaderCommit may be 0)")
    
    println("\n✅ Commit tracking example complete!")
    println("\nKey takeaways:")
    println("  • Leader initializes nextIndex=lastLog+1, matchIndex=0 for all followers")
    println("  • matchIndex updated when follower acknowledges replication")
    println("  • commitIndex = median of matchIndex values (majority threshold)")
    println("  • Only commits entries from current term (RAFT safety)")
    println("  • Followers learn commitIndex via leaderCommit in AppendEntries")
