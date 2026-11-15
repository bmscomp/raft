package raft.spi

import cats.{Functor, MonadError}
import raft.message.{MessageCodec, RaftMessage, SimpleMessageCodec}

/** Cats typeclass interop helpers for [[raft.message.MessageCodec]]. */
object CodecInterop:
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

  extension [F[_], Wire](codec: MessageCodec[F, Wire])
    def mapWire[Wire2](
        f: Wire => Wire2,
        g: Wire2 => Wire
    )(using F: Functor[F]): MessageCodec[F, Wire2] =
      new MessageCodec[F, Wire2]:
        def encode(msg: RaftMessage): F[Wire2] = F.map(codec.encode(msg))(f)
        def decode(raw: Wire2): F[RaftMessage] = codec.decode(g(raw))
