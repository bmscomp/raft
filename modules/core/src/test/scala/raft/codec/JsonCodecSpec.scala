package raft.codec

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import raft.message.RaftMessage
import raft.message.RaftMessage.*
import raft.state.{Log, LogType, NodeId}

class JsonCodecSpec
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckPropertyChecks:

  private val codec = JsonCodec

  private val genHostileString: Gen[String] =
    Gen
      .listOf(
        Gen.frequency(
          4 -> Gen.alphaNumChar,
          2 -> Gen.oneOf('"', '\\', '\n', '\r', '\t', '\b', '\f'),
          2 -> Gen.oneOf('{', '}', '[', ']', ',', ':', '/'),
          1 -> Gen.choose(0.toChar, 31.toChar),
          1 -> Gen.oneOf('é', 'λ', 'Ω', '漢', '字')
        )
      )
      .map(cs => if cs.isEmpty then "n0" else cs.mkString)

  private val genNodeId: Gen[NodeId] = genHostileString.map(NodeId(_))
  private val genNonNegLong: Gen[Long] = Gen.choose(0L, 1_000_000L)
  private val genBytes: Gen[Array[Byte]] =
    Gen.listOf(Gen.choose(Byte.MinValue, Byte.MaxValue)).map(_.toArray)

  private val genLog: Gen[Log] = for
    idx <- Gen.choose(1L, 10_000L)
    term <- Gen.choose(1L, 1_000L)
    lt <- Gen.oneOf(LogType.Command, LogType.NoOp, LogType.Configuration)
    data <- genBytes
  yield Log(idx, term, lt, data)

  private val genRaftMessage: Gen[RaftMessage] = Gen.oneOf(
    for
      t <- genNonNegLong
      ldr <- genNodeId
      prev <- genNonNegLong
      prevT <- genNonNegLong
      ents <- Gen.resize(5, Gen.listOf(genLog))
      commit <- genNonNegLong
    yield AppendEntriesRequest(t, ldr, prev, prevT, ents, commit),
    for
      t <- genNonNegLong
      ok <- Gen.oneOf(true, false)
      m <- genNonNegLong
      cIdx <- Gen.option(Gen.choose(1L, 500L))
      cTerm <- Gen.option(Gen.choose(1L, 50L))
    yield AppendEntriesResponse(t, ok, m, cIdx, cTerm),
    for
      t <- genNonNegLong
      cand <- genNodeId
      li <- genNonNegLong
      lt <- genNonNegLong
      pv <- Gen.oneOf(true, false)
      tr <- Gen.oneOf(true, false)
    yield RequestVoteRequest(t, cand, li, lt, pv, tr),
    for
      t <- genNonNegLong
      gr <- Gen.oneOf(true, false)
      pv <- Gen.oneOf(true, false)
    yield RequestVoteResponse(t, gr, pv),
    for
      t <- genNonNegLong
      ldr <- genNodeId
      li <- genNonNegLong
      lt <- genNonNegLong
      off <- genNonNegLong
      data <- genBytes
      done <- Gen.oneOf(true, false)
    yield InstallSnapshotRequest(t, ldr, li, lt, off, data, done),
    for
      t <- genNonNegLong
      ok <- Gen.oneOf(true, false)
      m <- genNonNegLong
    yield InstallSnapshotResponse(t, ok, m),
    for
      t <- genNonNegLong
      target <- genNodeId
    yield TransferLeadershipRequest(t, target),
    for
      t <- genNonNegLong
      ldr <- genNodeId
    yield TimeoutNowRequest(t, ldr),
    genHostileString.map(ReadIndexRequest(_)),
    for
      id <- genHostileString
      ok <- Gen.oneOf(true, false)
      ri <- genNonNegLong
    yield ReadIndexResponse(id, ok, ri),
    for
      t <- genNonNegLong
      srv <- genNodeId
      lrn <- Gen.oneOf(true, false)
    yield AddServerRequest(t, srv, lrn),
    for
      t <- genNonNegLong
      ok <- Gen.oneOf(true, false)
      hint <- Gen.option(genNodeId)
    yield AddServerResponse(t, ok, hint),
    for
      t <- genNonNegLong
      srv <- genNodeId
    yield RemoveServerRequest(t, srv),
    for
      t <- genNonNegLong
      ok <- Gen.oneOf(true, false)
      hint <- Gen.option(genNodeId)
    yield RemoveServerResponse(t, ok, hint),
    Gen.const(ElectionTimeout),
    Gen.const(HeartbeatTimeout),
    Gen.const(CheckQuorumTimeout)
  )

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

    codec.decode(json) shouldBe Right(req)
  }

  it should "encode and decode RequestVoteResponse" in {
    val resp =
      RequestVoteResponse(term = 5, voteGranted = true, isPreVote = false)

    val json = codec.encode(resp)
    json should include("RV_RESP")
    json should include("granted")
    json should include("true")

    codec.decode(json) shouldBe Right(resp)
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

    codec.decode(json) shouldBe Right(req)
  }

  it should "encode and decode AppendEntriesResponse with conflict hints" in {
    val resp = AppendEntriesResponse(
      term = 3,
      success = false,
      matchIndex = 0,
      conflictIndex = Some(7L),
      conflictTerm = Some(2L)
    )

    val json = codec.encode(resp)
    json should include("AE_RESP")
    json should include("conflictIndex")
    codec.decode(json) shouldBe Right(resp)
  }

  it should "encode timeout messages" in {
    codec.encode(ElectionTimeout) should include("ELECTION_TIMEOUT")
    codec.encode(HeartbeatTimeout) should include("HEARTBEAT_TIMEOUT")
    codec.encode(CheckQuorumTimeout) should include("CHECK_QUORUM_TIMEOUT")
  }

  it should "decode timeout messages" in {
    codec.decode("""{"type":"ELECTION_TIMEOUT"}""") shouldBe Right(
      ElectionTimeout
    )
    codec.decode("""{"type":"HEARTBEAT_TIMEOUT"}""") shouldBe Right(
      HeartbeatTimeout
    )
    codec.decode("""{"type":"CHECK_QUORUM_TIMEOUT"}""") shouldBe Right(
      CheckQuorumTimeout
    )
  }

  it should "round-trip all RaftMessage variants with hostile escaped strings and binary payloads" in {
    forAll(genRaftMessage) { msg =>
      val encoded = codec.encode(msg)
      val decoded = codec.decode(encoded)
      (decoded, msg) match
        case (Right(a: InstallSnapshotRequest), b: InstallSnapshotRequest) =>
          a.term shouldBe b.term
          a.leaderId shouldBe b.leaderId
          a.lastIncludedIndex shouldBe b.lastIncludedIndex
          a.lastIncludedTerm shouldBe b.lastIncludedTerm
          a.offset shouldBe b.offset
          java.util.Arrays.equals(a.data, b.data) shouldBe true
          a.done shouldBe b.done
        case _ =>
          decoded shouldBe Right(msg)
    }
  }

  it should "return error for unknown message type or unsupported version" in {
    codec.decode("""{"type":"UNKNOWN_MSG"}""").isLeft shouldBe true
    codec.decode("""{"type":"ELECTION_TIMEOUT","v":99}""").isLeft shouldBe true
  }

  it should "return error for invalid JSON" in {
    codec.decode("not json at all").isLeft shouldBe true
    codec
      .decode("""{"type":"RV_REQ","term":"not-a-long"}""")
      .isLeft shouldBe true
  }
