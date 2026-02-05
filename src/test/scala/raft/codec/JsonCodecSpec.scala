package raft.codec

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.message.RaftMessage.*
import raft.state.NodeId

class JsonCodecSpec extends AnyFlatSpec with Matchers:
  
  private val codec = JsonCodec
  
  "JsonCodec" should "encode and decode RequestVoteRequest" in {
    val req = RequestVoteRequest(
      term = 5,
      candidateId = NodeId("node-1"),
      lastLogIndex = 100,
      lastLogTerm = 4,
      isPreVote = false
    )
    
    val json = codec.encode(req)
    json should include("RV_REQ")
    json should include("term")
    json should include("5")
    
    val decoded = codec.decode(json)
    decoded.isRight shouldBe true
    decoded.toOption.get match
      case r: RequestVoteRequest =>
        r.term shouldBe 5
        r.candidateId shouldBe NodeId("node-1")
        r.lastLogIndex shouldBe 100
        r.lastLogTerm shouldBe 4
      case _ => fail("Wrong message type")
  }
  
  it should "encode and decode RequestVoteResponse" in {
    val resp = RequestVoteResponse(term = 5, voteGranted = true, isPreVote = false)
    
    val json = codec.encode(resp)
    json should include("RV_RESP")
    json should include("granted")
    json should include("true")
    
    val decoded = codec.decode(json)
    decoded.isRight shouldBe true
    decoded.toOption.get match
      case r: RequestVoteResponse =>
        r.term shouldBe 5
        r.voteGranted shouldBe true
      case _ => fail("Wrong message type")
  }
  
  it should "encode and decode AppendEntriesRequest" in {
    val req = AppendEntriesRequest(
      term = 3,
      leaderId = NodeId("leader"),
      prevLogIndex = 10,
      prevLogTerm = 2,
      entries = Seq.empty,
      leaderCommit = 8
    )
    
    val json = codec.encode(req)
    json should include("AE_REQ")
    json should include("leader")
    json should include("commit")
    
    val decoded = codec.decode(json)
    decoded.isRight shouldBe true
    decoded.toOption.get match
      case r: AppendEntriesRequest =>
        r.term shouldBe 3
        r.leaderId shouldBe NodeId("leader")
        r.leaderCommit shouldBe 8
      case _ => fail("Wrong message type")
  }
  
  it should "encode and decode AppendEntriesResponse" in {
    val resp = AppendEntriesResponse(term = 3, success = true, matchIndex = 15)
    
    val json = codec.encode(resp)
    json should include("AE_RESP")
    json should include("matchIndex")
    
    val decoded = codec.decode(json)
    decoded.isRight shouldBe true
    decoded.toOption.get match
      case r: AppendEntriesResponse =>
        r.term shouldBe 3
        r.success shouldBe true
        r.matchIndex shouldBe 15
      case _ => fail("Wrong message type")
  }
  
  it should "encode timeout messages" in {
    codec.encode(ElectionTimeout) should include("ELECTION_TIMEOUT")
    codec.encode(HeartbeatTimeout) should include("HEARTBEAT_TIMEOUT")
  }
  
  it should "decode timeout messages" in {
    codec.decode("""{"type":"ELECTION_TIMEOUT"}""") shouldBe Right(ElectionTimeout)
    codec.decode("""{"type":"HEARTBEAT_TIMEOUT"}""") shouldBe Right(HeartbeatTimeout)
  }
  
  it should "return error for unknown message type" in {
    val result = codec.decode("""{"type":"UNKNOWN_MSG"}""")
    result.isLeft shouldBe true
  }
  
  it should "return error for invalid JSON" in {
    val result = codec.decode("not json at all")
    result.isLeft shouldBe true
  }
