package raft.message

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.{NodeId, Log}
import raft.message.RaftMessage.*

/**
 * Unit tests for RaftMessage types and MessageCodec.
 * 
 * RAFT uses these message types:
 * - RequestVote: Candidate asks for votes
 * - AppendEntries: Leader replicates logs / sends heartbeats
 * - Timeout signals: Internal triggers for elections/heartbeats
 */
class RaftMessageSpec extends AnyFlatSpec with Matchers:
  
  // Request vote Message
  
  "RequestVoteRequest" should "carry candidate information" in {
    val req: RequestVoteRequest = RequestVoteRequest(
      term = 5,
      candidateId = NodeId("candidate-1"),
      lastLogIndex = 100,
      lastLogTerm = 4,
      isPreVote = false
    )
    
    req.term shouldBe 5
    req.candidateId shouldBe NodeId("candidate-1")
    req.lastLogIndex shouldBe 100
    req.lastLogTerm shouldBe 4
    req.isPreVote shouldBe false
  }
  
  "RequestVoteRequest for pre-vote" should "set isPreVote flag" in {
    // Pre-vote is used to check if election would succeed before incrementing term
    val preVoteReq: RequestVoteRequest = RequestVoteRequest(
      term = 5,
      candidateId = NodeId("candidate-1"),
      lastLogIndex = 100,
      lastLogTerm = 4,
      isPreVote = true  // This is a pre-vote
    )
    
    preVoteReq.isPreVote shouldBe true
  }
  
  "RequestVoteResponse" should "indicate whether vote was granted" in {
    val granted: RequestVoteResponse = RequestVoteResponse(term = 5, voteGranted = true, isPreVote = false)
    val denied: RequestVoteResponse = RequestVoteResponse(term = 5, voteGranted = false, isPreVote = false)
    
    granted.voteGranted shouldBe true
    denied.voteGranted shouldBe false
  }
  
  // APPEND ENTRIES MESSAGE
  
  "AppendEntriesRequest" should "carry leader and log information" in {
    val entries = Seq(
      Log.command(1, 5, "cmd1".getBytes),
      Log.command(2, 5, "cmd2".getBytes)
    )
    
    val req: AppendEntriesRequest = AppendEntriesRequest(
      term = 5,
      leaderId = NodeId("leader-1"),
      prevLogIndex = 10,
      prevLogTerm = 4,
      entries = entries,
      leaderCommit = 8
    )
    
    req.term shouldBe 5
    req.leaderId shouldBe NodeId("leader-1")
    req.prevLogIndex shouldBe 10
    req.prevLogTerm shouldBe 4
    req.entries.size shouldBe 2
    req.leaderCommit shouldBe 8
  }
  
  "AppendEntriesRequest as heartbeat" should "have empty entries" in {
    // Heartbeats are AppendEntries with no log entries
    val heartbeat: AppendEntriesRequest = AppendEntriesRequest(
      term = 5,
      leaderId = NodeId("leader-1"),
      prevLogIndex = 100,
      prevLogTerm = 4,
      entries = Seq.empty,  // Empty = heartbeat
      leaderCommit = 90
    )
    
    heartbeat.entries shouldBe empty
  }
  
  "AppendEntriesResponse" should "indicate success and match index" in {
    val success: AppendEntriesResponse = AppendEntriesResponse(term = 5, success = true, matchIndex = 102)
    val failure: AppendEntriesResponse = AppendEntriesResponse(term = 5, success = false, matchIndex = 0)
    
    success.success shouldBe true
    success.matchIndex shouldBe 102
    
    failure.success shouldBe false
  }
  
  // TIMEOUT SIGNALS
  
  "ElectionTimeout" should "be a singleton signal" in {
    ElectionTimeout shouldBe ElectionTimeout
  }
  
  "HeartbeatTimeout" should "be a singleton signal" in {
    HeartbeatTimeout shouldBe HeartbeatTimeout
  }

/**
 * Tests for MessageCodec - user-defined wire format abstraction.
 */
class MessageCodecSpec extends AnyFlatSpec with Matchers:
  
  "CodecError.ParseError" should "carry error message" in {
    val error = CodecError.ParseError("Invalid message format")
    error.message shouldBe "Invalid message format"
  }
  
  "Custom SimpleMessageCodec" should "encode and decode round-trip" in {
    // Example: Simple string-based codec
    val codec = new SimpleMessageCodec[String]:
      def encode(msg: RaftMessage): String = msg match
        case ElectionTimeout => "ELECTION_TIMEOUT"
        case HeartbeatTimeout => "HEARTBEAT_TIMEOUT"
        case _ => "UNKNOWN"
      
      def decode(raw: String): Either[CodecError, RaftMessage] = raw match
        case "ELECTION_TIMEOUT" => Right(ElectionTimeout)
        case "HEARTBEAT_TIMEOUT" => Right(HeartbeatTimeout)
        case other => Left(CodecError.ParseError(s"Unknown: $other"))
    
    // Encode
    codec.encode(ElectionTimeout) shouldBe "ELECTION_TIMEOUT"
    
    // Decode
    codec.decode("ELECTION_TIMEOUT") shouldBe Right(ElectionTimeout)
    codec.decode("INVALID") shouldBe a[Left[?, ?]]
  }
