package raft.transport.grpc

import scala.util.control.NonFatal

import com.google.protobuf.ByteString
import raft.message.{CodecError, RaftMessage, SimpleMessageCodec}
import raft.state.{Log, LogType, NodeId}
import raft.transport.grpc.proto as pb

/** Native binary Protobuf [[SimpleMessageCodec]] for all [[RaftMessage]]
  * variants.
  *
  * Eliminates JSON text serialization and Base64 expansion for log entry and
  * snapshot chunk byte payloads when transporting messages over
  * [[GrpcTransport]] or [[raft.multigroup.GroupAwareCodec]].
  */
object ProtobufMessageCodec extends SimpleMessageCodec[Array[Byte]]:

  def encode(msg: RaftMessage): Array[Byte] =
    val inner: pb.RaftWirePayload.Message = msg match
      case m: RaftMessage.AppendEntriesRequest =>
        pb.RaftWirePayload.Message.AppendEntriesReq(
          pb.AppendEntriesRequest(
            term = m.term,
            leaderId = m.leaderId.value,
            prevLogIndex = m.prevLogIndex,
            prevLogTerm = m.prevLogTerm,
            entries = m.entries.map(encodeLogEntry),
            leaderCommit = m.leaderCommit
          )
        )

      case m: RaftMessage.AppendEntriesResponse =>
        pb.RaftWirePayload.Message.AppendEntriesResp(
          pb.AppendEntriesResponse(
            term = m.term,
            success = m.success,
            matchIndex = m.matchIndex,
            hasConflictIndex = m.conflictIndex.isDefined,
            conflictIndex = m.conflictIndex.getOrElse(0L),
            hasConflictTerm = m.conflictTerm.isDefined,
            conflictTerm = m.conflictTerm.getOrElse(0L)
          )
        )

      case m: RaftMessage.RequestVoteRequest =>
        pb.RaftWirePayload.Message.RequestVoteReq(
          pb.RequestVoteRequest(
            term = m.term,
            candidateId = m.candidateId.value,
            lastLogIndex = m.lastLogIndex,
            lastLogTerm = m.lastLogTerm,
            isPreVote = m.isPreVote,
            isTransfer = m.isTransfer
          )
        )

      case m: RaftMessage.RequestVoteResponse =>
        pb.RaftWirePayload.Message.RequestVoteResp(
          pb.RequestVoteResponse(
            term = m.term,
            voteGranted = m.voteGranted,
            isPreVote = m.isPreVote
          )
        )

      case m: RaftMessage.InstallSnapshotRequest =>
        pb.RaftWirePayload.Message.InstallSnapshotReq(
          pb.InstallSnapshotChunk(
            term = m.term,
            leaderId = m.leaderId.value,
            lastIncludedIndex = m.lastIncludedIndex,
            lastIncludedTerm = m.lastIncludedTerm,
            offset = m.offset,
            data = ByteString.copyFrom(m.data),
            done = m.done
          )
        )

      case m: RaftMessage.InstallSnapshotResponse =>
        pb.RaftWirePayload.Message.InstallSnapshotResp(
          pb.InstallSnapshotResponse(
            term = m.term,
            success = m.success,
            matchIndex = m.matchIndex
          )
        )

      case m: RaftMessage.TransferLeadershipRequest =>
        pb.RaftWirePayload.Message.TransferLeadershipReq(
          pb.TransferLeadershipRequest(
            term = m.term,
            targetId = m.target.value
          )
        )

      case m: RaftMessage.TimeoutNowRequest =>
        pb.RaftWirePayload.Message.TimeoutNowReq(
          pb.TimeoutNowRequest(
            term = m.term,
            leaderId = m.leaderId.value
          )
        )

      case m: RaftMessage.ReadIndexRequest =>
        pb.RaftWirePayload.Message.ReadIndexReq(
          pb.ReadIndexRequest(requestId = m.requestId)
        )

      case m: RaftMessage.ReadIndexResponse =>
        pb.RaftWirePayload.Message.ReadIndexResp(
          pb.ReadIndexResponse(
            requestId = m.requestId,
            success = m.success,
            readIndex = m.readIndex
          )
        )

      case m: RaftMessage.AddServerRequest =>
        pb.RaftWirePayload.Message.AddServerReq(
          pb.AddServerRequest(
            term = m.term,
            newServer = m.newServer.value,
            isLearner = m.isLearner
          )
        )

      case m: RaftMessage.AddServerResponse =>
        pb.RaftWirePayload.Message.AddServerResp(
          pb.AddServerResponse(
            term = m.term,
            success = m.success,
            hasLeaderHint = m.leaderHint.isDefined,
            leaderHint = m.leaderHint.map(_.value).getOrElse("")
          )
        )

      case m: RaftMessage.RemoveServerRequest =>
        pb.RaftWirePayload.Message.RemoveServerReq(
          pb.RemoveServerRequest(
            term = m.term,
            server = m.server.value
          )
        )

      case m: RaftMessage.RemoveServerResponse =>
        pb.RaftWirePayload.Message.RemoveServerResp(
          pb.RemoveServerResponse(
            term = m.term,
            success = m.success,
            hasLeaderHint = m.leaderHint.isDefined,
            leaderHint = m.leaderHint.map(_.value).getOrElse("")
          )
        )

      case RaftMessage.ElectionTimeout =>
        pb.RaftWirePayload.Message.TimerTick(
          pb.TimerTick(pb.TimerKind.ELECTION_TIMEOUT)
        )

      case RaftMessage.HeartbeatTimeout =>
        pb.RaftWirePayload.Message.TimerTick(
          pb.TimerTick(pb.TimerKind.HEARTBEAT_TIMEOUT)
        )

      case RaftMessage.CheckQuorumTimeout =>
        pb.RaftWirePayload.Message.TimerTick(
          pb.TimerTick(pb.TimerKind.CHECK_QUORUM_TIMEOUT)
        )

    pb.RaftWirePayload(inner).toByteArray

  def decode(raw: Array[Byte]): Either[CodecError, RaftMessage] =
    if raw.isEmpty then
      Left(CodecError.ParseError("Empty Protobuf RaftWirePayload"))
    else
      try
        val payload = pb.RaftWirePayload.parseFrom(raw)
        decodePayload(payload)
      catch
        case NonFatal(e) =>
          Left(
            CodecError.ParseError(
              s"Malformed Protobuf RaftWirePayload: ${e.getMessage}",
              Some(e)
            )
          )

  private def decodePayload(
      payload: pb.RaftWirePayload
  ): Either[CodecError, RaftMessage] =
    payload.message match
      case pb.RaftWirePayload.Message.Empty =>
        Left(CodecError.UnknownMessageType("RaftWirePayload.Empty"))

      case pb.RaftWirePayload.Message.AppendEntriesReq(m) =>
        decodeEntries(m.entries).map { entries =>
          RaftMessage.AppendEntriesRequest(
            term = m.term,
            leaderId = NodeId(m.leaderId),
            prevLogIndex = m.prevLogIndex,
            prevLogTerm = m.prevLogTerm,
            entries = entries,
            leaderCommit = m.leaderCommit
          )
        }

      case pb.RaftWirePayload.Message.AppendEntriesResp(m) =>
        Right(
          RaftMessage.AppendEntriesResponse(
            term = m.term,
            success = m.success,
            matchIndex = m.matchIndex,
            conflictIndex =
              if m.hasConflictIndex then Some(m.conflictIndex) else None,
            conflictTerm =
              if m.hasConflictTerm then Some(m.conflictTerm) else None
          )
        )

      case pb.RaftWirePayload.Message.RequestVoteReq(m) =>
        Right(
          RaftMessage.RequestVoteRequest(
            term = m.term,
            candidateId = NodeId(m.candidateId),
            lastLogIndex = m.lastLogIndex,
            lastLogTerm = m.lastLogTerm,
            isPreVote = m.isPreVote,
            isTransfer = m.isTransfer
          )
        )

      case pb.RaftWirePayload.Message.RequestVoteResp(m) =>
        Right(
          RaftMessage.RequestVoteResponse(
            term = m.term,
            voteGranted = m.voteGranted,
            isPreVote = m.isPreVote
          )
        )

      case pb.RaftWirePayload.Message.InstallSnapshotReq(m) =>
        Right(
          RaftMessage.InstallSnapshotRequest(
            term = m.term,
            leaderId = NodeId(m.leaderId),
            lastIncludedIndex = m.lastIncludedIndex,
            lastIncludedTerm = m.lastIncludedTerm,
            offset = m.offset,
            data = m.data.toByteArray,
            done = m.done
          )
        )

      case pb.RaftWirePayload.Message.InstallSnapshotResp(m) =>
        Right(
          RaftMessage.InstallSnapshotResponse(
            term = m.term,
            success = m.success,
            matchIndex = m.matchIndex
          )
        )

      case pb.RaftWirePayload.Message.TransferLeadershipReq(m) =>
        Right(
          RaftMessage.TransferLeadershipRequest(
            term = m.term,
            target = NodeId(m.targetId)
          )
        )

      case pb.RaftWirePayload.Message.TimeoutNowReq(m) =>
        Right(
          RaftMessage.TimeoutNowRequest(
            term = m.term,
            leaderId = NodeId(m.leaderId)
          )
        )

      case pb.RaftWirePayload.Message.ReadIndexReq(m) =>
        Right(RaftMessage.ReadIndexRequest(requestId = m.requestId))

      case pb.RaftWirePayload.Message.ReadIndexResp(m) =>
        Right(
          RaftMessage.ReadIndexResponse(
            requestId = m.requestId,
            success = m.success,
            readIndex = m.readIndex
          )
        )

      case pb.RaftWirePayload.Message.AddServerReq(m) =>
        Right(
          RaftMessage.AddServerRequest(
            term = m.term,
            newServer = NodeId(m.newServer),
            isLearner = m.isLearner
          )
        )

      case pb.RaftWirePayload.Message.AddServerResp(m) =>
        Right(
          RaftMessage.AddServerResponse(
            term = m.term,
            success = m.success,
            leaderHint =
              if m.hasLeaderHint then Some(NodeId(m.leaderHint)) else None
          )
        )

      case pb.RaftWirePayload.Message.RemoveServerReq(m) =>
        Right(
          RaftMessage.RemoveServerRequest(
            term = m.term,
            server = NodeId(m.server)
          )
        )

      case pb.RaftWirePayload.Message.RemoveServerResp(m) =>
        Right(
          RaftMessage.RemoveServerResponse(
            term = m.term,
            success = m.success,
            leaderHint =
              if m.hasLeaderHint then Some(NodeId(m.leaderHint)) else None
          )
        )

      case pb.RaftWirePayload.Message.TimerTick(t) =>
        t.kind match
          case pb.TimerKind.ELECTION_TIMEOUT =>
            Right(RaftMessage.ElectionTimeout)
          case pb.TimerKind.HEARTBEAT_TIMEOUT =>
            Right(RaftMessage.HeartbeatTimeout)
          case pb.TimerKind.CHECK_QUORUM_TIMEOUT =>
            Right(RaftMessage.CheckQuorumTimeout)
          case other =>
            Left(
              CodecError.InvalidField(
                "TimerTick.kind",
                "ELECTION_TIMEOUT|HEARTBEAT_TIMEOUT|CHECK_QUORUM_TIMEOUT",
                other.toString
              )
            )

  private def encodeLogEntry(entry: Log): pb.LogEntry =
    val pbType = entry.logType match
      case LogType.Command       => pb.EntryType.COMMAND
      case LogType.NoOp          => pb.EntryType.NO_OP
      case LogType.Configuration => pb.EntryType.CONFIG_CHANGE
    pb.LogEntry(
      index = entry.index,
      term = entry.term,
      data = ByteString.copyFrom(entry.data),
      entryType = pbType
    )

  private def decodeEntries(
      entries: Seq[pb.LogEntry]
  ): Either[CodecError, Vector[Log]] =
    val builder = Vector.newBuilder[Log]
    val iter = entries.iterator
    var err: Option[CodecError] = None
    while iter.hasNext && err.isEmpty do
      val e = iter.next()
      e.entryType match
        case pb.EntryType.COMMAND =>
          builder += Log(e.index, e.term, LogType.Command, e.data.toByteArray)
        case pb.EntryType.NO_OP =>
          builder += Log(e.index, e.term, LogType.NoOp, e.data.toByteArray)
        case pb.EntryType.CONFIG_CHANGE =>
          builder += Log(
            e.index,
            e.term,
            LogType.Configuration,
            e.data.toByteArray
          )
        case other =>
          err = Some(
            CodecError.InvalidField(
              "LogEntry.entryType",
              "COMMAND|NO_OP|CONFIG_CHANGE",
              other.toString
            )
          )
    err.toLeft(builder.result())
