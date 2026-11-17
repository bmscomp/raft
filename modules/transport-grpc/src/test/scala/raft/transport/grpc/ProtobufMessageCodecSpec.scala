package raft.transport.grpc

import java.nio.charset.StandardCharsets

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import raft.message.{CodecError, RaftMessage}
import raft.message.RaftMessage.*
import raft.state.{Log, LogType, NodeId}

class ProtobufMessageCodecSpec extends AnyFreeSpec with Matchers:

  private val n1 = NodeId("node-1")
  private val n2 = NodeId("node-2")

  "ProtobufMessageCodec" - {
    "should round-trip every RaftMessage variant losslessly" in {
      val binaryData = Array[Byte](0, 1, -1, 127, -128, 42, 99)
      val entries = Vector(
        Log.command(1L, 1L, binaryData),
        Log.noOp(2L, 2L),
        Log(
          3L,
          2L,
          LogType.Configuration,
          "cfg".getBytes(StandardCharsets.UTF_8)
        )
      )

      val samples: List[RaftMessage] = List(
        AppendEntriesRequest(
          term = 5L,
          leaderId = n1,
          prevLogIndex = 10L,
          prevLogTerm = 4L,
          entries = entries,
          leaderCommit = 9L
        ),
        AppendEntriesResponse(
          term = 5L,
          success = false,
          matchIndex = 0L,
          conflictIndex = Some(7L),
          conflictTerm = Some(3L)
        ),
        AppendEntriesResponse(
          term = 5L,
          success = true,
          matchIndex = 13L,
          conflictIndex = None,
          conflictTerm = None
        ),
        RequestVoteRequest(
          term = 6L,
          candidateId = n2,
          lastLogIndex = 20L,
          lastLogTerm = 5L,
          isPreVote = true,
          isTransfer = false
        ),
        RequestVoteRequest(
          term = 7L,
          candidateId = n2,
          lastLogIndex = 20L,
          lastLogTerm = 5L,
          isPreVote = false,
          isTransfer = true
        ),
        RequestVoteResponse(term = 6L, voteGranted = true, isPreVote = true),
        InstallSnapshotRequest(
          term = 8L,
          leaderId = n1,
          lastIncludedIndex = 500L,
          lastIncludedTerm = 7L,
          offset = 65536L,
          data = binaryData,
          done = true
        ),
        InstallSnapshotResponse(term = 8L, success = true, matchIndex = 500L),
        TransferLeadershipRequest(term = 8L, target = n2),
        TimeoutNowRequest(term = 8L, leaderId = n1),
        ReadIndexRequest(requestId = "req-uuid-1"),
        ReadIndexResponse(
          requestId = "req-uuid-1",
          success = true,
          readIndex = 42L
        ),
        AddServerRequest(term = 8L, newServer = n2, isLearner = true),
        AddServerResponse(term = 8L, success = true, leaderHint = Some(n1)),
        AddServerResponse(term = 8L, success = false, leaderHint = None),
        RemoveServerRequest(term = 8L, server = n2),
        RemoveServerResponse(term = 8L, success = true, leaderHint = Some(n1)),
        RemoveServerResponse(term = 8L, success = false, leaderHint = None),
        ElectionTimeout,
        HeartbeatTimeout,
        CheckQuorumTimeout
      )

      samples.foreach { msg =>
        val bytes = ProtobufMessageCodec.encode(msg)
        bytes should not be empty
        (msg, ProtobufMessageCodec.decode(bytes)) match
          case (
                expected: InstallSnapshotRequest,
                Right(actual: InstallSnapshotRequest)
              ) =>
            actual.term shouldBe expected.term
            actual.leaderId shouldBe expected.leaderId
            actual.lastIncludedIndex shouldBe expected.lastIncludedIndex
            actual.lastIncludedTerm shouldBe expected.lastIncludedTerm
            actual.offset shouldBe expected.offset
            actual.data.toSeq shouldBe expected.data.toSeq
            actual.done shouldBe expected.done
          case (_, decoded) =>
            decoded shouldBe Right(msg)
      }
    }

    "should reject empty or corrupted wire frames with CodecError" in {
      ProtobufMessageCodec.decode(Array.emptyByteArray).isLeft shouldBe true

      // Invalid Protobuf wire tag 0
      val invalid = Array[Byte](0x00, 0x01, 0x02)
      ProtobufMessageCodec.decode(invalid) match
        case Left(_: CodecError) => succeed
        case Right(other)        => fail(s"Expected Left, got $other")
    }
  }
