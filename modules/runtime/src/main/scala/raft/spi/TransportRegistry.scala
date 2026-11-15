package raft.spi

import cats.effect.kernel.{Async, Ref}
import cats.syntax.all.*

/** Registry for multiple transport backends.
  *
  * Allows runtime selection and management of different transport
  * implementations (e.g., in-memory, gRPC, HTTP). Each transport is registered
  * under a unique name and can be looked up at initialization time to create
  * the appropriate `Transport` instance.
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @see
  *   [[TransportFactory]] for creating transport instances
  */
trait TransportRegistry[F[_]]:

  /** Register a transport factory under a unique name.
    *
    * @param name
    *   the transport backend name (e.g., "grpc", "in-memory", "http")
    * @param factory
    *   the factory for creating transports of this type
    */
  def register[Wire](name: String, factory: TransportFactory[F, Wire]): F[Unit]

  /** Look up a transport factory by name.
    *
    * @param name
    *   the transport backend name
    * @return
    *   `Some(factory)` if a transport with that name is registered, `None`
    *   otherwise
    */
  def get(name: String): F[Option[TransportFactory[F, ?]]]

  /** List all registered transport backend names.
    *
    * @return
    *   the names of all available transports
    */
  def available: F[List[String]]

object TransportRegistry:

  /** Create a new in-memory [[TransportRegistry]].
    *
    * @tparam F
    *   the effect type (requires `Async`)
    * @return
    *   a fresh, empty transport registry
    */
  def create[F[_]: Async]: F[TransportRegistry[F]] =
    Ref.of[F, Map[String, TransportFactory[F, ?]]](Map.empty).map { ref =>
      new TransportRegistry[F]:
        def register[Wire](
            name: String,
            factory: TransportFactory[F, Wire]
        ): F[Unit] =
          ref.update(_ + (name -> factory))

        def get(name: String): F[Option[TransportFactory[F, ?]]] =
          ref.get.map(_.get(name))

        def available: F[List[String]] =
          ref.get.map(_.keys.toList.sorted)
    }
