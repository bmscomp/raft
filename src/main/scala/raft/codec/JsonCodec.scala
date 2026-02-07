package raft.codec

import raft.message.*
import raft.message.RaftMessage.*
import raft.message.CodecError.*
import raft.state.{NodeId, Log, LogType}

/** JSON-based [[SimpleMessageCodec]] implementation for the RAFT wire protocol.
  *
  * Uses a compact, hand-rolled JSON format suitable for debugging and
  * interoperability. Each message is serialized as a single JSON object with a
  * `"type"` discriminator field and a protocol version field `"v"` for forward
  * compatibility.
  *
  * Binary data (log entry payloads, snapshot chunks) is encoded as Base64
  * strings.
  *
  * Implements [[SimpleMessageCodec]] with typed [[CodecError]] error hierarchy,
  * and can be lifted into an effectful [[MessageCodec]] via
  * [[MessageCodec.fromSimple]].
  *
  * @see
  *   [[MessageCodec.fromSimple]] for lifting into an effectful context
  * @see
  *   [[raft.spi.Transport.withCodec]] for integrating with the transport layer
  */
object JsonCodec extends SimpleMessageCodec[String]:

  /** Protocol version number embedded in every serialized message. */
  val VERSION = 1

  /** Encode a [[RaftMessage]] to a JSON string.
    *
    * @param msg
    *   the message to encode
    * @return
    *   a compact JSON string representation
    */
  def encode(msg: RaftMessage): String = msg match
    case AppendEntriesRequest(term, leader, prev, prevTerm, entries, commit) =>
      val entriesJson = entries.map(encodeLog).mkString("[", ",", "]")
      s"""{"type":"AE_REQ","v":$VERSION,"term":$term,"leader":"${leader.value}","prev":$prev,"prevTerm":$prevTerm,"entries":$entriesJson,"commit":$commit}"""

    case AppendEntriesResponse(term, success, matchIdx) =>
      s"""{"type":"AE_RESP","v":$VERSION,"term":$term,"success":$success,"matchIndex":$matchIdx}"""

    case RequestVoteRequest(term, candidate, lastIdx, lastTerm, preVote) =>
      s"""{"type":"RV_REQ","v":$VERSION,"term":$term,"candidate":"${candidate.value}","lastIndex":$lastIdx,"lastTerm":$lastTerm,"preVote":$preVote}"""

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
      val dataB64 = java.util.Base64.getEncoder.encodeToString(data)
      s"""{"type":"IS_REQ","v":$VERSION,"term":$term,"leader":"${leader.value}","lastIndex":$lastIdx,"lastTerm":$lastTerm,"offset":$offset,"data":"$dataB64","done":$done}"""

    case InstallSnapshotResponse(term, success) =>
      s"""{"type":"IS_RESP","v":$VERSION,"term":$term,"success":$success}"""

    case ElectionTimeout =>
      s"""{"type":"ELECTION_TIMEOUT","v":$VERSION}"""

    case HeartbeatTimeout =>
      s"""{"type":"HEARTBEAT_TIMEOUT","v":$VERSION}"""

    case TransferLeadershipRequest(term, target) =>
      s"""{"type":"TL_REQ","v":$VERSION,"term":$term,"target":"${target.value}"}"""

    case ReadIndexRequest(requestId) =>
      s"""{"type":"RI_REQ","v":$VERSION,"requestId":"$requestId"}"""

    case ReadIndexResponse(requestId, success, readIndex) =>
      s"""{"type":"RI_RESP","v":$VERSION,"requestId":"$requestId","success":$success,"readIndex":$readIndex}"""

    case AddServerRequest(term, server, isLearner) =>
      s"""{"type":"AS_REQ","v":$VERSION,"term":$term,"server":"${server.value}","learner":$isLearner}"""

    case AddServerResponse(term, success, hint) =>
      val hintStr = hint.map(h => s""""${h.value}"""").getOrElse("null")
      s"""{"type":"AS_RESP","v":$VERSION,"term":$term,"success":$success,"hint":$hintStr}"""

    case RemoveServerRequest(term, server) =>
      s"""{"type":"RS_REQ","v":$VERSION,"term":$term,"server":"${server.value}"}"""

    case RemoveServerResponse(term, success, hint) =>
      val hintStr = hint.map(h => s""""${h.value}"""").getOrElse("null")
      s"""{"type":"RS_RESP","v":$VERSION,"term":$term,"success":$success,"hint":$hintStr}"""

  /** Decode a JSON string into a [[RaftMessage]].
    *
    * Validates the protocol version and message type, returning typed
    * [[CodecError]]s for any deserialization failures.
    *
    * @param raw
    *   the JSON string to decode
    * @return
    *   `Right(message)` on success, `Left(error)` on failure
    */
  def decode(raw: String): Either[CodecError, RaftMessage] =
    try
      val json = parseJson(raw)

      // Version check
      json.get("v").map(_.toInt) match
        case Some(v) if v < 1 || v > VERSION =>
          Left(UnsupportedVersion(v, 1, VERSION))
        case _ => // OK, continue

      json.get("type") match
        case Some("AE_REQ") =>
          for
            term <- requireField[Long](json, "term", "AE_REQ")
            leader <- requireField[String](json, "leader", "AE_REQ").map(
              NodeId(_)
            )
            prev <- requireField[Long](json, "prev", "AE_REQ")
            prevTerm <- requireField[Long](json, "prevTerm", "AE_REQ")
            commit <- requireField[Long](json, "commit", "AE_REQ")
            entries = decodeEntries(json.getOrElse("entries", "[]"))
          yield AppendEntriesRequest(
            term,
            leader,
            prev,
            prevTerm,
            entries,
            commit
          )

        case Some("AE_RESP") =>
          for
            term <- requireField[Long](json, "term", "AE_RESP")
            success <- requireField[Boolean](json, "success", "AE_RESP")
            matchIndex <- requireField[Long](json, "matchIndex", "AE_RESP")
          yield AppendEntriesResponse(term, success, matchIndex)

        case Some("RV_REQ") =>
          for
            term <- requireField[Long](json, "term", "RV_REQ")
            candidate <- requireField[String](json, "candidate", "RV_REQ").map(
              NodeId(_)
            )
            lastIndex <- requireField[Long](json, "lastIndex", "RV_REQ")
            lastTerm <- requireField[Long](json, "lastTerm", "RV_REQ")
            preVote <- requireField[Boolean](json, "preVote", "RV_REQ")
          yield RequestVoteRequest(
            term,
            candidate,
            lastIndex,
            lastTerm,
            preVote
          )

        case Some("RV_RESP") =>
          for
            term <- requireField[Long](json, "term", "RV_RESP")
            granted <- requireField[Boolean](json, "granted", "RV_RESP")
            preVote <- requireField[Boolean](json, "preVote", "RV_RESP")
          yield RequestVoteResponse(term, granted, preVote)

        case Some("IS_REQ") =>
          for
            term <- requireField[Long](json, "term", "IS_REQ")
            leader <- requireField[String](json, "leader", "IS_REQ").map(
              NodeId(_)
            )
            lastIndex <- requireField[Long](json, "lastIndex", "IS_REQ")
            lastTerm <- requireField[Long](json, "lastTerm", "IS_REQ")
            offset <- requireField[Long](json, "offset", "IS_REQ")
            dataB64 <- requireField[String](json, "data", "IS_REQ")
            done <- requireField[Boolean](json, "done", "IS_REQ")
          yield
            val data = java.util.Base64.getDecoder.decode(dataB64)
            InstallSnapshotRequest(
              term,
              leader,
              lastIndex,
              lastTerm,
              offset,
              data,
              done
            )

        case Some("IS_RESP") =>
          for
            term <- requireField[Long](json, "term", "IS_RESP")
            success <- requireField[Boolean](json, "success", "IS_RESP")
          yield InstallSnapshotResponse(term, success)

        case Some("TL_REQ") =>
          for
            term <- requireField[Long](json, "term", "TL_REQ")
            target <- requireField[String](json, "target", "TL_REQ").map(
              NodeId(_)
            )
          yield TransferLeadershipRequest(term, target)

        case Some("RI_REQ") =>
          for requestId <- requireField[String](json, "requestId", "RI_REQ")
          yield ReadIndexRequest(requestId)

        case Some("RI_RESP") =>
          for
            requestId <- requireField[String](json, "requestId", "RI_RESP")
            success <- requireField[Boolean](json, "success", "RI_RESP")
            readIndex <- requireField[Long](json, "readIndex", "RI_RESP")
          yield ReadIndexResponse(requestId, success, readIndex)

        case Some("AS_REQ") =>
          for
            term <- requireField[Long](json, "term", "AS_REQ")
            server <- requireField[String](json, "server", "AS_REQ").map(
              NodeId(_)
            )
            learner <- requireField[Boolean](json, "learner", "AS_REQ")
          yield AddServerRequest(term, server, learner)

        case Some("AS_RESP") =>
          for
            term <- requireField[Long](json, "term", "AS_RESP")
            success <- requireField[Boolean](json, "success", "AS_RESP")
          yield
            val hint = json.get("hint").filterNot(_ == "null").map(NodeId(_))
            AddServerResponse(term, success, hint)

        case Some("RS_REQ") =>
          for
            term <- requireField[Long](json, "term", "RS_REQ")
            server <- requireField[String](json, "server", "RS_REQ").map(
              NodeId(_)
            )
          yield RemoveServerRequest(term, server)

        case Some("RS_RESP") =>
          for
            term <- requireField[Long](json, "term", "RS_RESP")
            success <- requireField[Boolean](json, "success", "RS_RESP")
          yield
            val hint = json.get("hint").filterNot(_ == "null").map(NodeId(_))
            RemoveServerResponse(term, success, hint)

        case Some("ELECTION_TIMEOUT") =>
          Right(ElectionTimeout)

        case Some("HEARTBEAT_TIMEOUT") =>
          Right(HeartbeatTimeout)

        case Some(other) =>
          Left(UnknownMessageType(other))

        case None =>
          Left(MissingField("type", "message"))
    catch
      case e: NumberFormatException =>
        Left(InvalidField("numeric", "number", e.getMessage))
      case e: Exception =>
        Left(ParseError(s"Parse error: ${e.getMessage}", Some(e)))

  private def requireField[T](
      json: Map[String, String],
      name: String,
      msgType: String
  )(using
      parser: FieldParser[T]
  ): Either[CodecError, T] =
    json.get(name) match
      case Some(value) => parser.parse(value, name)
      case None        => Left(MissingField(name, msgType))

  private trait FieldParser[T]:
    def parse(value: String, fieldName: String): Either[CodecError, T]

  private given FieldParser[Long] with
    def parse(value: String, fieldName: String) =
      try Right(value.toLong)
      catch
        case _: NumberFormatException =>
          Left(InvalidField(fieldName, "Long", value))

  private given FieldParser[Int] with
    def parse(value: String, fieldName: String) =
      try Right(value.toInt)
      catch
        case _: NumberFormatException =>
          Left(InvalidField(fieldName, "Int", value))

  private given FieldParser[Boolean] with
    def parse(value: String, fieldName: String) =
      value.toLowerCase match
        case "true"  => Right(true)
        case "false" => Right(false)
        case _       => Left(InvalidField(fieldName, "Boolean", value))

  private given FieldParser[String] with
    def parse(value: String, fieldName: String) = Right(value)

  private def encodeLog(log: Log): String =
    val dataB64 = java.util.Base64.getEncoder.encodeToString(log.data)
    s"""{"index":${log.index},"term":${log.term},"type":"${log.logType}","data":"$dataB64"}"""

  private def decodeEntries(entriesStr: String): Seq[Log] =
    if entriesStr == "[]" then Seq.empty
    else
      // Simple array parsing - handles basic cases
      val content = entriesStr.stripPrefix("[").stripSuffix("]").trim
      if content.isEmpty then Seq.empty
      else
        // Split on },{ to get individual entries
        val parts = content.split("\\},\\s*\\{").toSeq
        parts.zipWithIndex.map { case (part, i) =>
          val normalized =
            if i == 0 && parts.length > 1 then part + "}"
            else if i == parts.length - 1 && parts.length > 1 then "{" + part
            else if parts.length == 1 then part
            else "{" + part + "}"
          decodeLog(normalized)
        }

  private def decodeLog(logStr: String): Log =
    val json = parseJson(logStr)
    val index = json.getOrElse("index", "0").toLong
    val term = json.getOrElse("term", "0").toLong
    val logType = LogType.valueOf(json.getOrElse("type", "Command"))
    val dataB64 = json.getOrElse("data", "")
    val data =
      if dataB64.isEmpty then Array.emptyByteArray
      else java.util.Base64.getDecoder.decode(dataB64)
    Log(index, term, logType, data)

  private def parseJson(s: String): Map[String, String] =
    val content = s.stripPrefix("{").stripSuffix("}")
    val pairs = content.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")
    pairs.flatMap { pair =>
      val kv = pair.split(":", 2)
      if kv.length == 2 then
        val key = kv(0).trim.stripPrefix("\"").stripSuffix("\"")
        val value = kv(1).trim.stripPrefix("\"").stripSuffix("\"")
        Some(key -> value)
      else None
    }.toMap
