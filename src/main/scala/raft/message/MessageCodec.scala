package raft.message

import cats.MonadError
import cats.syntax.all.*
import scala.util.control.NoStackTrace

/** Effectful message codec for encoding and decoding RAFT protocol messages.
  *
  * Wire-format agnosticism is a first-class design principle in this library.
  * The consensus logic ([[raft.logic.RaftLogic]]) works exclusively with
  * [[RaftMessage]] types; serialization to and from a wire format is an
  * orthogonal concern delegated to codec implementations. This means the same
  * Raft cluster can speak JSON, Protobuf, Avro, or any custom format simply by
  * swapping the codec — no changes to the consensus logic are needed.
  *
  * The codec layer offers two abstractions:
  *   - '''MessageCodec[F, Wire]''' (this trait) — effectful codecs that can
  *     perform async I/O or fail within an effect type `F`.
  *   - '''SimpleMessageCodec[Wire]''' — synchronous codecs that return
  *     `Either[CodecError, _]`, useful for simple in-memory formats.
  *
  * Synchronous codecs can be lifted into any effect type via
  * [[MessageCodec.fromSimple]], bridging the two abstractions.
  *
  * @tparam F
  *   the effect type (e.g., `IO`, `Either[CodecError, *]`)
  * @tparam Wire
  *   the wire format type (e.g., `String`, `Array[Byte]`)
  * @see
  *   [[SimpleMessageCodec]] for a synchronous alternative
  * @see
  *   [[raft.codec.JsonCodec]] for the built-in JSON implementation
  * @see
  *   [[raft.spi.Transport]] for how codecs integrate with the transport layer
  */
trait MessageCodec[F[_], Wire]:
  /** Encode a RAFT message to the wire format.
    *
    * @param msg
    *   the message to encode
    * @return
    *   the wire-format representation, wrapped in `F`
    */
  def encode(msg: RaftMessage): F[Wire]

  /** Decode a wire-format payload into a RAFT message.
    *
    * @param raw
    *   the wire-format data to decode
    * @return
    *   the decoded message, wrapped in `F` (may raise [[CodecError]])
    */
  def decode(raw: Wire): F[RaftMessage]

/** Sealed hierarchy of codec errors that can occur during message
  * serialization.
  *
  * All errors extend `RuntimeException` with `NoStackTrace` for lightweight
  * error handling in effectful contexts.
  *
  * @see
  *   [[MessageCodec]] for where these errors are raised
  */
sealed trait CodecError extends RuntimeException with NoStackTrace:
  /** Human-readable description of the error. */
  def message: String
  override def getMessage: String = message

/** Concrete codec error types. */
object CodecError:
  /** Failed to parse the wire format (malformed input).
    *
    * @param message
    *   description of the parse failure
    * @param cause
    *   optional underlying exception
    */
  case class ParseError(message: String, cause: Option[Throwable] = None)
      extends CodecError

  /** A required field is missing from the wire-format message.
    *
    * @param fieldName
    *   the name of the missing field
    * @param messageType
    *   the message type that expected the field
    */
  case class MissingField(fieldName: String, messageType: String)
      extends CodecError:
    val message = s"Missing field '$fieldName' in $messageType"

  /** A field has a value that does not match the expected format or range.
    *
    * @param fieldName
    *   the name of the invalid field
    * @param expected
    *   description of the expected value
    * @param actual
    *   the actual value encountered
    */
  case class InvalidField(fieldName: String, expected: String, actual: String)
      extends CodecError:
    val message =
      s"Invalid value for '$fieldName': expected $expected, got '$actual'"

  /** The message type identifier is not recognized.
    *
    * @param typeName
    *   the unrecognized type string
    */
  case class UnknownMessageType(typeName: String) extends CodecError:
    val message = s"Unknown message type: $typeName"

  /** The protocol version in the message is outside the supported range.
    *
    * @param found
    *   the version found in the message
    * @param minSupported
    *   the minimum supported version
    * @param maxSupported
    *   the maximum supported version
    */
  case class UnsupportedVersion(
      found: Int,
      minSupported: Int,
      maxSupported: Int
  ) extends CodecError:
    val message =
      s"Unsupported protocol version $found (supported: $minSupported-$maxSupported)"

  /** A generic encoding failure.
    *
    * @param message
    *   description of the failure
    * @param cause
    *   optional underlying exception
    */
  case class EncodingFailed(message: String, cause: Option[Throwable] = None)
      extends CodecError

