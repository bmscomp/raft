package raft.message

import scala.util.control.NoStackTrace

/** Effectful message codec for encoding and decoding RAFT protocol messages.
  *
  * Wire-format agnosticism is a first-class design principle in this library.
  * The consensus logic ([[raft.logic.RaftLogic]]) works exclusively with
  * [[RaftMessage]] types; serialization to and from a wire format is delegated
  * to codec implementations.
  *
  * @tparam F
  *   the effect type (e.g., `IO`, `Either[CodecError, *]`)
  * @tparam Wire
  *   the wire format type (e.g., `String`, `Array[Byte]`)
  */
trait MessageCodec[F[_], Wire]:
  def encode(msg: RaftMessage): F[Wire]
  def decode(raw: Wire): F[RaftMessage]

/** Sealed hierarchy of codec errors that can occur during message
  * serialization.
  */
sealed trait CodecError extends RuntimeException with NoStackTrace:
  def message: String
  override def getMessage: String = message

object CodecError:
  case class ParseError(message: String, cause: Option[Throwable] = None)
      extends CodecError

  case class MissingField(fieldName: String, messageType: String)
      extends CodecError:
    val message = s"Missing field '$fieldName' in $messageType"

  case class InvalidField(fieldName: String, expected: String, actual: String)
      extends CodecError:
    val message =
      s"Invalid value for '$fieldName': expected $expected, got '$actual'"

  case class UnknownMessageType(typeName: String) extends CodecError:
    val message = s"Unknown message type: $typeName"

  case class UnsupportedVersion(
      found: Int,
      minSupported: Int,
      maxSupported: Int
  ) extends CodecError:
    val message =
      s"Unsupported protocol version $found (supported: $minSupported-$maxSupported)"

  case class EncodingFailed(message: String, cause: Option[Throwable] = None)
      extends CodecError

/** Pure synchronous codec for Sans-I/O and zero-dependency environments. */
trait SimpleMessageCodec[Wire]:
  def encode(msg: RaftMessage): Wire
  def decode(raw: Wire): Either[CodecError, RaftMessage]

  /** Transform the wire representation bidirectionally without effect wrappers.
    */
  final def mapWire[Wire2](
      toWire2: Wire => Wire2,
      fromWire2: Wire2 => Wire
  ): SimpleMessageCodec[Wire2] =
    val self = this
    new SimpleMessageCodec[Wire2]:
      def encode(msg: RaftMessage): Wire2 = toWire2(self.encode(msg))
      def decode(raw: Wire2): Either[CodecError, RaftMessage] =
        self.decode(fromWire2(raw))

object MessageCodec:
  /** Lift a [[SimpleMessageCodec]] into `Either[CodecError, *]` with zero
    * external dependencies.
    */
  def either[Wire](
      simple: SimpleMessageCodec[Wire]
  ): MessageCodec[[A] =>> Either[CodecError, A], Wire] =
    new MessageCodec[[A] =>> Either[CodecError, A], Wire]:
      def encode(msg: RaftMessage): Either[CodecError, Wire] =
        Right(simple.encode(msg))
      def decode(raw: Wire): Either[CodecError, RaftMessage] =
        simple.decode(raw)

type DecodeError = CodecError.ParseError

object DecodeError:
  def apply(msg: String): DecodeError = CodecError.ParseError(msg)
