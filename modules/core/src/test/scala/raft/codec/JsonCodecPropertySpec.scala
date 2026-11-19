package raft.codec

import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import raft.message.{CodecError, DecodeError, MessageCodec, RaftMessage}
import raft.message.RaftMessage.*
import raft.state.{Log, LogType, NodeId}

class JsonCodecPropertySpec
    extends AnyFreeSpec
    with Matchers
    with ScalaCheckPropertyChecks:

  private val trickyCharGen: Gen[Char] = Gen.frequency(
    6 -> Gen.alphaNumChar,
    2 -> Gen.oneOf('"', '\\', '/', '\b', '\f', '\n', '\r', '\t', '{', '}', '[',
      ']', ':', ','),
    1 -> Gen.choose(0.toChar, 0x1f.toChar),
    1 -> Gen.oneOf('é', 'λ', 'Ω', '漢')
  )

  private val trickyStringGen: Gen[String] =
    Gen
      .choose(0, 24)
      .flatMap(n => Gen.listOfN(n, trickyCharGen).map(_.mkString))

  private val nonEmptyNodeIdGen: Gen[NodeId] =
    Gen
      .choose(1, 20)
      .flatMap(n =>
        Gen.listOfN(n, trickyCharGen).map(cs => NodeId(cs.mkString))
      )

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
      leader <- nonEmptyNodeIdGen
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
      cand <- nonEmptyNodeIdGen
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
      leader <- nonEmptyNodeIdGen
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
      target <- nonEmptyNodeIdGen
    yield TransferLeadershipRequest(term, target),
    for
      term <- nonNegLongGen
      leader <- nonEmptyNodeIdGen
    yield TimeoutNowRequest(term, leader),
    trickyStringGen.map(ReadIndexRequest(_)),
    for
      reqId <- trickyStringGen
      ok <- Arbitrary.arbitrary[Boolean]
      idx <- nonNegLongGen
    yield ReadIndexResponse(reqId, ok, idx),
    for
      term <- nonNegLongGen
      srv <- nonEmptyNodeIdGen
      lrn <- Arbitrary.arbitrary[Boolean]
    yield AddServerRequest(term, srv, lrn),
    for
      term <- nonNegLongGen
      ok <- Arbitrary.arbitrary[Boolean]
      hint <- Gen.option(nonEmptyNodeIdGen)
    yield AddServerResponse(term, ok, hint),
    for
      term <- nonNegLongGen
      srv <- nonEmptyNodeIdGen
    yield RemoveServerRequest(term, srv),
    for
      term <- nonNegLongGen
      ok <- Arbitrary.arbitrary[Boolean]
      hint <- Gen.option(nonEmptyNodeIdGen)
    yield RemoveServerResponse(term, ok, hint),
    Gen.const(ElectionTimeout),
    Gen.const(HeartbeatTimeout),
    Gen.const(CheckQuorumTimeout)
  )

  private def assertSameMessage(
      expected: RaftMessage,
      decoded: Either[CodecError, RaftMessage]
  ): Unit =
    decoded shouldBe Right(expected)

  "JsonCodec property round-trips" - {
    "should losslessly round-trip all 17 RaftMessage variants across tricky UTF-8, control chars, and binary payloads" in {
      val eitherCodec = MessageCodec.either(JsonCodec)

      forAll(raftMessageGen) { msg =>
        val json = JsonCodec.encode(msg)
        assertSameMessage(msg, JsonCodec.decode(json))

        val utf8 = JsonCodec.byteCodec.encode(msg)
        assertSameMessage(msg, JsonCodec.byteCodec.decode(utf8))

        val immutBytes = JsonCodec.bytesCodec.encode(msg)
        assertSameMessage(msg, JsonCodec.bytesCodec.decode(immutBytes))

        val lifted = eitherCodec.encode(msg).flatMap(eitherCodec.decode)
        assertSameMessage(msg, lifted)
      }
    }
  }

  "JsonCodec parser and schema error handling" - {
    "should return typed CodecError subtypes for malformed syntax and invalid fields" in {
      // Syntax / parser failures
      JsonCodec.decode("") shouldBe a[Left[?, ?]]
      JsonCodec.decode("[]") should matchPattern {
        case Left(_: CodecError.ParseError) =>
      }
      JsonCodec.decode(
        """{"type":"ELECTION_TIMEOUT"} trailing"""
      ) should matchPattern { case Left(_: CodecError.ParseError) =>
      }
      JsonCodec.decode("""{"type":"unterminated""") should matchPattern {
        case Left(_: CodecError.ParseError) =>
      }
      JsonCodec.decode("""{"type":"bad\qescape"}""") should matchPattern {
        case Left(_: CodecError.ParseError) =>
      }
      JsonCodec.decode("""{"type":"bad\u12"}""") should matchPattern {
        case Left(_: CodecError.ParseError) =>
      }
      JsonCodec.decode("""{"type":"bad\uZZZZ"}""") should matchPattern {
        case Left(_: CodecError.ParseError) =>
      }
      JsonCodec.decode(
        """{"type":"ELECTION_TIMEOUT","v":- }"""
      ) should matchPattern { case Left(_: CodecError.ParseError) =>
      }
      JsonCodec.decode(
        """{"type":"ELECTION_TIMEOUT","v":1.e}"""
      ) should matchPattern { case Left(_: CodecError.ParseError) =>
      }
      JsonCodec.decode(
        """{"type":"ELECTION_TIMEOUT","v":1e+}"""
      ) should matchPattern { case Left(_: CodecError.ParseError) =>
      }
      JsonCodec.decode("""{123:true}""") should matchPattern {
        case Left(_: CodecError.ParseError) =>
      }

      // Version & message-type validation
      JsonCodec.decode("""{"type":"ELECTION_TIMEOUT","v":99}""") shouldBe
        Left(CodecError.UnsupportedVersion(99, 1, JsonCodec.VERSION))
      JsonCodec.decode("""{"type":"NO_SUCH_TYPE","v":1}""") shouldBe
        Left(CodecError.UnknownMessageType("NO_SUCH_TYPE"))

      // Legacy short timeout aliases remain supported
      JsonCodec.decode("""{"type":"ELEC_TIMEOUT"}""") shouldBe Right(
        ElectionTimeout
      )
      JsonCodec.decode("""{"type":"HB_TIMEOUT"}""") shouldBe Right(
        HeartbeatTimeout
      )
      JsonCodec.decode("""{"type":"CQ_TIMEOUT"}""") shouldBe Right(
        CheckQuorumTimeout
      )

      // Missing & mistyped fields
      JsonCodec.decode("""{}""") shouldBe
        Left(CodecError.MissingField("type", "Message"))
      JsonCodec.decode(
        """{"type":"RV_RESP","term":"notLong","granted":true}"""
      ) should matchPattern {
        case Left(CodecError.InvalidField("term", "Long", _)) =>
      }
      JsonCodec.decode(
        """{"type":"RV_RESP","term":1,"granted":"notBool"}"""
      ) should matchPattern {
        case Left(CodecError.InvalidField("granted", "Boolean", _)) =>
      }
      JsonCodec.decode(
        """{"type":"AE_REQ","term":1,"leader":"n1","prev":0,"prevTerm":0,"commit":0,"entries":"bad"}"""
      ) should matchPattern {
        case Left(CodecError.InvalidField("entries", "Array[Log]", _)) =>
      }
      JsonCodec.decode(
        """{"type":"AE_REQ","term":1,"leader":"n1","prev":0,"prevTerm":0,"commit":0,"entries":[{"idx":1,"term":1,"type":"Bogus","data":""}]}"""
      ) should matchPattern {
        case Left(
              CodecError.InvalidField(
                "type",
                "Command|NoOp|Configuration",
                "Bogus"
              )
            ) =>
      }
      JsonCodec.decode(
        """{"type":"IS_REQ","term":1,"leader":"n1","lastIndex":1,"lastTerm":1,"offset":0,"data":"!!!not-base64!!!","done":true}"""
      ) should matchPattern {
        case Left(CodecError.InvalidField("data", "Base64", _)) =>
      }

      DecodeError("boom").getMessage shouldBe "boom"
      CodecError.EncodingFailed("enc-err").getMessage shouldBe "enc-err"
    }
  }
