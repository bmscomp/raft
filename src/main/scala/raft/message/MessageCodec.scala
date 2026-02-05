package raft.message

import cats.MonadError
import cats.syntax.all.*
import scala.util.control.NoStackTrace

/**
 * Enhanced MessageCodec with typed errors and effect support.
 * 
 * @tparam F Effect type (e.g., IO, Either[CodecError, *])
 * @tparam Wire Wire format (e.g., String, Array[Byte])
 */
trait MessageCodec[F[_], Wire]:
  /** Encode a message to wire format */
  def encode(msg: RaftMessage): F[Wire]
  
  /** Decode wire format to message */
  def decode(raw: Wire): F[RaftMessage]

/**
 * Comprehensive codec error hierarchy.
 */
sealed trait CodecError extends RuntimeException with NoStackTrace:
  def message: String
  override def getMessage: String = message

object CodecError:
  /** Failed to parse wire format */
  case class ParseError(message: String, cause: Option[Throwable] = None) extends CodecError
  
  /** Missing required field in message */
  case class MissingField(fieldName: String, messageType: String) extends CodecError:
    val message = s"Missing field '$fieldName' in $messageType"
  
  /** Field has invalid value */
  case class InvalidField(fieldName: String, expected: String, actual: String) extends CodecError:
    val message = s"Invalid value for '$fieldName': expected $expected, got '$actual'"
  
  /** Unknown message type */
  case class UnknownMessageType(typeName: String) extends CodecError:
    val message = s"Unknown message type: $typeName"
  
  /** Protocol version mismatch */
  case class UnsupportedVersion(found: Int, minSupported: Int, maxSupported: Int) extends CodecError:
    val message = s"Unsupported protocol version $found (supported: $minSupported-$maxSupported)"
  
  /** Generic encoding failure */
  case class EncodingFailed(message: String, cause: Option[Throwable] = None) extends CodecError

/**
 * Simple synchronous codec (backward compatible).
 */
trait SimpleMessageCodec[Wire]:
  def encode(msg: RaftMessage): Wire
  def decode(raw: Wire): Either[CodecError, RaftMessage]

/**
 * Codec companion with utilities.
 */
object MessageCodec:
  /** Lift simple codec into effectful codec */
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
  
  /** Extension for convenient use */
  extension [F[_], Wire](codec: MessageCodec[F, Wire])
    def mapWire[Wire2](
      f: Wire => Wire2,
      g: Wire2 => Wire
    )(using F: cats.Functor[F]): MessageCodec[F, Wire2] =
      new MessageCodec[F, Wire2]:
        def encode(msg: RaftMessage): F[Wire2] = F.map(codec.encode(msg))(f)
        def decode(raw: Wire2): F[RaftMessage] = codec.decode(g(raw))

// For backward compatibility
type DecodeError = CodecError.ParseError
object DecodeError:
  def apply(msg: String): DecodeError = CodecError.ParseError(msg)
