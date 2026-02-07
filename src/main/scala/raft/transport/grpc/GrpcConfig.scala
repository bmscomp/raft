package raft.transport.grpc

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.*

/** Configuration for the gRPC-based Raft transport.
  *
  * @param host
  *   the hostname or IP address to bind the gRPC server to
  * @param port
  *   the port number for the gRPC server
  * @param maxMessageSize
  *   maximum message size in bytes (default: 4MB, accommodates large snapshots)
  * @param keepAliveInterval
  *   interval between gRPC keep-alive pings to detect dead connections
  * @param keepAliveTimeout
  *   maximum wait time for a keep-alive response before closing the connection
  * @param useTls
  *   whether to enable TLS encryption for peer communication
  * @param tlsCertPath
  *   path to the TLS certificate file (required when `useTls` is true)
  * @param tlsKeyPath
  *   path to the TLS private key file (required when `useTls` is true)
  * @param maxRetries
  *   maximum number of connection retry attempts before giving up
  * @param retryBackoff
  *   initial backoff duration between retry attempts (exponential)
  */
final case class GrpcConfig(
    host: String = "0.0.0.0",
    port: Int = 9090,
    maxMessageSize: Int = 4 * 1024 * 1024,
    keepAliveInterval: FiniteDuration = 30.seconds,
    keepAliveTimeout: FiniteDuration = 10.seconds,
    useTls: Boolean = false,
    tlsCertPath: Option[String] = None,
    tlsKeyPath: Option[String] = None,
    maxRetries: Int = 5,
    retryBackoff: FiniteDuration = 100.millis
)
