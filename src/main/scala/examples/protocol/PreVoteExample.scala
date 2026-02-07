package examples.protocol

import cats.effect.*
import cats.syntax.all.*
import scala.concurrent.duration.*

import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.logic.*
import raft.effect.Effect.*

/** Pre-Vote Protocol Example
  *
  * Demonstrates the Pre-Vote optimization that prevents network-partitioned
  * nodes from disrupting stable clusters by starting unnecessary elections.
  *
  * ==The Problem Pre-Vote Solves==
  *
  * In standard RAFT, a partitioned node will:
  *   1. Election timeout fires
  *   2. Increment term and become Candidate
  *   3. When partition heals, its high term disrupts the cluster
  *
  * With Pre-Vote:
  *   1. Election timeout fires
  *   2. Become PreCandidate (term NOT incremented)
  *   3. Ask "Would you vote for me if I started an election?"
  *   4. If majority says yes → start real election
  *   5. If no → stay follower, don't disrupt cluster
  *
  * ==RAFT Library Components Used==
  *
  *   - PreCandidate state (new in this implementation)
  *   - RaftLogic.onMessage with pre-vote handling
  *   - RequestVoteRequest with isPreVote=true
  *   - RequestVoteResponse with isPreVote=true
  *   - RaftConfig.preVoteEnabled flag
  */
object PreVoteExample extends IOApp.Simple:

  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")

  // Pre-vote enabled (default)
  val configWithPreVote = RaftConfig(
    localId = node1,
    preVoteEnabled = true
  )

  // Pre-vote disabled
  val configNoPreVote = RaftConfig(
    localId = node1,
    preVoteEnabled = false
  )

  def run: IO[Unit] =
    for
      _ <- IO.println("Pre-Vote Protocol Example\n")

      _ <- demonstratePreVoteFlow
      _ <- IO.println("")
      _ <- compareWithoutPreVote
      _ <- simulatePartitionedNode

      _ <- IO.println("\nPre-Vote Benefits:")
      _ <- IO.println("  • Prevents term inflation from partitioned nodes")
      _ <- IO.println("  • Reduces unnecessary leader disruptions")
      _ <- IO.println(
        "  • Two-phase election: PreCandidate → Candidate → Leader"
      )
    yield ()

  /** Demonstrates the full pre-vote flow: ElectionTimeout → PreCandidate →
    * pre-vote requests → majority response → Candidate promotion.
    */
  private def demonstratePreVoteFlow: IO[Unit] =
    for
      _ <- IO.println(
        "\n▶ Step 1: Pre-Vote Election Flow (preVoteEnabled=true)"
      )
      _ <- IO.println("─" * 50)

      follower = Follower(term = 5, votedFor = None, leaderId = None)
      _ <- IO.println(s"   Initial state: Follower(term=5)")

      // Election timeout with pre-vote enabled
      trans1 = RaftLogic.onMessage(
        follower,
        ElectionTimeout,
        configWithPreVote,
        10,
        5,
        3
      )
      _ <- IO.println(s"   After ElectionTimeout: ${trans1.state}")
      _ <- IO.println(s"   State type: PreCandidate (NOT Candidate!)")
      _ <- IO.println(s"   Term: ${trans1.state.term} (NOT incremented yet)")

      // Check the broadcast request
      preVoteReq = trans1.effects.collectFirst {
        case Broadcast(r: RequestVoteRequest) => r
      }
      _ <- IO.println(
        s"   Pre-vote request term: ${preVoteReq.get.term} (asking for NEXT term)"
      )
      _ <- IO.println(s"   isPreVote: ${preVoteReq.get.isPreVote}")

      // Simulate receiving pre-vote responses
      _ <- IO.println("\n▶ Step 2: Receiving Pre-Vote Responses")
      _ <- IO.println("─" * 50)

      preVoteResp = RequestVoteResponse(
        term = 6,
        voteGranted = true,
        isPreVote = true
      )
      _ <- IO.println(s"   Received pre-vote grant from node-2")

      trans2 = RaftLogic.onMessage(
        trans1.state,
        preVoteResp,
        configWithPreVote,
        10,
        5,
        3
      )
      _ <- IO.println(s"   After 1st pre-vote: ${trans2.state}")

      trans3 = RaftLogic.onMessage(
        trans2.state,
        preVoteResp,
        configWithPreVote,
        10,
        5,
        3
      )
      _ <- IO.println(s"   After 2nd pre-vote (majority): ${trans3.state}")
      _ <- IO.println(s"   NOW becomes Candidate with incremented term")
    yield ()

  /** Shows how a node behaves with `preVoteEnabled=false`: it immediately
    * becomes a Candidate with an incremented term, which can disrupt a stable
    * cluster.
    */
  private def compareWithoutPreVote: IO[Unit] =
    for
      _ <- IO.println("\n▶ Step 3: Comparison WITHOUT Pre-Vote")
      _ <- IO.println("─" * 50)

      follower = Follower(term = 5, votedFor = None, leaderId = None)
      trans = RaftLogic.onMessage(
        follower,
        ElectionTimeout,
        configNoPreVote,
        10,
        5,
        3
      )

      _ <- IO.println(s"   With preVoteEnabled=false:")
      _ <- IO.println(s"   After ElectionTimeout: ${trans.state}")
      _ <- IO.println(
        s"   Immediately becomes Candidate at term ${trans.state.term}"
      )
      _ <- IO.println(s"   Term is already incremented (can disrupt cluster!)")
    yield ()

  /** Simulates a network-partitioned node under both pre-vote and non-pre-vote
    * configurations, illustrating how pre-vote prevents term inflation.
    */
  private def simulatePartitionedNode: IO[Unit] =
    for
      _ <- IO.println("\n▶ Step 4: Partitioned Node Scenario")
      _ <- IO.println("─" * 50)
      _ <- IO.println("   Scenario: Node-3 is partitioned from the cluster")
      _ <- IO.println("")

      partitionedNode = Follower(term = 5, votedFor = None, leaderId = None)

      // With pre-vote: partitioned node stays at low term
      _ <- IO.println("   WITH Pre-Vote:")
      trans1 = RaftLogic.onMessage(
        partitionedNode,
        ElectionTimeout,
        configWithPreVote,
        0,
        0,
        3
      )
      _ <- IO.println(s"      Node becomes: ${trans1.state}")
      _ <- IO.println(s"      Term: ${trans1.state.term} (not incremented)")
      _ <- IO.println(
        s"      Cannot get pre-vote majority → stays PreCandidate"
      )
      _ <- IO.println(s"      When partition heals: no disruption!")
      _ <- IO.println("")

      // Without pre-vote: term keeps incrementing
      _ <- IO.println("   WITHOUT Pre-Vote (repeated timeouts):")
      t1 = RaftLogic.onMessage(
        partitionedNode,
        ElectionTimeout,
        configNoPreVote,
        0,
        0,
        3
      )
      _ <- IO.println(s"      Timeout 1: term=${t1.state.term}")
      t2 = RaftLogic.onMessage(
        t1.state,
        ElectionTimeout,
        configNoPreVote,
        0,
        0,
        3
      )
      _ <- IO.println(s"      Timeout 2: term=${t2.state.term}")
      t3 = RaftLogic.onMessage(
        t2.state,
        ElectionTimeout,
        configNoPreVote,
        0,
        0,
        3
      )
      _ <- IO.println(s"      Timeout 3: term=${t3.state.term}")
      _ <- IO.println(
        s"      When partition heals: high term causes leader step-down!"
      )
    yield ()
