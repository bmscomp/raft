package raft.multigroup

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.annotation.tailrec

import raft.message.{CodecError, SimpleMessageCodec}
import raft.state.{GroupId, NodeId}

/** Codec wrapping a `String`-based [[SimpleMessageCodec]] for [[GroupEnvelope]]
  * serialization.
  */
class GroupAwareCodec(inner: SimpleMessageCodec[String]):

  private val Delimiter = "|GRPENV|"
  private val DestDelimiter = "|GRPDST|"

  def encode(envelope: GroupEnvelope): String =
    val encoded = inner.encode(envelope.message)
    val routeHeader = envelope.to match
      case Some(dest) => s"${envelope.from.value}$DestDelimiter${dest.value}"
      case None       => envelope.from.value
    s"${envelope.groupId.value}$Delimiter$routeHeader$Delimiter$encoded"

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

object GroupAwareCodec:
  def binary(inner: SimpleMessageCodec[Array[Byte]]): BinaryGroupAwareCodec =
    new BinaryGroupAwareCodec(inner)

/** Pure `@tailrec` length-prefixed binary `Array[Byte]` codec for single and
  * batched [[GroupEnvelope]] frames (`0` `var`s, `0` `while` loops).
  */
final class BinaryGroupAwareCodec(inner: SimpleMessageCodec[Array[Byte]]):
  private val MagicSingle: Byte = 0x4d // 'M'
  private val MagicBatch: Byte = 0x42 // 'B'

  def encode(envelope: GroupEnvelope): Array[Byte] =
    val groupBytes = envelope.groupId.value.getBytes(StandardCharsets.UTF_8)
    val fromBytes = envelope.from.value.getBytes(StandardCharsets.UTF_8)
    val toBytes =
      envelope.to
        .map(_.value.getBytes(StandardCharsets.UTF_8))
        .getOrElse(Array.emptyByteArray)
    val payloadBytes = inner.encode(envelope.message)

    val total =
      1 + 4 + groupBytes.length + 4 + fromBytes.length + 4 + toBytes.length + 4 + payloadBytes.length
    val buf = ByteBuffer.allocate(total)
    val _ = buf
      .put(MagicSingle)
      .putInt(groupBytes.length)
      .put(groupBytes)
      .putInt(fromBytes.length)
      .put(fromBytes)
      .putInt(if envelope.to.isDefined then toBytes.length else -1)
    if envelope.to.isDefined then
      val _ = buf.put(toBytes)
    val _ = buf.putInt(payloadBytes.length).put(payloadBytes)
    buf.array()

  def encodeBatch(envelopes: Seq[GroupEnvelope]): Array[Byte] =
    if envelopes.size == 1 then encode(envelopes.head)
    else
      val encodedFrames = envelopes.map(encode)
      val total = 1 + 4 + encodedFrames.map(4 + _.length).sum
      val buf = ByteBuffer.allocate(total)
      val _ = buf.put(MagicBatch).putInt(encodedFrames.size)
      encodedFrames.foreach { frame =>
        val _ = buf.putInt(frame.length).put(frame)
      }
      buf.array()

  def decode(raw: Array[Byte]): Either[CodecError, GroupEnvelope] =
    decodeBatch(raw).flatMap {
      case Vector(single) => Right(single)
      case other          =>
        Left(
          CodecError.ParseError(
            s"Expected single GroupEnvelope, decoded batch of ${other.size}"
          )
        )
    }

  def decodeBatch(raw: Array[Byte]): Either[CodecError, Vector[GroupEnvelope]] =
    if raw.isEmpty then
      Left(CodecError.ParseError("Empty binary GroupEnvelope"))
    else
      try
        val buf = ByteBuffer.wrap(raw)
        buf.get() match
          case MagicSingle =>
            decodeSingleBody(buf).map(Vector(_))
          case MagicBatch =>
            if buf.remaining() < 4 then
              Left(
                CodecError.ParseError(
                  "Truncated BinaryGroupAwareCodec batch header"
                )
              )
            else
              val count = buf.getInt()
              if count < 0 || count > 65536 then
                Left(CodecError.ParseError(s"Invalid batch count: $count"))
              else decodeFramesLoop(buf, count, Vector.empty)
          case other =>
            Left(
              CodecError.ParseError(
                f"Unknown BinaryGroupAwareCodec magic byte: 0x$other%02x"
              )
            )
      catch
        case e: Exception =>
          Left(
            CodecError.ParseError(
              s"Malformed BinaryGroupAwareCodec frame: ${e.getMessage}",
              Some(e)
            )
          )

  @tailrec
  private def decodeFramesLoop(
      buf: ByteBuffer,
      remaining: Int,
      acc: Vector[GroupEnvelope]
  ): Either[CodecError, Vector[GroupEnvelope]] =
    if remaining <= 0 then Right(acc)
    else if buf.remaining() < 4 then
      Left(CodecError.ParseError("Truncated frame length"))
    else
      val frameLen = buf.getInt()
      if frameLen < 1 || frameLen > buf.remaining() then
        Left(CodecError.ParseError(s"Invalid frame length: $frameLen"))
      else
        val slice = new Array[Byte](frameLen)
        val _ = buf.get(slice)
        val sub = ByteBuffer.wrap(slice)
        if sub.get() != MagicSingle then
          Left(CodecError.ParseError("Malformed nested frame magic"))
        else
          decodeSingleBody(sub) match
            case Right(env) => decodeFramesLoop(buf, remaining - 1, acc :+ env)
            case Left(ce)   => Left(ce)

  private def decodeSingleBody(
      buf: ByteBuffer
  ): Either[CodecError, GroupEnvelope] =
    def readUtf8(): Either[CodecError, String] =
      if buf.remaining() < 4 then
        Left(CodecError.ParseError("Truncated length field"))
      else
        val len = buf.getInt()
        if len < 0 || len > buf.remaining() then
          Left(CodecError.ParseError(s"Invalid string length: $len"))
        else
          val arr = new Array[Byte](len)
          val _ = buf.get(arr)
          Right(new String(arr, StandardCharsets.UTF_8))

    for
      groupStr <- readUtf8()
      fromStr <- readUtf8()
      toOpt <-
        if buf.remaining() < 4 then
          Left(CodecError.ParseError("Truncated recipient length"))
        else
          val toLen = buf.getInt()
          if toLen == -1 then Right(None)
          else if toLen < 0 || toLen > buf.remaining() then
            Left(CodecError.ParseError(s"Invalid recipient length: $toLen"))
          else
            val arr = new Array[Byte](toLen)
            val _ = buf.get(arr)
            Right(Some(NodeId(new String(arr, StandardCharsets.UTF_8))))
      payloadBytes <-
        if buf.remaining() < 4 then
          Left(CodecError.ParseError("Truncated payload length"))
        else
          val pLen = buf.getInt()
          if pLen < 0 || pLen > buf.remaining() then
            Left(CodecError.ParseError(s"Invalid payload length: $pLen"))
          else
            val arr = new Array[Byte](pLen)
            val _ = buf.get(arr)
            Right(arr)
      msg <- inner.decode(payloadBytes)
    yield GroupEnvelope(GroupId(groupStr), NodeId(fromStr), msg, toOpt)
