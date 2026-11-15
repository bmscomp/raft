package raft.codec

import java.nio.charset.StandardCharsets
import java.util.Base64
import raft.message.*
import raft.message.RaftMessage.*
import raft.message.CodecError.*
import raft.state.{Log, LogType, NodeId}

/** Zero-dependency, escape-safe JSON [[SimpleMessageCodec]] for the RAFT wire
  * protocol.
  *
  * Uses a lightweight recursive-descent JSON parser so arbitrary strings
  * containing quotes, backslashes, newlines, commas, colons, braces, brackets,
  * or Unicode escapes round-trip losslessly without regex fragility or third-
  * party dependencies.
  */
object JsonCodec extends SimpleMessageCodec[String]:

  val VERSION: Int = 1

  /** UTF-8 byte-array projection of [[JsonCodec]] for binary transports such as
    * TCP, UDP, and gRPC.
    */
  val byteCodec: SimpleMessageCodec[Array[Byte]] =
    mapWire(
      _.getBytes(StandardCharsets.UTF_8),
      bytes => new String(bytes, StandardCharsets.UTF_8)
    )

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
          data,
          done
        ) =>
      val b64 = Base64.getEncoder.encodeToString(data)
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
      val hintStr = hint.map(h => quote(h.value)).getOrElse("null")
      s"""{"type":"ADD_RESP","v":$VERSION,"term":$term,"success":$success,"hint":$hintStr}"""

    case RemoveServerRequest(term, server) =>
      s"""{"type":"REM_REQ","v":$VERSION,"term":$term,"server":${quote(
          server.value
        )}}"""

    case RemoveServerResponse(term, success, hint) =>
      val hintStr = hint.map(h => quote(h.value)).getOrElse("null")
      s"""{"type":"REM_RESP","v":$VERSION,"term":$term,"success":$success,"hint":$hintStr}"""

    case ElectionTimeout =>
      s"""{"type":"ELECTION_TIMEOUT","v":$VERSION}"""

    case HeartbeatTimeout =>
      s"""{"type":"HEARTBEAT_TIMEOUT","v":$VERSION}"""

    case CheckQuorumTimeout =>
      s"""{"type":"CHECK_QUORUM_TIMEOUT","v":$VERSION}"""

  private def encodeLog(log: Log): String =
    val b64 = Base64.getEncoder.encodeToString(log.data)
    s"""{"idx":${log.index},"term":${log.term},"type":${quote(
        log.logType.toString
      )},"data":${quote(b64)}}"""

  private def quote(s: String): String =
    val sb = new java.lang.StringBuilder(s.length + 4)
    sb.append('"')
    var i = 0
    while i < s.length do
      s.charAt(i) match
        case '"'           => sb.append("\\\"")
        case '\\'          => sb.append("\\\\")
        case '\b'          => sb.append("\\b")
        case '\f'          => sb.append("\\f")
        case '\n'          => sb.append("\\n")
        case '\r'          => sb.append("\\r")
        case '\t'          => sb.append("\\t")
        case c if c < 0x20 =>
          sb.append(f"\\u${c.toInt}%04x")
        case c => sb.append(c)
      i += 1
    sb.append('"')
    sb.toString

  // === Recursive-Descent JSON AST & Parser ===

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
        data <- decodeBase64(dataB64, "data")
        done <- getBool(fields, "done", msgType)
      yield InstallSnapshotRequest(
        term,
        NodeId(leader),
        lastIdx,
        lastTerm,
        offset,
        data,
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
      case Some(other) => Left(InvalidField(key, "String|null", other.toString))

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
      case Some(other) => Left(InvalidField(key, "Long|null", other.toString))

  private def getIntOpt(
      m: Map[String, JVal],
      key: String
  ): Either[CodecError, Option[Int]] =
    m.get(key) match
      case None | Some(JVal.JNull) => Right(None)
      case Some(JVal.JNum(raw))    =>
        raw.toIntOption.map(Some(_)).toRight(InvalidField(key, "Int", raw))
      case Some(other) => Left(InvalidField(key, "Int|null", other.toString))

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
        Left(InvalidField(key, "Boolean|null", other.toString))

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
      bytes <- decodeBase64(b64, "Log.data")
    yield Log(idx, term, logType, bytes)

  private def parseLogType(raw: String): Either[CodecError, LogType] =
    LogType.values.find(_.toString == raw) match
      case Some(lt) => Right(lt)
      case None => Left(InvalidField("type", "Command|NoOp|Configuration", raw))

  private def decodeBase64(
      b64: String,
      fieldName: String
  ): Either[CodecError, Array[Byte]] =
    try Right(Base64.getDecoder.decode(b64))
    catch
      case _: IllegalArgumentException =>
        Left(InvalidField(fieldName, "Base64", b64))

  private def parseJsonObject(
      input: String
  ): Either[CodecError, Map[String, JVal]] =
    val parser = new Parser(input)
    try
      val v = parser.parseValue()
      parser.skipWs()
      if !parser.isEof then
        Left(ParseError(s"Unexpected trailing characters at pos ${parser.pos}"))
      else
        v match
          case JVal.JObj(fields) => Right(fields)
          case _ => Left(ParseError("Expected top-level JSON object"))
    catch case e: ParseException => Left(ParseError(e.getMessage))

  private final class ParseException(msg: String) extends RuntimeException(msg)

  private final class Parser(s: String):
    var pos: Int = 0
    private val len: Int = s.length

    def isEof: Boolean = pos >= len

    def skipWs(): Unit =
      while pos < len && {
          val c = s.charAt(pos)
          c == ' ' || c == '\t' || c == '\n' || c == '\r'
        }
      do pos += 1

    private def fail(msg: String): Nothing =
      throw new ParseException(s"$msg (at offset $pos)")

    private def expect(ch: Char): Unit =
      skipWs()
      if pos >= len || s.charAt(pos) != ch then fail(s"Expected '$ch'")
      pos += 1

    def parseValue(): JVal =
      skipWs()
      if pos >= len then fail("Unexpected end of JSON input")
      s.charAt(pos) match
        case '{' => parseObject()
        case '[' => parseArray()
        case '"' => JVal.JStr(parseString())
        case 't' => parseLiteral("true", JVal.JBool(true))
        case 'f' => parseLiteral("false", JVal.JBool(false))
        case 'n' => parseLiteral("null", JVal.JNull)
        case c if c == '-' || (c >= '0' && c <= '9') => parseNumber()
        case c => fail(s"Unexpected token '$c'")

    private def parseObject(): JVal.JObj =
      expect('{')
      skipWs()
      if pos < len && s.charAt(pos) == '}' then
        pos += 1
        JVal.JObj(Map.empty)
      else
        val acc = Map.newBuilder[String, JVal]
        var more = true
        while more do
          skipWs()
          if pos >= len || s.charAt(pos) != '"' then
            fail("Expected string key in JSON object")
          val k = parseString()
          expect(':')
          val v = parseValue()
          acc += (k -> v)
          skipWs()
          if pos < len && s.charAt(pos) == ',' then pos += 1
          else if pos < len && s.charAt(pos) == '}' then
            pos += 1
            more = false
          else fail("Expected ',' or '}' in JSON object")
        JVal.JObj(acc.result())

    private def parseArray(): JVal.JArr =
      expect('[')
      skipWs()
      if pos < len && s.charAt(pos) == ']' then
        pos += 1
        JVal.JArr(Vector.empty)
      else
        val acc = Vector.newBuilder[JVal]
        var more = true
        while more do
          acc += parseValue()
          skipWs()
          if pos < len && s.charAt(pos) == ',' then pos += 1
          else if pos < len && s.charAt(pos) == ']' then
            pos += 1
            more = false
          else fail("Expected ',' or ']' in JSON array")
        JVal.JArr(acc.result())

    private def parseString(): String =
      expect('"')
      val sb = new java.lang.StringBuilder
      var done = false
      def putChar(ch: Char): Unit =
        val _ = sb.append(ch)

      while !done do
        if pos >= len then fail("Unterminated JSON string")
        val c = s.charAt(pos)
        pos += 1
        if c == '"' then done = true
        else if c == '\\' then
          if pos >= len then fail("Unterminated escape sequence")
          val esc = s.charAt(pos)
          pos += 1
          esc match
            case '"'  => putChar('"')
            case '\\' => putChar('\\')
            case '/'  => putChar('/')
            case 'b'  => putChar('\b')
            case 'f'  => putChar('\f')
            case 'n'  => putChar('\n')
            case 'r'  => putChar('\r')
            case 't'  => putChar('\t')
            case 'u'  =>
              if pos + 4 > len then fail("Incomplete \\u escape")
              val hex = s.substring(pos, pos + 4)
              try
                putChar(Integer.parseInt(hex, 16).toChar)
                pos += 4
              catch
                case _: NumberFormatException =>
                  fail(s"Invalid hex escape \\u$hex")
            case other => fail(s"Invalid escape '\\$other'")
        else putChar(c)
      sb.toString

    private def parseNumber(): JVal.JNum =
      val start = pos
      if s.charAt(pos) == '-' then pos += 1
      if pos >= len || s.charAt(pos) < '0' || s.charAt(pos) > '9' then
        fail("Invalid JSON number")
      while pos < len && s.charAt(pos) >= '0' && s.charAt(pos) <= '9' do
        pos += 1
      if pos < len && s.charAt(pos) == '.' then
        pos += 1
        if pos >= len || s.charAt(pos) < '0' || s.charAt(pos) > '9' then
          fail("Invalid decimal in JSON number")
        while pos < len && s.charAt(pos) >= '0' && s.charAt(pos) <= '9' do
          pos += 1
      if pos < len && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E') then
        pos += 1
        if pos < len && (s.charAt(pos) == '+' || s.charAt(pos) == '-') then
          pos += 1
        if pos >= len || s.charAt(pos) < '0' || s.charAt(pos) > '9' then
          fail("Invalid exponent in JSON number")
        while pos < len && s.charAt(pos) >= '0' && s.charAt(pos) <= '9' do
          pos += 1
      JVal.JNum(s.substring(start, pos))

    private def parseLiteral(lit: String, out: JVal): JVal =
      if s.regionMatches(pos, lit, 0, lit.length) then
        pos += lit.length
        out
      else fail(s"Expected '$lit'")
