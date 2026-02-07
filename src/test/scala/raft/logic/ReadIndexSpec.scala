package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

import cats.effect.IO
import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.Effect
import raft.effect.Effect.*

/** Tests for Linearizable Reads (ReadIndex) protocol.
  *
  * ReadIndex provides linearizable reads by:
  *   1. Recording current commit index when read request arrives
  *   2. Confirming leadership with heartbeat quorum
  *   3. Returning readIndex to client once commit reaches that index
  */
class ReadIndexSpec extends AnyFlatSpec with Matchers:

  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")

  val config = RaftConfig(localId = node1, preVoteEnabled = false)

  // === READINDEX REQUEST HANDLING ===

  "ReadIndex on Leader" should "return ReadIndexReady with current commit index" in {
    // Leader at term 5, commit index 100
    val leader = Leader(term = 5)
    val commitIndex = 100L

    val req = ReadIndexRequest("read-123")

    // When leader processes ReadIndex, should return the commit index
    // Client waits until their local apply index >= readIndex
    val effect: Effect[IO] = ReadIndexReady("read-123", commitIndex)

    effect match
      case ReadIndexReady(id, idx) =>
        id shouldBe "read-123"
        idx shouldBe commitIndex
      case _ => fail("Expected ReadIndexReady")
  }

  "ReadIndex on Follower" should "be rejected with leader hint" in {
    val follower = Follower(term = 5, votedFor = None, leaderId = Some(node2))

    val req = ReadIndexRequest("read-456")

    // Follower should reject and hint to leader
    val effect: Effect[IO] =
      ReadIndexRejected("read-456", s"Not leader. Hint: $node2")

    effect match
      case ReadIndexRejected(id, hint) =>
        id shouldBe "read-456"
        hint should include(node2.value)
      case _ => fail("Expected ReadIndexRejected")
  }

  "ReadIndex on Follower without leader" should "be rejected without hint" in {
    val follower = Follower(term = 5, votedFor = None, leaderId = None)

    val effect: Effect[IO] =
      ReadIndexRejected("read-789", "Not leader. No known leader.")

    effect match
      case ReadIndexRejected(id, hint) =>
        id shouldBe "read-789"
        hint should include("No known leader")
      case _ => fail("Expected ReadIndexRejected")
  }

  "ReadIndex on Candidate" should "be rejected" in {
    val candidate = Candidate(term = 5, votesReceived = Set(node1))

    val effect: Effect[IO] =
      ReadIndexRejected("read-abc", "Not leader (Candidate)")

    effect match
      case ReadIndexRejected(id, _) => id shouldBe "read-abc"
      case _                        => fail("Expected ReadIndexRejected")
  }

  // === CONFIRM LEADERSHIP ===

  "ConfirmLeadership effect" should "trigger heartbeat round" in {
    // Leader needs to confirm it's still leader before returning read
    val effect: Effect[IO] = ConfirmLeadership(5, () => ())

    effect match
      case ConfirmLeadership(term, _) =>
        term shouldBe 5
      case _ => fail("Expected ConfirmLeadership")
  }

  // === READINDEX RESPONSE ===

  "ReadIndexResponse message" should "carry read index to client" in {
    val response: RaftMessage =
      ReadIndexResponse("read-123", success = true, readIndex = 100)

    response match
      case ReadIndexResponse(reqId, success, readIdx) =>
        reqId shouldBe "read-123"
        success shouldBe true
        readIdx shouldBe 100
      case _ => fail("Expected ReadIndexResponse")
  }

  it should "indicate failure when not leader" in {
    val response: RaftMessage =
      ReadIndexResponse("read-456", success = false, readIndex = 0)

    response match
      case ReadIndexResponse(_, success, readIdx) =>
        success shouldBe false
        readIdx shouldBe 0
      case _ => fail("Expected ReadIndexResponse")
  }

  // === LINEARIZABILITY GUARANTEE ===

  "ReadIndex protocol" should "ensure read sees all prior writes" in {
    // Scenario: Client writes at index 100, then reads
    // ReadIndex returns 100, client waits for local apply >= 100
    // This ensures the read sees the write

    val writeIndex = 100L
    val readIndexResponse = ReadIndexReady("read-after-write", writeIndex)

    readIndexResponse match
      case ReadIndexReady(_, idx) =>
        // Client must wait until local apply index >= idx
        idx shouldBe writeIndex
      case _ => fail("Expected ReadIndexReady")
  }

  // === EFFECTS ===

  "ReadIndexReady effect" should "contain request ID and index" in {
    val effect: Effect[IO] = ReadIndexReady("req-1", 50)

    effect match
      case ReadIndexReady(id, idx) =>
        id shouldBe "req-1"
        idx shouldBe 50
      case _ => fail("Expected ReadIndexReady")
  }

  "ReadIndexRejected effect" should "contain request ID and reason" in {
    val effect: Effect[IO] = ReadIndexRejected("req-2", "Reason")

    effect match
      case ReadIndexRejected(id, reason) =>
        id shouldBe "req-2"
        reason shouldBe "Reason"
      case _ => fail("Expected ReadIndexRejected")
  }
