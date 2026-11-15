package raft.multigroup

import raft.state.{GroupId, NodeId}
import raft.message.{SimpleMessageCodec, CodecError}

/** Codec that wraps a standard [[SimpleMessageCodec]] to handle
  * [[GroupEnvelope]] serialization (including optional unicast target routing).
  *
  * Wire format:
  *   - Broadcast (`to = None`): `groupId|GRPENV|fromNodeId|GRPENV|innerMessage`
  *   - Unicast (`to = Some(dest)`):
  *     `groupId|GRPENV|fromNodeId|GRPDST|destNodeId|GRPENV|innerMessage`
  *
  * @param inner
  *   the standard message codec for encoding/decoding
  *   [[raft.message.RaftMessage]]
  */
class GroupAwareCodec(inner: SimpleMessageCodec[String]):

  private val Delimiter = "|GRPENV|"
  private val DestDelimiter = "|GRPDST|"

  /** Encode a [[GroupEnvelope]] to a wire-format string. */
  def encode(envelope: GroupEnvelope): String =
    val encoded = inner.encode(envelope.message)
    val routeHeader = envelope.to match
      case Some(dest) => s"${envelope.from.value}$DestDelimiter${dest.value}"
      case None       => envelope.from.value
    s"${envelope.groupId.value}$Delimiter$routeHeader$Delimiter$encoded"

  /** Decode a wire-format string into a [[GroupEnvelope]]. */
  def decode(raw: String): Either[CodecError, GroupEnvelope] =
    raw.split(java.util.regex.Pattern.quote(Delimiter), 3) match
      case Array(groupStr, routeStr, msgStr) =>
        val (fromId, toOpt) =
          routeStr.split(java.util.regex.Pattern.quote(DestDelimiter), 2) match
            case Array(f, t) => (NodeId(f), Some(NodeId(t)))
            case _           => (NodeId(routeStr), None)
        inner.decode(msgStr).map { msg =>
          GroupEnvelope(GroupId(groupStr), fromId, msg, toOpt)
        }
      case _ =>
        Left(
          CodecError.ParseError(
            s"Invalid GroupEnvelope format: missing $Delimiter delimiter"
          )
        )