/** Synchronous codec for environments that do not require effectful
  * serialization.
  *
  * Provides a simpler API than [[MessageCodec]] at the cost of synchronous-only
  * operation. Can be lifted into an effectful codec via
  * [[MessageCodec.fromSimple]].
  *
  * @tparam Wire
  *   the wire format type (e.g., `String`)
  * @see
  *   [[MessageCodec.fromSimple]] for lifting into an effectful context
  */
trait SimpleMessageCodec[Wire]:
  /** Encode a message to wire format (pure, synchronous).
    *
    * @param msg
    *   the message to encode
    * @return
    *   the wire-format representation
    */
  def encode(msg: RaftMessage): Wire

  /** Decode wire format to a message (synchronous, may fail).
    *
    * @param raw
    *   the wire-format data to decode
    * @return
    *   `Right(message)` on success, `Left(error)` on failure
    */
  def decode(raw: Wire): Either[CodecError, RaftMessage]

/** Codec companion with factory methods and extension utilities. */
object MessageCodec:
  /** Lift a [[SimpleMessageCodec]] into an effectful [[MessageCodec]].
    *
    * Encoding is pure (wrapped in `F.pure`). Decoding errors are raised via
    * `F.raiseError`.
    *
    * @tparam F
    *   the target effect type (requires `MonadError[F, Throwable]`)
    * @tparam Wire
    *   the wire format type
    * @param simple
    *   the synchronous codec to lift
    * @return
    *   an effectful codec delegating to the simple codec
    */
  def fromSimple[F[_], Wire](
      simple: SimpleMessageCodec[Wire]
  )(using F: MonadError[F, Throwable]): MessageCodec[F, Wire] =
    new MessageCodec[F, Wire]:
      def encode(msg: RaftMessage): F[Wire] =
        F.pure(simple.encode(msg))

      def decode(raw: Wire): F[RaftMessage] =
        simple.decode(raw) match
          case Right(msg) => F.pure(msg)
          case Left(err)  => F.raiseError(err)

  /** Extension for transforming the wire format of an existing codec.
    *
    * Useful for composing codecs with compression, encryption, or format
    * adapters.
    */
  extension [F[_], Wire](codec: MessageCodec[F, Wire])
    /** Map the wire format to a different type bidirectionally.
      *
      * @tparam Wire2
      *   the new wire format type
      * @param f
      *   encode-side transformation (Wire → Wire2)
      * @param g
      *   decode-side transformation (Wire2 → Wire)
      * @return
      *   a codec operating on `Wire2`
      */
    def mapWire[Wire2](
        f: Wire => Wire2,
        g: Wire2 => Wire
    )(using F: cats.Functor[F]): MessageCodec[F, Wire2] =
      new MessageCodec[F, Wire2]:
        def encode(msg: RaftMessage): F[Wire2] = F.map(codec.encode(msg))(f)
        def decode(raw: Wire2): F[RaftMessage] = codec.decode(g(raw))

/** Backward-compatible alias for [[CodecError.ParseError]]. */
type DecodeError = CodecError.ParseError

/** Backward-compatible factory for [[DecodeError]]. */
object DecodeError:
  /** Create a [[DecodeError]] with the given message.
    *
    * @param msg
    *   description of the decode failure
    * @return
    *   a new decode error instance
    */
  def apply(msg: String): DecodeError = CodecError.ParseError(msg)
