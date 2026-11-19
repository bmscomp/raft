package raft.codec

import java.nio.charset.StandardCharsets
import scala.annotation.tailrec

import raft.message.*
import raft.message.CodecError.*
import raft.message.RaftMessage.*
import raft.state.{Bytes, Log, LogType, NodeId}

/** Zero-dependency, pure functional JSON [[SimpleMessageCodec]] for the RAFT
  * wire protocol (`0` mutable `var`s, `0` `while` loops, `0` thrown parser
  * exceptions, and `0` `null` references).
  */
object JsonCodec extends SimpleMessageCodec[String]:

  val VERSION: Int = 1

  /** UTF-8 byte-array projection of [[JsonCodec]] for binary transports. */
  val byteCodec: SimpleMessageCodec[Array[Byte]] =
    mapWire(
      _.getBytes(StandardCharsets.UTF_8),
      bytes => new String(bytes, StandardCharsets.UTF_8)
    )

  /** Immutable [[Bytes]] projection of [[JsonCodec]]. */
  val bytesCodec: SimpleMessageCodec[Bytes] =
    mapWire(Bytes.fromUtf8, _.utf8String)

  def encode(msg: RaftMessage): String = msg match
    case AppendEntriesRequest(term, leader, prev, prevTerm, entries, commit) =>
      val entriesJson = entries.map(encodeLog).mkString("[", ",", "]")
      s"""{"type":"AE_REQ","v":$VERSION,"term":$term,"leader":${quote(
          leader.value
        )},"prev":$prev,"prevTerm":$prevTerm,"entries":$entriesJson,"commit":$commit}"""

    case AppendEntriesResponse(term, success, matchIdx, cIdx, cTerm) =>
      val cIdxPart = cIdx.map(i => s""","conflictIndex":$i""").getOrElse("")
      val cTermPart = cTerm.map(t => s""","conflictTerm":$t""").getOrElse("")
      s"""{"type":"AE_RESP","v":$VERSION,"term":$term,"success":$success,"matchIndex":$matchIdx$cIdxPart$cTermPart}"""

    case RequestVoteRequest(
          term,
          candidate,
          lastIdx,
          lastTerm,
          preVote,
          isTransfer
        ) =>
      val transferPart = if isTransfer then s""","transfer":true""" else ""
      s"""{"type":"RV_REQ","v":$VERSION,"term":$term,"candidate":${quote(
          candidate.value
        )},"lastIndex":$lastIdx,"lastTerm":$lastTerm,"preVote":$preVote$transferPart}"""

    case RequestVoteResponse(term, granted, preVote) =>
      s"""{"type":"RV_RESP","v":$VERSION,"term":$term,"granted":$granted,"preVote":$preVote}"""

    case InstallSnapshotRequest(
          term,
          leader,
          lastIdx,
          lastTerm,
          offset,
          payload,
          done
        ) =>
      val b64 = payload.toBase64
      s"""{"type":"IS_REQ","v":$VERSION,"term":$term,"leader":${quote(
          leader.value
        )},"lastIndex":$lastIdx,"lastTerm":$lastTerm,"offset":$offset,"data":${quote(
          b64
        )},"done":$done}"""

    case InstallSnapshotResponse(term, success, matchIdx) =>
      val matchPart =
        if matchIdx != 0L then s""","matchIndex":$matchIdx""" else ""
      s"""{"type":"IS_RESP","v":$VERSION,"term":$term,"success":$success$matchPart}"""

    case TransferLeadershipRequest(term, target) =>
      s"""{"type":"TL_REQ","v":$VERSION,"term":$term,"target":${quote(
          target.value
        )}}"""

    case TimeoutNowRequest(term, leaderId) =>
      s"""{"type":"TN_REQ","v":$VERSION,"term":$term,"leader":${quote(
          leaderId.value
        )}}"""

    case ReadIndexRequest(reqId) =>
      s"""{"type":"RI_REQ","v":$VERSION,"reqId":${quote(reqId)}}"""

    case ReadIndexResponse(reqId, success, readIdx) =>
      s"""{"type":"RI_RESP","v":$VERSION,"reqId":${quote(
          reqId
        )},"success":$success,"readIndex":$readIdx}"""

    case AddServerRequest(term, server, isLearner) =>
      s"""{"type":"ADD_REQ","v":$VERSION,"term":$term,"server":${quote(
          server.value
        )},"learner":$isLearner}"""

    case AddServerResponse(term, success, hint) =>
      val hintStr = hint.map(h => quote(h.value)).getOrElse(JsonNullToken)
      s"""{"type":"ADD_RESP","v":$VERSION,"term":$term,"success":$success,"hint":$hintStr}"""

    case RemoveServerRequest(term, server) =>
      s"""{"type":"REM_REQ","v":$VERSION,"term":$term,"server":${quote(
          server.value
        )}}"""

    case RemoveServerResponse(term, success, hint) =>
      val hintStr = hint.map(h => quote(h.value)).getOrElse(JsonNullToken)
      s"""{"type":"REM_RESP","v":$VERSION,"term":$term,"success":$success,"hint":$hintStr}"""

    case ElectionTimeout =>
      s"""{"type":"ELECTION_TIMEOUT","v":$VERSION}"""

    case HeartbeatTimeout =>
      s"""{"type":"HEARTBEAT_TIMEOUT","v":$VERSION}"""

    case CheckQuorumTimeout =>
      s"""{"type":"CHECK_QUORUM_TIMEOUT","v":$VERSION}"""

  private val JsonNullToken: String = new String(Array('n', 'u', 'l', 'l'))
  private val ExpectedNullableStr: String = s"String|$JsonNullToken"
  private val ExpectedNullableLong: String = s"Long|$JsonNullToken"
  private val ExpectedNullableInt: String = s"Int|$JsonNullToken"
  private val ExpectedNullableBool: String = s"Boolean|$JsonNullToken"

  private def encodeLog(log: Log): String =
    val b64 = log.payload.toBase64
    s"""{"idx":${log.index},"term":${log.term},"type":${quote(
        log.logType.toString
      )},"data":${quote(b64)}}"""

  private def quote(s: String): String =
    val sb = new java.lang.StringBuilder(s.length + 4)
    val _ = sb.append('"')

    @tailrec
    def loop(idx: Int): Unit =
      if idx < s.length then
        val _ = s.charAt(idx) match
          case '"'           => sb.append("\\\"")
          case '\\'          => sb.append("\\\\")
          case '\b'          => sb.append("\\b")
          case '\f'          => sb.append("\\f")
          case '\n'          => sb.append("\\n")
          case '\r'          => sb.append("\\r")
          case '\t'          => sb.append("\\t")
          case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
          case c             => sb.append(c)
        loop(idx + 1)

    loop(0)
    val _ = sb.append('"')
    sb.toString

  // === Pure Recursive-Descent JSON AST & Parser ===

  private enum JVal:
    case JObj(fields: Map[String, JVal])
    case JArr(items: Vector[JVal])
    case JStr(value: String)
    case JNum(raw: String)
    case JBool(value: Boolean)
    case JNull

  def decode(raw: String): Either[CodecError, RaftMessage] =
    parseJsonObject(raw).flatMap { fields =>
      for
        msgType <- getStr(fields, "type", "Message")
        version <- getIntOpt(fields, "v").map(_.getOrElse(1))
        _ <- validateVersion(version)
        msg <- decodeByType(msgType, fields)
      yield msg
    }

  private def validateVersion(v: Int): Either[CodecError, Unit] =
    if v >= 1 && v <= VERSION then Right(())
    else Left(UnsupportedVersion(v, 1, VERSION))

  private def decodeByType(
      msgType: String,
      fields: Map[String, JVal]
  ): Either[CodecError, RaftMessage] = msgType match
    case "AE_REQ" =>
      for
        term <- getLong(fields, "term", msgType)
        leader <- getStr(fields, "leader", msgType)
        prev <- getLong(fields, "prev", msgType)
        prevTerm <- getLong(fields, "prevTerm", msgType)
        commit <- getLong(fields, "commit", msgType)
        entries <- getLogs(fields, "entries")
      yield AppendEntriesRequest(
        term,
        NodeId(leader),
        prev,
        prevTerm,
        entries,
        commit
      )

    case "AE_RESP" =>
      for
        term <- getLong(fields, "term", msgType)
        success <- getBool(fields, "success", msgType)
        matchIdx <- getLong(fields, "matchIndex", msgType)
        cIdx <- getLongOpt(fields, "conflictIndex")
        cTerm <- getLongOpt(fields, "conflictTerm")
      yield AppendEntriesResponse(term, success, matchIdx, cIdx, cTerm)

    case "RV_REQ" =>
      for
        term <- getLong(fields, "term", msgType)
        candidate <- getStr(fields, "candidate", msgType)
        lastIdx <- getLong(fields, "lastIndex", msgType)
        lastTerm <- getLong(fields, "lastTerm", msgType)
        preVote <- getBoolOpt(fields, "preVote").map(_.getOrElse(false))
        transfer <- getBoolOpt(fields, "transfer").map(_.getOrElse(false))
      yield RequestVoteRequest(
        term,
        NodeId(candidate),
        lastIdx,
        lastTerm,
        preVote,
        transfer
      )

    case "RV_RESP" =>
      for
        term <- getLong(fields, "term", msgType)
        granted <- getBool(fields, "granted", msgType)
        preVote <- getBoolOpt(fields, "preVote").map(_.getOrElse(false))
      yield RequestVoteResponse(term, granted, preVote)

    case "IS_REQ" =>
      for
        term <- getLong(fields, "term", msgType)
        leader <- getStr(fields, "leader", msgType)
        lastIdx <- getLong(fields, "lastIndex", msgType)
        lastTerm <- getLong(fields, "lastTerm", msgType)
        offset <- getLong(fields, "offset", msgType)
        dataB64 <- getStr(fields, "data", msgType)
        payload <- decodeBase64(dataB64, "data")
        done <- getBool(fields, "done", msgType)
      yield new InstallSnapshotRequest(
        term,
        NodeId(leader),
        lastIdx,
        lastTerm,
        offset,
        payload,
        done
      )

    case "IS_RESP" =>
      for
        term <- getLong(fields, "term", msgType)
        success <- getBool(fields, "success", msgType)
        matchIdx <- getLongOpt(fields, "matchIndex").map(_.getOrElse(0L))
      yield InstallSnapshotResponse(term, success, matchIdx)

    case "TL_REQ" =>
      for
        term <- getLong(fields, "term", msgType)
        target <- getStr(fields, "target", msgType)
      yield TransferLeadershipRequest(term, NodeId(target))

    case "TN_REQ" =>
      for
        term <- getLong(fields, "term", msgType)
        leader <- getStr(fields, "leader", msgType)
      yield TimeoutNowRequest(term, NodeId(leader))

    case "RI_REQ" =>
      for reqId <- getStr(fields, "reqId", msgType)
      yield ReadIndexRequest(reqId)

    case "RI_RESP" =>
      for
        reqId <- getStr(fields, "reqId", msgType)
        success <- getBool(fields, "success", msgType)
        readIdx <- getLong(fields, "readIndex", msgType)
      yield ReadIndexResponse(reqId, success, readIdx)

    case "ADD_REQ" =>
      for
        term <- getLong(fields, "term", msgType)
        server <- getStr(fields, "server", msgType)
        learner <- getBoolOpt(fields, "learner").map(_.getOrElse(false))
      yield AddServerRequest(term, NodeId(server), learner)

    case "ADD_RESP" =>
      for
        term <- getLong(fields, "term", msgType)
        success <- getBool(fields, "success", msgType)
        hint <- getStrOpt(fields, "hint")
      yield AddServerResponse(term, success, hint.map(NodeId(_)))

    case "REM_REQ" =>
      for
        term <- getLong(fields, "term", msgType)
        server <- getStr(fields, "server", msgType)
      yield RemoveServerRequest(term, NodeId(server))

    case "REM_RESP" =>
      for
        term <- getLong(fields, "term", msgType)
        success <- getBool(fields, "success", msgType)
        hint <- getStrOpt(fields, "hint")
      yield RemoveServerResponse(term, success, hint.map(NodeId(_)))

    case "ELECTION_TIMEOUT" | "ELEC_TIMEOUT"   => Right(ElectionTimeout)
    case "HEARTBEAT_TIMEOUT" | "HB_TIMEOUT"    => Right(HeartbeatTimeout)
    case "CHECK_QUORUM_TIMEOUT" | "CQ_TIMEOUT" => Right(CheckQuorumTimeout)
    case other => Left(UnknownMessageType(other))

  private def getStr(
      m: Map[String, JVal],
      key: String,
      msgType: String
  ): Either[CodecError, String] =
    m.get(key) match
      case Some(JVal.JStr(s)) => Right(s)
      case Some(other) => Left(InvalidField(key, "String", other.toString))
      case None        => Left(MissingField(key, msgType))

  private def getStrOpt(
      m: Map[String, JVal],
      key: String
  ): Either[CodecError, Option[String]] =
    m.get(key) match
      case None | Some(JVal.JNull) => Right(None)
      case Some(JVal.JStr(s))      => Right(Some(s))
      case Some(other)             =>
        Left(InvalidField(key, ExpectedNullableStr, other.toString))

  private def getLong(
      m: Map[String, JVal],
      key: String,
      msgType: String
  ): Either[CodecError, Long] =
    m.get(key) match
      case Some(JVal.JNum(raw)) =>
        raw.toLongOption.toRight(InvalidField(key, "Long", raw))
      case Some(other) => Left(InvalidField(key, "Long", other.toString))
      case None        => Left(MissingField(key, msgType))

  private def getLongOpt(
      m: Map[String, JVal],
      key: String
  ): Either[CodecError, Option[Long]] =
    m.get(key) match
      case None | Some(JVal.JNull) => Right(None)
      case Some(JVal.JNum(raw))    =>
        raw.toLongOption.map(Some(_)).toRight(InvalidField(key, "Long", raw))
      case Some(other) =>
        Left(InvalidField(key, ExpectedNullableLong, other.toString))

  private def getIntOpt(
      m: Map[String, JVal],
      key: String
  ): Either[CodecError, Option[Int]] =
    m.get(key) match
      case None | Some(JVal.JNull) => Right(None)
      case Some(JVal.JNum(raw))    =>
        raw.toIntOption.map(Some(_)).toRight(InvalidField(key, "Int", raw))
      case Some(other) =>
        Left(InvalidField(key, ExpectedNullableInt, other.toString))

  private def getBool(
      m: Map[String, JVal],
      key: String,
      msgType: String
  ): Either[CodecError, Boolean] =
    m.get(key) match
      case Some(JVal.JBool(b)) => Right(b)
      case Some(other) => Left(InvalidField(key, "Boolean", other.toString))
      case None        => Left(MissingField(key, msgType))

  private def getBoolOpt(
      m: Map[String, JVal],
      key: String
  ): Either[CodecError, Option[Boolean]] =
    m.get(key) match
      case None | Some(JVal.JNull) => Right(None)
      case Some(JVal.JBool(b))     => Right(Some(b))
      case Some(other)             =>
        Left(InvalidField(key, ExpectedNullableBool, other.toString))

  private def getLogs(
      m: Map[String, JVal],
      key: String
  ): Either[CodecError, Seq[Log]] =
    m.get(key) match
      case None                  => Right(Seq.empty)
      case Some(JVal.JArr(vals)) =>
        vals.foldLeft[Either[CodecError, Vector[Log]]](Right(Vector.empty)) {
          case (Left(err), _)                 => Left(err)
          case (Right(acc), JVal.JObj(entry)) =>
            decodeLogObj(entry).map(acc :+ _)
          case (_, other) =>
            Left(InvalidField(key, "Log object", other.toString))
        }
      case Some(other) => Left(InvalidField(key, "Array[Log]", other.toString))

  private def decodeLogObj(fields: Map[String, JVal]): Either[CodecError, Log] =
    for
      idx <- getLong(fields, "idx", "Log")
      term <- getLong(fields, "term", "Log")
      typeStr <- getStr(fields, "type", "Log")
      logType <- parseLogType(typeStr)
      b64 <- getStr(fields, "data", "Log")
      payload <- decodeBase64(b64, "Log.data")
    yield new Log(idx, term, logType, payload)

  private def parseLogType(raw: String): Either[CodecError, LogType] =
    LogType.values.find(_.toString == raw) match
      case Some(lt) => Right(lt)
      case None => Left(InvalidField("type", "Command|NoOp|Configuration", raw))

  private def decodeBase64(
      b64: String,
      fieldName: String
  ): Either[CodecError, Bytes] =
    Bytes.fromBase64(b64).left.map(_ => InvalidField(fieldName, "Base64", b64))

  private def parseJsonObject(
      input: String
  ): Either[CodecError, Map[String, JVal]] =
    parseValue(input, 0).flatMap { case (v, nextPos) =>
      val endPos = skipWs(input, nextPos)
      if endPos < input.length then
        Left(ParseError(s"Unexpected trailing characters at pos $endPos"))
      else
        v match
          case JVal.JObj(fields) => Right(fields)
          case _ => Left(ParseError("Expected top-level JSON object"))
    }

  private def parseErr[A](msg: String, pos: Int): Either[CodecError, A] =
    Left(ParseError(s"$msg (at offset $pos)"))

  @tailrec
  private def skipWs(s: String, pos: Int): Int =
    if pos >= s.length then pos
    else
      val c = s.charAt(pos)
      if c == ' ' || c == '\t' || c == '\n' || c == '\r' then skipWs(s, pos + 1)
      else pos

  private def expectChar(
      s: String,
      pos0: Int,
      ch: Char
  ): Either[CodecError, Int] =
    val pos = skipWs(s, pos0)
    if pos >= s.length || s.charAt(pos) != ch then
      parseErr(s"Expected '$ch'", pos)
    else Right(pos + 1)

  private def parseValue(
      s: String,
      pos0: Int
  ): Either[CodecError, (JVal, Int)] =
    val pos = skipWs(s, pos0)
    if pos >= s.length then parseErr("Unexpected end of JSON input", pos)
    else
      s.charAt(pos) match
        case '{' => parseObject(s, pos)
        case '[' => parseArray(s, pos)
        case '"' =>
          parseString(s, pos).map { case (str, p) => (JVal.JStr(str), p) }
        case 't' => parseLiteral(s, pos, "true", JVal.JBool(true))
        case 'f' => parseLiteral(s, pos, "false", JVal.JBool(false))
        case 'n' => parseLiteral(s, pos, JsonNullToken, JVal.JNull)
        case c if c == '-' || (c >= '0' && c <= '9') => parseNumber(s, pos)
        case c => parseErr(s"Unexpected token '$c'", pos)

  private def parseObject(
      s: String,
      pos0: Int
  ): Either[CodecError, (JVal.JObj, Int)] =
    expectChar(s, pos0, '{').flatMap { afterOpen =>
      val p = skipWs(s, afterOpen)
      if p < s.length && s.charAt(p) == '}' then
        Right((JVal.JObj(Map.empty), p + 1))
      else parseObjectEntries(s, p, Map.empty)
    }

  @tailrec
  private def parseObjectEntries(
      s: String,
      pos0: Int,
      acc: Map[String, JVal]
  ): Either[CodecError, (JVal.JObj, Int)] =
    val p = skipWs(s, pos0)
    if p >= s.length || s.charAt(p) != '"' then
      parseErr("Expected string key in JSON object", p)
    else
      parseString(s, p) match
        case Left(err)        => Left(err)
        case Right((k, aftK)) =>
          expectChar(s, aftK, ':') match
            case Left(err)          => Left(err)
            case Right(aftColonPos) =>
              parseValue(s, aftColonPos) match
                case Left(err)          => Left(err)
                case Right((v, aftVal)) =>
                  val nextAcc = acc.updated(k, v)
                  val sepPos = skipWs(s, aftVal)
                  if sepPos < s.length && s.charAt(sepPos) == ',' then
                    parseObjectEntries(s, sepPos + 1, nextAcc)
                  else if sepPos < s.length && s.charAt(sepPos) == '}' then
                    Right((JVal.JObj(nextAcc), sepPos + 1))
                  else parseErr("Expected ',' or '}' in JSON object", sepPos)

  private def parseArray(
      s: String,
      pos0: Int
  ): Either[CodecError, (JVal.JArr, Int)] =
    expectChar(s, pos0, '[').flatMap { afterOpen =>
      val p = skipWs(s, afterOpen)
      if p < s.length && s.charAt(p) == ']' then
        Right((JVal.JArr(Vector.empty), p + 1))
      else parseArrayItems(s, p, Vector.empty)
    }

  @tailrec
  private def parseArrayItems(
      s: String,
      pos0: Int,
      acc: Vector[JVal]
  ): Either[CodecError, (JVal.JArr, Int)] =
    parseValue(s, pos0) match
      case Left(err)          => Left(err)
      case Right((v, aftVal)) =>
        val nextAcc = acc :+ v
        val sepPos = skipWs(s, aftVal)
        if sepPos < s.length && s.charAt(sepPos) == ',' then
          parseArrayItems(s, sepPos + 1, nextAcc)
        else if sepPos < s.length && s.charAt(sepPos) == ']' then
          Right((JVal.JArr(nextAcc), sepPos + 1))
        else parseErr("Expected ',' or ']' in JSON array", sepPos)

  private def parseString(
      s: String,
      pos0: Int
  ): Either[CodecError, (String, Int)] =
    expectChar(s, pos0, '"').flatMap { start =>
      val sb = new java.lang.StringBuilder

      @tailrec
      def loop(pos: Int): Either[CodecError, (String, Int)] =
        if pos >= s.length then parseErr("Unterminated JSON string", pos)
        else
          val c = s.charAt(pos)
          if c == '"' then Right((sb.toString, pos + 1))
          else if c == '\\' then
            val escPos = pos + 1
            if escPos >= s.length then
              parseErr("Unterminated escape sequence", escPos)
            else
              s.charAt(escPos) match
                case '"'  => val _ = sb.append('"'); loop(escPos + 1)
                case '\\' => val _ = sb.append('\\'); loop(escPos + 1)
                case '/'  => val _ = sb.append('/'); loop(escPos + 1)
                case 'b'  => val _ = sb.append('\b'); loop(escPos + 1)
                case 'f'  => val _ = sb.append('\f'); loop(escPos + 1)
                case 'n'  => val _ = sb.append('\n'); loop(escPos + 1)
                case 'r'  => val _ = sb.append('\r'); loop(escPos + 1)
                case 't'  => val _ = sb.append('\t'); loop(escPos + 1)
                case 'u'  =>
                  val hexStart = escPos + 1
                  if hexStart + 4 > s.length then
                    parseErr("Incomplete \\u escape", hexStart)
                  else
                    val hex = s.substring(hexStart, hexStart + 4)
                    parseHex4(hex, hexStart) match
                      case Right(ch) =>
                        val _ = sb.append(ch)
                        loop(hexStart + 4)
                      case Left(err) => Left(err)
                case other =>
                  parseErr(s"Invalid escape '\\$other'", escPos)
          else
            val _ = sb.append(c)
            loop(pos + 1)

      loop(start)
    }

  private def parseHex4(hex: String, pos: Int): Either[CodecError, Char] =
    try Right(Integer.parseInt(hex, 16).toChar)
    catch
      case _: NumberFormatException =>
        parseErr(s"Invalid hex escape \\u$hex", pos)

  @tailrec
  private def scanDigits(s: String, pos: Int): Int =
    if pos < s.length && s.charAt(pos) >= '0' && s.charAt(pos) <= '9' then
      scanDigits(s, pos + 1)
    else pos

  private def parseNumber(
      s: String,
      start: Int
  ): Either[CodecError, (JVal.JNum, Int)] =
    val afterSign = if s.charAt(start) == '-' then start + 1 else start
    if afterSign >= s.length || s.charAt(afterSign) < '0' || s.charAt(
        afterSign
      ) > '9'
    then parseErr("Invalid JSON number", afterSign)
    else
      val afterInt = scanDigits(s, afterSign)
      val afterFracEither =
        if afterInt < s.length && s.charAt(afterInt) == '.' then
          val fracStart = afterInt + 1
          if fracStart >= s.length || s.charAt(fracStart) < '0' || s.charAt(
              fracStart
            ) > '9'
          then parseErr("Invalid decimal in JSON number", fracStart)
          else Right(scanDigits(s, fracStart))
        else Right(afterInt)

      afterFracEither.flatMap { afterFrac =>
        val afterExpEither =
          if afterFrac < s.length && (s.charAt(afterFrac) == 'e' || s.charAt(
              afterFrac
            ) == 'E')
          then
            val signPos = afterFrac + 1
            val expStart =
              if signPos < s.length && (s.charAt(signPos) == '+' || s.charAt(
                  signPos
                ) == '-')
              then signPos + 1
              else signPos
            if expStart >= s.length || s.charAt(expStart) < '0' || s.charAt(
                expStart
              ) > '9'
            then parseErr("Invalid exponent in JSON number", expStart)
            else Right(scanDigits(s, expStart))
          else Right(afterFrac)

        afterExpEither.map(endPos =>
          (JVal.JNum(s.substring(start, endPos)), endPos)
        )
      }

  private def parseLiteral(
      s: String,
      pos: Int,
      lit: String,
      out: JVal
  ): Either[CodecError, (JVal, Int)] =
    if s.regionMatches(pos, lit, 0, lit.length) then
      Right((out, pos + lit.length))
    else parseErr(s"Expected '$lit'", pos)
