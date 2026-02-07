package raft.multigroup

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import raft.state.{GroupId, NodeId}
import raft.message.RaftMessage.*
import raft.codec.JsonCodec

class GroupAwareCodecSpec extends AnyFunSuite with Matchers:

  private val codec = new GroupAwareCodec(JsonCodec)

  test("should round-trip encode/decode GroupEnvelope with ElectionTimeout"):
    val envelope = GroupEnvelope(
      GroupId("shard-42"),
      NodeId("node1"),
      ElectionTimeout
    )

    val encoded = codec.encode(envelope)
    val decoded = codec.decode(encoded)

    decoded shouldBe Right(envelope)

  test(
    "should round-trip encode/decode GroupEnvelope with AppendEntriesRequest"
  ):
    val msg = AppendEntriesRequest(
      term = 3,
      leaderId = NodeId("leader"),
      prevLogIndex = 5,
      prevLogTerm = 2,
      entries = Seq.empty,
      leaderCommit = 4
    )
    val envelope = GroupEnvelope(GroupId("range-0"), NodeId("leader"), msg)

    val encoded = codec.encode(envelope)
    val decoded = codec.decode(encoded)

    decoded shouldBe Right(envelope)

  test("should round-trip encode/decode GroupEnvelope with RequestVoteRequest"):
    val msg = RequestVoteRequest(
      term = 5,
      candidateId = NodeId("candidate"),
      lastLogIndex = 10,
      lastLogTerm = 3,
      isPreVote = true
    )
    val envelope = GroupEnvelope(GroupId("shard-0"), NodeId("candidate"), msg)

    val encoded = codec.encode(envelope)
    val decoded = codec.decode(encoded)

    decoded shouldBe Right(envelope)

  test("should fail to decode malformed input"):
    val result = codec.decode("not-a-valid-envelope")
    result.isLeft shouldBe true

  test("should handle group IDs with special characters"):
    val envelope = GroupEnvelope(
      GroupId("shard/range:42"),
      NodeId("node-1"),
      HeartbeatTimeout
    )

    val encoded = codec.encode(envelope)
    val decoded = codec.decode(encoded)

    decoded shouldBe Right(envelope)

  test("should preserve distinct group IDs"):
    val env1 = GroupEnvelope(GroupId("a"), NodeId("n1"), ElectionTimeout)
    val env2 = GroupEnvelope(GroupId("b"), NodeId("n1"), ElectionTimeout)

    val enc1 = codec.encode(env1)
    val enc2 = codec.encode(env2)

    enc1 should not equal enc2
    codec.decode(enc1) shouldBe Right(env1)
    codec.decode(enc2) shouldBe Right(env2)
