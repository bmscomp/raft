package raft.transport.grpc

import scala.annotation.tailrec
import scala.util.control.NonFatal

import raft.message.CodecError
import raft.message.RaftMessage.{AppendEntriesRequest, AppendEntriesResponse}
import raft.multigroup.GroupEnvelope
import raft.state.{GroupId, NodeId}
import raft.transport.grpc.proto as pb

/** Native binary Protobuf codec for Multi-Raft `MultiRaftWireFrame` wire
  * payloads (`Single`, `Batch`, and compact `CoalescedHeartbeat`).
  *
  *   - Single [[GroupEnvelope]]s encode as `MultiRaftWireFrame.Frame.Single`
  *     with zero text delimiter parsing or Base64 expansion.
  *   - Homogeneous multi-group empty-heartbeat or heartbeat-ACK batches sharing
  *     `(from, to)` automatically compact into
  *     `MultiRaftWireFrame.Frame.CoalescedHeartbeat`, deduplicating sender /
  *     recipient node headers across all groups in the batch.
  *   - Pipelined data `AppendEntriesRequest` bursts or mixed batches encode as
  *     `MultiRaftWireFrame.Frame.Batch` so an entire burst traverses Netty
  *     HTTP/2 in a single `WireEnvelope` RPC.
  */
object ProtobufMultiGroupCodec:

  /** Encode a single [[GroupEnvelope]] into a binary `MultiRaftWireFrame`. */
  def encode(envelope: GroupEnvelope): Array[Byte] =
    pb.MultiRaftWireFrame(
      pb.MultiRaftWireFrame.Frame.Single(toEnvelopeProto(envelope))
    ).toByteArray

  /** Encode a sequence of [[GroupEnvelope]]s into a single binary
    * `MultiRaftWireFrame`, automatically selecting compact
    * `CoalescedHeartbeatBatchProto` when all envelopes are empty heartbeats (or
    * all `AppendEntriesResponse`s) between the same `(from, Some(to))` pair.
    */
  def encodeBatch(envelopes: Seq[GroupEnvelope]): Array[Byte] =
    if envelopes.isEmpty then Array.emptyByteArray
    else if envelopes.size == 1 then encode(envelopes.head)
    else
      tryEncodeCoalescedHeartbeat(envelopes) match
        case Some(hbProto) =>
          pb.MultiRaftWireFrame(
            pb.MultiRaftWireFrame.Frame.CoalescedHeartbeat(hbProto)
          ).toByteArray
        case None =>
          pb.MultiRaftWireFrame(
            pb.MultiRaftWireFrame.Frame.Batch(
              pb.MultiGroupBatchProto(envelopes.map(toEnvelopeProto))
            )
          ).toByteArray

  /** True iff `raw` is a valid `MultiRaftWireFrame` carrying a
    * `CoalescedHeartbeatBatchProto` frame.
    */
  def isCoalescedHeartbeatFrame(raw: Array[Byte]): Boolean =
    if raw.isEmpty then false
    else
      try
        pb.MultiRaftWireFrame
          .parseFrom(raw)
          .frame
          .isCoalescedHeartbeat
      catch case NonFatal(_) => false

  /** Decode a binary `MultiRaftWireFrame` into one or more [[GroupEnvelope]]s.
    */
  def decode(raw: Array[Byte]): Either[CodecError, Vector[GroupEnvelope]] =
    if raw.isEmpty then
      Left(CodecError.ParseError("Empty Protobuf MultiRaftWireFrame"))
    else
      try
        val frame = pb.MultiRaftWireFrame.parseFrom(raw)
        frame.frame match
          case pb.MultiRaftWireFrame.Frame.Empty =>
            Left(CodecError.UnknownMessageType("MultiRaftWireFrame.Empty"))

          case pb.MultiRaftWireFrame.Frame.Single(envProto) =>
            fromEnvelopeProto(envProto).map(Vector(_))

          case pb.MultiRaftWireFrame.Frame.Batch(batchProto) =>
            decodeBatchEnvelopes(batchProto.envelopes.toVector)

          case pb.MultiRaftWireFrame.Frame.CoalescedHeartbeat(hbProto) =>
            Right(fromCoalescedHeartbeatProto(hbProto))
      catch
        case NonFatal(e) =>
          Left(
            CodecError.ParseError(
              s"Malformed Protobuf MultiRaftWireFrame: ${e.getMessage}",
              Some(e)
            )
          )

  private def decodeBatchEnvelopes(
      protos: Vector[pb.MultiGroupEnvelopeProto]
  ): Either[CodecError, Vector[GroupEnvelope]] =
    @tailrec
    def loop(
        idx: Int,
        acc: Vector[GroupEnvelope]
    ): Either[CodecError, Vector[GroupEnvelope]] =
      if idx >= protos.length then Right(acc)
      else
        fromEnvelopeProto(protos(idx)) match
          case Right(env) => loop(idx + 1, acc :+ env)
          case Left(ce)   => Left(ce)
    loop(0, Vector.empty)

  private def toEnvelopeProto(env: GroupEnvelope): pb.MultiGroupEnvelopeProto =
    pb.MultiGroupEnvelopeProto(
      groupId = env.groupId.value,
      fromId = env.from.value,
      hasToId = env.to.isDefined,
      toId = env.to.map(_.value).getOrElse(""),
      payload = Some(ProtobufMessageCodec.toProtoPayload(env.message))
    )

  private def fromEnvelopeProto(
      p: pb.MultiGroupEnvelopeProto
  ): Either[CodecError, GroupEnvelope] =
    p.payload match
      case None =>
        Left(
          CodecError.InvalidField(
            "MultiGroupEnvelopeProto.payload",
            "defined RaftWirePayload",
            "None"
          )
        )
      case Some(wirePayload) =>
        ProtobufMessageCodec.fromProtoPayload(wirePayload).map { msg =>
          GroupEnvelope(
            groupId = GroupId(p.groupId),
            from = NodeId(p.fromId),
            message = msg,
            to = if p.hasToId then Some(NodeId(p.toId)) else None
          )
        }

  private def tryEncodeCoalescedHeartbeat(
      envelopes: Seq[GroupEnvelope]
  ): Option[pb.CoalescedHeartbeatBatchProto] =
    val first = envelopes.head
    first.to.flatMap { dest =>
      val sameRoute =
        envelopes.forall(e => e.from == first.from && e.to.contains(dest))
      if !sameRoute then None
      else
        val allEmptyAppends = envelopes.forall { e =>
          e.message match
            case ae: AppendEntriesRequest => ae.entries.isEmpty
            case _                        => false
        }
        val allResponses =
          envelopes.forall(_.message.isInstanceOf[AppendEntriesResponse])

        if allEmptyAppends then
          val beats = envelopes.map { e =>
            val ae = e.message.asInstanceOf[AppendEntriesRequest]
            pb.CoalescedHeartbeatBeatProto(
              groupId = e.groupId.value,
              term = ae.term,
              leaderId =
                if ae.leaderId == first.from then "" else ae.leaderId.value,
              prevLogIndex = ae.prevLogIndex,
              prevLogTerm = ae.prevLogTerm,
              leaderCommit = ae.leaderCommit
            )
          }
          Some(
            pb.CoalescedHeartbeatBatchProto(
              fromId = first.from.value,
              toId = dest.value,
              isResponse = false,
              beats = beats
            )
          )
        else if allResponses then
          val beats = envelopes.map { e =>
            val r = e.message.asInstanceOf[AppendEntriesResponse]
            pb.CoalescedHeartbeatBeatProto(
              groupId = e.groupId.value,
              term = r.term,
              success = r.success,
              matchIndex = r.matchIndex,
              hasConflictIndex = r.conflictIndex.isDefined,
              conflictIndex = r.conflictIndex.getOrElse(0L),
              hasConflictTerm = r.conflictTerm.isDefined,
              conflictTerm = r.conflictTerm.getOrElse(0L)
            )
          }
          Some(
            pb.CoalescedHeartbeatBatchProto(
              fromId = first.from.value,
              toId = dest.value,
              isResponse = true,
              beats = beats
            )
          )
        else None
    }

  private def fromCoalescedHeartbeatProto(
      hb: pb.CoalescedHeartbeatBatchProto
  ): Vector[GroupEnvelope] =
    val fromNode = NodeId(hb.fromId)
    val toOpt = if hb.toId.nonEmpty then Some(NodeId(hb.toId)) else None
    if !hb.isResponse then
      hb.beats.iterator.map { b =>
        val resolvedLeader =
          if b.leaderId.isEmpty then fromNode else NodeId(b.leaderId)
        GroupEnvelope(
          groupId = GroupId(b.groupId),
          from = fromNode,
          to = toOpt,
          message = AppendEntriesRequest(
            term = b.term,
            leaderId = resolvedLeader,
            prevLogIndex = b.prevLogIndex,
            prevLogTerm = b.prevLogTerm,
            entries = Seq.empty,
            leaderCommit = b.leaderCommit
          )
        )
      }.toVector
    else
      hb.beats.iterator.map { b =>
        GroupEnvelope(
          groupId = GroupId(b.groupId),
          from = fromNode,
          to = toOpt,
          message = AppendEntriesResponse(
            term = b.term,
            success = b.success,
            matchIndex = b.matchIndex,
            conflictIndex =
              if b.hasConflictIndex then Some(b.conflictIndex) else None,
            conflictTerm =
              if b.hasConflictTerm then Some(b.conflictTerm) else None
          )
        )
      }.toVector
