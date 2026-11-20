package raft.transport.grpc

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import raft.message.{CodecError, RaftMessage}
import raft.message.RaftMessage.*
import raft.multigroup.{CoalescedHeartbeatConfig, GroupEnvelope}
import raft.spi.TransportConfig
import raft.state.{GroupId, Log, LogType, NodeId}

/** Property-based, wire-compaction, and live Netty HTTP/2 socket tests for
  * [[ProtobufMultiGroupCodec]] and [[GrpcTransport.multiGroupResource]].
  */
class ProtobufMultiGroupCodecSpec
    extends AnyFreeSpec
    with Matchers
    with ScalaCheckPropertyChecks:

  private val n1 = NodeId("node-1")
  private val n2 = NodeId("node-2")

  private val nodeIdGen: Gen[NodeId] =
    Gen.alphaNumStr.suchThat(_.nonEmpty).map(NodeId(_))

  private val groupIdGen: Gen[GroupId] =
    Gen.alphaNumStr.suchThat(_.nonEmpty).map(s => GroupId(s"grp-$s"))

  private val nonNegLongGen: Gen[Long] = Gen.choose(0L, 1_000_000L)

  private val bytesGen: Gen[Array[Byte]] =
    Gen
      .choose(0, 64)
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
      cnt <- Gen.choose(0, 4)
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

  private val envelopeGen: Gen[GroupEnvelope] =
    for
      gid <- groupIdGen
      from <- nodeIdGen
      toOpt <- Gen.option(nodeIdGen)
      msg <- raftMessageGen
    yield GroupEnvelope(gid, from, msg, toOpt)

  private def assertSameEnvelope(
      expected: GroupEnvelope,
      actual: GroupEnvelope
  ): Unit =
    actual.groupId shouldBe expected.groupId
    actual.from shouldBe expected.from
    actual.to shouldBe expected.to
    (expected.message, actual.message) match
      case (e: InstallSnapshotRequest, a: InstallSnapshotRequest) =>
        a.term shouldBe e.term
        a.leaderId shouldBe e.leaderId
        a.lastIncludedIndex shouldBe e.lastIncludedIndex
        a.lastIncludedTerm shouldBe e.lastIncludedTerm
        a.offset shouldBe e.offset
        a.data.toSeq shouldBe e.data.toSeq
        a.done shouldBe e.done
      case (e, a) =>
        a shouldBe e

  "ProtobufMultiGroupCodec" - {
    "should satisfy ScalaCheck round-trip isomorphism for single and batched GroupEnvelopes" in {
      forAll(envelopeGen) { env =>
        val raw = ProtobufMultiGroupCodec.encode(env)
        ProtobufMultiGroupCodec.decode(raw) match
          case Right(Vector(decoded)) => assertSameEnvelope(env, decoded)
          case other => fail(s"Unexpected decode result: $other")
      }

      forAll(Gen.nonEmptyListOf(envelopeGen)) { envs =>
        val raw = ProtobufMultiGroupCodec.encodeBatch(envs)
        ProtobufMultiGroupCodec.decode(raw) match
          case Right(decoded) =>
            decoded should have size envs.size
            envs.zip(decoded).foreach { case (e, a) =>
              assertSameEnvelope(e, a)
            }
          case Left(err) => fail(s"Batch decode failed: $err")
      }
    }

    "should compact homogeneous empty-heartbeat and ACK batches into CoalescedHeartbeatBatchProto frames" in {
      val hbEnvelopes = (0 until 32).map { i =>
        GroupEnvelope(
          groupId = GroupId(s"range-shard-$i"),
          from = NodeId("physical-host-alpha"),
          to = Some(NodeId("physical-host-beta")),
          message = AppendEntriesRequest(
            term = 7L,
            leaderId = NodeId("physical-host-alpha"),
            prevLogIndex = 1000L + i,
            prevLogTerm = 7L,
            entries = Seq.empty,
            leaderCommit = 995L + i
          )
        )
      }.toVector

      val coalescedBytes = ProtobufMultiGroupCodec.encodeBatch(hbEnvelopes)
      val individualBytesSum =
        hbEnvelopes.map(ProtobufMultiGroupCodec.encode(_).length).sum

      ProtobufMultiGroupCodec.isCoalescedHeartbeatFrame(
        coalescedBytes
      ) shouldBe true
      // Coalesced frame deduplicates sender/receiver headers and eliminates nested payload tags!
      coalescedBytes.length should be < ((individualBytesSum * 65) / 100)

      val decoded = ProtobufMultiGroupCodec.decode(coalescedBytes)
      decoded shouldBe Right(hbEnvelopes)

      // Also verify coalesced AppendEntriesResponse batch with conflict hints
      val ackEnvelopes = (0 until 16).map { i =>
        GroupEnvelope(
          groupId = GroupId(s"range-shard-$i"),
          from = NodeId("physical-host-beta"),
          to = Some(NodeId("physical-host-alpha")),
          message = AppendEntriesResponse(
            term = 7L,
            success = i % 2 == 0,
            matchIndex = if i % 2 == 0 then 1000L + i else 0L,
            conflictIndex = if i % 2 == 1 then Some(990L) else None,
            conflictTerm = if i % 2 == 1 then Some(6L) else None
          )
        )
      }.toVector

      val ackBytes = ProtobufMultiGroupCodec.encodeBatch(ackEnvelopes)
      ProtobufMultiGroupCodec.isCoalescedHeartbeatFrame(ackBytes) shouldBe true
      ProtobufMultiGroupCodec.decode(ackBytes) shouldBe Right(ackEnvelopes)
    }

    "should reject empty or malformed MultiRaftWireFrame bytes with CodecError" in {
      import raft.transport.grpc.proto as pb

      ProtobufMultiGroupCodec.decode(Array.emptyByteArray).isLeft shouldBe true
      ProtobufMultiGroupCodec.decode(Array[Byte](0x00)).isLeft shouldBe true
      ProtobufMultiGroupCodec.decode(
        "not-protobuf".getBytes(StandardCharsets.UTF_8)
      ) match
        case Left(_: CodecError) => succeed
        case Right(v)            => fail(s"Expected Left, got $v")

      val emptyFrameBytes =
        pb.MultiRaftWireFrame(pb.MultiRaftWireFrame.Frame.Empty).toByteArray
      ProtobufMultiGroupCodec.decode(emptyFrameBytes).isLeft shouldBe true

      val missingPayload = pb
        .MultiRaftWireFrame(
          pb.MultiRaftWireFrame.Frame.Single(
            pb.MultiGroupEnvelopeProto(
              groupId = "g1",
              fromId = "n1",
              payload = None
            )
          )
        )
        .toByteArray
      ProtobufMultiGroupCodec.decode(missingPayload) match
        case Left(_: CodecError.InvalidField) => succeed
        case other => fail(s"Expected InvalidField, got $other")

      ProtobufMultiGroupCodec.encodeBatch(Seq.empty) shouldBe empty
      ProtobufMultiGroupCodec.isCoalescedHeartbeatFrame(
        Array.emptyByteArray
      ) shouldBe false
      ProtobufMultiGroupCodec.isCoalescedHeartbeatFrame(
        Array[Byte](0x00, 0x01)
      ) shouldBe false

      val singleEnv = GroupEnvelope(
        GroupId("g1"),
        n1,
        AppendEntriesRequest(1L, n1, 0L, 0L, Seq.empty, 0L),
        Some(n2)
      )
      val singleBytes = ProtobufMultiGroupCodec.encodeBatch(Seq(singleEnv))
      ProtobufMultiGroupCodec.isCoalescedHeartbeatFrame(
        singleBytes
      ) shouldBe false
      ProtobufMultiGroupCodec.decode(singleBytes) shouldBe Right(
        Vector(singleEnv)
      )

      val forwardedHbs = Vector(
        GroupEnvelope(
          GroupId("g1"),
          n1,
          AppendEntriesRequest(
            3L,
            NodeId("proxy-leader"),
            10L,
            2L,
            Seq.empty,
            9L
          ),
          Some(n2)
        ),
        GroupEnvelope(
          GroupId("g2"),
          n1,
          AppendEntriesRequest(3L, n1, 11L, 3L, Seq.empty, 10L),
          Some(n2)
        )
      )
      val fwdBytes = ProtobufMultiGroupCodec.encodeBatch(forwardedHbs)
      ProtobufMultiGroupCodec.isCoalescedHeartbeatFrame(fwdBytes) shouldBe true
      ProtobufMultiGroupCodec.decode(fwdBytes) shouldBe Right(forwardedHbs)

      val mixedRoute = Vector(
        GroupEnvelope(
          GroupId("g1"),
          n1,
          AppendEntriesRequest(1L, n1, 0L, 0L, Seq.empty, 0L),
          Some(n2)
        ),
        GroupEnvelope(
          GroupId("g2"),
          n1,
          AppendEntriesRequest(1L, n1, 0L, 0L, Seq.empty, 0L),
          Some(NodeId("node-3"))
        )
      )
      val mixedBytes = ProtobufMultiGroupCodec.encodeBatch(mixedRoute)
      ProtobufMultiGroupCodec.isCoalescedHeartbeatFrame(
        mixedBytes
      ) shouldBe false
      ProtobufMultiGroupCodec.decode(mixedBytes) shouldBe Right(mixedRoute)
    }
  }

  "GrpcTransport.multiGroupResource" - {
    "should multiplex multiple groups, pipelined batches, broadcasts, malformed frames, and coalesced heartbeats over one live HTTP/2 socket pair" in {
      val gCfg = GrpcConfig(host = "127.0.0.1", port = 0)
      val coal = CoalescedHeartbeatConfig(
        enabled = true,
        flushInterval = 15.millis,
        maxBatchSize = 8,
        coalesceResponses = true
      )

      val prog = (for
        pair1 <- GrpcTransport.multiGroupResource[IO](
          gCfg,
          TransportConfig(n1, "127.0.0.1:0", Map.empty),
          coalescing = coal
        )
        pair2 <- GrpcTransport.multiGroupResource[IO](
          gCfg,
          TransportConfig(n2, "127.0.0.1:0", Map.empty),
          coalescing = coal
        )
        (grpc1, mg1) = pair1
        (grpc2, mg2) = pair2
        addr1 <- cats.effect.Resource.eval(grpc1.localAddress)
        addr2 <- cats.effect.Resource.eval(grpc2.localAddress)
        _ <- cats.effect.Resource.eval(
          grpc1.updatePeers(Map(n2 -> addr2)) *>
            grpc2.updatePeers(Map(n1 -> addr1))
        )
      yield (grpc1, mg1, mg2)).use { case (grpc1, mg1, mg2) =>
        val gA = GroupId("shard-A")
        val gB = GroupId("shard-B")
        for
          tp1A <- mg1.registerGroup(gA)
          tp1B <- mg1.registerGroup(gB)
          tp2A <- mg2.registerGroup(gA)
          tp2B <- mg2.registerGroup(gB)

          _ <- grpc1.send(
            n2,
            "corrupt-multiraft-frame".getBytes(StandardCharsets.UTF_8)
          )

          _ <- tp1A.send(
            n2,
            AppendEntriesRequest(1L, n1, 0L, 0L, Seq.empty, 0L)
          )
          _ <- tp1B.send(
            n2,
            AppendEntriesRequest(2L, n1, 5L, 2L, Seq.empty, 5L)
          )
          _ <- mg1.flushCoalescedHeartbeats

          _ <- tp1A.sendBatch(
            n2,
            Seq(
              AppendEntriesRequest(
                1L,
                n1,
                0L,
                0L,
                Seq(Log.command(1L, 1L, "a=1".getBytes)),
                0L
              ),
              AppendEntriesRequest(
                1L,
                n1,
                1L,
                1L,
                Seq(Log.command(2L, 1L, "a=2".getBytes)),
                1L
              )
            )
          )
          _ <- tp1B.broadcast(
            RequestVoteRequest(3L, n1, 10L, 2L, isPreVote = true)
          )

          recvA <- tp2A.receive.take(3).compile.toVector.timeout(5.seconds)
          recvB <- tp2B.receive.take(2).compile.toVector.timeout(5.seconds)
          stats1 <- mg1.coalescerStats
        yield {
          recvA should have size 3
          recvB should have size 2
          recvB.head._2
            .asInstanceOf[AppendEntriesRequest]
            .prevLogIndex shouldBe 5L
          recvB(1)._2 shouldBe RequestVoteRequest(
            3L,
            n1,
            10L,
            2L,
            isPreVote = true
          )
          stats1.envelopesCoalesced shouldBe 2L
          stats1.batchesFlushed shouldBe 1L
        }
      }

      prog.unsafeRunSync()
    }
  }
