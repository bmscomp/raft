package raft.transport.grpc

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.*

/** Configuration for the gRPC-based Raft transport.
  *
  * @param host
  *   the hostname or IP address to bind the gRPC server to
  * @param port
  *   the port number for the gRPC server (`0` selects an ephemeral port)
  * @param maxMessageSize
  *   maximum message size in bytes (default: 4MB, accommodates large snapshots)
  * @param keepAliveInterval
  *   interval between gRPC keep-alive pings to detect dead connections
  * @param keepAliveTimeout
  *   maximum wait time for a keep-alive response before closing the connection
  * @param useTls
  *   whether to enable TLS encryption for peer communication
  * @param tlsCertPath
  *   path to the TLS certificate file (required on servers when `useTls` is
  *   true, and on clients when `tlsClientAuth` is true)
  * @param tlsKeyPath
  *   path to the PKCS#8 PEM private key file (required on servers when `useTls`
  *   is true, and on clients when `tlsClientAuth` is true)
  * @param tlsTrustCertPath
  *   optional PEM CA bundle path used to verify peer certificates (enables
  *   private cluster CAs; defaults to JVM system trust store when `None`)
  * @param tlsClientAuth
  *   when `true` (with `useTls = true`), enforces mutual TLS (`mTLS`
  *   `ClientAuth.REQUIRE`) so untrusted peers are rejected at handshake
  * @param maxRetries
  *   maximum number of transient RPC retry attempts before giving up
  * @param retryBackoff
  *   initial backoff duration between transient retry attempts (exponential)
  * @param maxRetryBackoff
  *   upper bound for a single retry sleep interval
  * @param maxInboundQueueSize
  *   bounded capacity of the inbound RPC envelope queue for backpressure
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
    tlsTrustCertPath: Option[String] = None,
    tlsClientAuth: Boolean = false,
    maxRetries: Int = 5,
    retryBackoff: FiniteDuration = 25.millis,
    maxRetryBackoff: FiniteDuration = 200.millis,
    maxInboundQueueSize: Int = 4096
):
  require(port >= 0 && port <= 65535, s"port must be in 0..65535, got $port")
  require(
    maxMessageSize > 0,
    s"maxMessageSize must be > 0, got $maxMessageSize"
  )
  require(maxRetries >= 0, s"maxRetries must be >= 0, got $maxRetries")
  require(
    maxInboundQueueSize > 0,
    s"maxInboundQueueSize must be > 0, got $maxInboundQueueSize"
  )
