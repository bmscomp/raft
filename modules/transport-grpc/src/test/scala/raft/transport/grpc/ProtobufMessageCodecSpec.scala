package raft.transport.grpc

import java.nio.charset.StandardCharsets

import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import raft.message.{CodecError, RaftMessage}
import raft.message.RaftMessage.*
import raft.state.{Log, LogType, NodeId}

class ProtobufMessageCodecSpec
    extends AnyFreeSpec
    with Matchers
    with ScalaCheckPropertyChecks:

  private val n1 = NodeId("node-1")
  private val n2 = NodeId("node-2")

  private val nodeIdGen: Gen[NodeId] =
    Gen.alphaNumStr.suchThat(_.nonEmpty).map(NodeId(_))

  private val nonNegLongGen: Gen[Long] = Gen.choose(0L, 1_000_000L)

  private val bytesGen: Gen[Array[Byte]] =
    Gen
      .choose(0, 128)
      .flatMap(n => Gen.listOfN(n, Arbitrary.arbitrary[Byte]).map(_.toArray))

  private val logGen: Gen[Log] =
    for
      idx <- Gen.choose(1L, 10_000L)
      term <- Gen.choose(1L, 500L)
      lt <- Gen.oneOf(LogType.Command, LogType.NoOp, LogType.Configuration)
      data <-
        if lt == LogType.NoOp then Gen.const(Array.emptyByteArray) else bytesGen
    yield Log(idx, term, lt, data)

  private val raftMessageGen: Gen[RaftMessage] = Gen.oneOf(
    for
      term <- nonNegLongGen
      leader <- nodeIdGen
      prev <- nonNegLongGen
      prevTerm <- nonNegLongGen
      cnt <- Gen.choose(0, 5)
      entries <- Gen.listOfN(cnt, logGen)
      commit <- nonNegLongGen
    yield AppendEntriesRequest(term, leader, prev, prevTerm, entries, commit),
    for
      term <- nonNegLongGen
      ok <- Arbitrary.arbitrary[Boolean]
      m <- nonNegLongGen
      cIdx <- Gen.option(nonNegLongGen)
      cTerm <- Gen.option(nonNegLongGen)
    yield AppendEntriesResponse(term, ok, m, cIdx, cTerm),
    for
      term <- nonNegLongGen
      cand <- nodeIdGen
      lastIdx <- nonNegLongGen
      lastTerm <- nonNegLongGen
      pre <- Arbitrary.arbitrary[Boolean]
      xfer <- Arbitrary.arbitrary[Boolean]
    yield RequestVoteRequest(term, cand, lastIdx, lastTerm, pre, xfer),
    for
      term <- nonNegLongGen
      granted <- Arbitrary.arbitrary[Boolean]
      pre <- Arbitrary.arbitrary[Boolean]
    yield RequestVoteResponse(term, granted, pre),
    for
      term <- nonNegLongGen
      leader <- nodeIdGen
      lastIdx <- nonNegLongGen
      lastTerm <- nonNegLongGen
      offset <- nonNegLongGen
      data <- bytesGen
      done <- Arbitrary.arbitrary[Boolean]
    yield InstallSnapshotRequest(
      term,
      leader,
      lastIdx,
      lastTerm,
      offset,
      data,
      done
    ),
    for
      term <- nonNegLongGen
      ok <- Arbitrary.arbitrary[Boolean]
      m <- nonNegLongGen
    yield InstallSnapshotResponse(term, ok, m),
    for
      term <- nonNegLongGen
      target <- nodeIdGen
    yield TransferLeadershipRequest(term, target),
    for
      term <- nonNegLongGen
      leader <- nodeIdGen
    yield TimeoutNowRequest(term, leader),
    Gen.alphaNumStr.map(ReadIndexRequest(_)),
    for
      reqId <- Gen.alphaNumStr
      ok <- Arbitrary.arbitrary[Boolean]
      idx <- nonNegLongGen
    yield ReadIndexResponse(reqId, ok, idx),
    for
      term <- nonNegLongGen
      srv <- nodeIdGen
      lrn <- Arbitrary.arbitrary[Boolean]
    yield AddServerRequest(term, srv, lrn),
    for
      term <- nonNegLongGen
      ok <- Arbitrary.arbitrary[Boolean]
      hint <- Gen.option(nodeIdGen)
    yield AddServerResponse(term, ok, hint),
    for
      term <- nonNegLongGen
      srv <- nodeIdGen
    yield RemoveServerRequest(term, srv),
    for
      term <- nonNegLongGen
      ok <- Arbitrary.arbitrary[Boolean]
      hint <- Gen.option(nodeIdGen)
    yield RemoveServerResponse(term, ok, hint),
    Gen.const(ElectionTimeout),
    Gen.const(HeartbeatTimeout),
    Gen.const(CheckQuorumTimeout)
  )

  private def assertSameMessage(
      expected: RaftMessage,
      decoded: Either[CodecError, RaftMessage]
  ): Unit =
    (expected, decoded) match
      case (
            exp: InstallSnapshotRequest,
            Right(act: InstallSnapshotRequest)
          ) =>
        act.term shouldBe exp.term
        act.leaderId shouldBe exp.leaderId
        act.lastIncludedIndex shouldBe exp.lastIncludedIndex
        act.lastIncludedTerm shouldBe exp.lastIncludedTerm
        act.offset shouldBe exp.offset
        act.data.toSeq shouldBe exp.data.toSeq
        act.done shouldBe exp.done
      case (_, other) =>
        other shouldBe Right(expected)

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
        assertSameMessage(msg, ProtobufMessageCodec.decode(bytes))
      }
    }

    "should satisfy ScalaCheck round-trip isomorphism across randomized RaftMessage instances" in {
      forAll(raftMessageGen) { msg =>
        val bytes = ProtobufMessageCodec.encode(msg)
        assertSameMessage(msg, ProtobufMessageCodec.decode(bytes))
      }
    }

    "should reject empty or corrupted wire frames with CodecError" in {
      ProtobufMessageCodec.decode(Array.emptyByteArray).isLeft shouldBe true

      val invalid = Array[Byte](0x00, 0x01, 0x02)
      ProtobufMessageCodec.decode(invalid) match
        case Left(_: CodecError) => succeed
        case Right(other)        => fail(s"Expected Left, got $other")
    }
  }

  "GrpcConfig validation" - {
    "should enforce valid port, message size, retry count, and inbound queue bounds" in {
      GrpcConfig(port = 0).port shouldBe 0
      an[IllegalArgumentException] should be thrownBy GrpcConfig(port = -1)
      an[IllegalArgumentException] should be thrownBy GrpcConfig(port = 70000)
      an[IllegalArgumentException] should be thrownBy GrpcConfig(
        maxMessageSize = 0
      )
      an[IllegalArgumentException] should be thrownBy GrpcConfig(maxRetries =
        -1
      )
      an[IllegalArgumentException] should be thrownBy GrpcConfig(
        maxInboundQueueSize = 0
      )
    }
  }
