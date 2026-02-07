package raft.multigroup

import raft.state.{GroupId, NodeId}
import raft.message.{RaftMessage, SimpleMessageCodec, CodecError}

/** Codec that wraps a standard [[SimpleMessageCodec]] to handle
  * [[GroupEnvelope]] serialization.
  *
  * The encoding format prefixes the group ID and sender node ID to the inner
  * message encoding, separated by a delimiter. This allows the multi-group
  * transport to multiplex messages for different groups over a single wire
  * connection.
  *
  * Wire format: `groupId|GRPENV|fromNodeId|GRPENV|innerMessage`
  *
  * @param inner
  *   the standard message codec for encoding/decoding [[RaftMessage]]
  * @see
  *   [[MultiGroupTransport]] for the transport that uses this codec
  * @see
  *   [[GroupEnvelope]] for the multiplexing wrapper
  */
class GroupAwareCodec(inner: SimpleMessageCodec[String]):

  private val Delimiter = "|GRPENV|"

  /** Encode a [[GroupEnvelope]] to a wire-format string.
    *
    * @param envelope
    *   the group-tagged message to encode
    * @return
    *   the wire-format string
    */
  def encode(envelope: GroupEnvelope): String =
    val encoded = inner.encode(envelope.message)
    s"${envelope.groupId.value}$Delimiter${envelope.from.value}$Delimiter$encoded"

  /** Decode a wire-format string into a [[GroupEnvelope]].
    *
    * @param raw
    *   the wire-format string to decode
    * @return
    *   the decoded envelope, or a codec error
    */
  def decode(raw: String): Either[CodecError, GroupEnvelope] =
    raw.split(java.util.regex.Pattern.quote(Delimiter), 3) match
      case Array(groupStr, fromStr, msgStr) =>
        inner.decode(msgStr).map { msg =>
          GroupEnvelope(GroupId(groupStr), NodeId(fromStr), msg)
        }
      case _ =>
        Left(
          CodecError.ParseError(
            s"Invalid GroupEnvelope format: missing $Delimiter delimiter"
          )
        )
