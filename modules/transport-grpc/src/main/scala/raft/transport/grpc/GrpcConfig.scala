package raft.transport.grpc

import scala.concurrent.duration.*

import raft.state.{ConfigError, Validated}

/** Operational telemetry counters exposed by [[GrpcTransport.transportStats]].
  */
final case class GrpcTransportStats(
    envelopesSent: Long = 0L,
    batchesSent: Long = 0L,
    backpressureRetries: Long = 0L,
    rejectedSpoofedEnvelopes: Long = 0L
)

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
  * @param verifyPeerIdentity
  *   when `true` (and `useTls && tlsClientAuth`), verifies that every inbound
  *   `WireEnvelope.sender_id` matches an authenticated X.509 client certificate
  *   `CN` or `SAN` identity, rejecting spoofed `sender_id`s with
  *   `PERMISSION_DENIED` (**fixes ST4**)
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
    verifyPeerIdentity: Boolean = true,
    maxRetries: Int = 5,
    retryBackoff: FiniteDuration = 25.millis,
    maxRetryBackoff: FiniteDuration = 200.millis,
    maxInboundQueueSize: Int = 4096
):
  require(host.trim.nonEmpty, "host must be non-empty")
  require(port >= 0 && port <= 65535, s"port must be in 0..65535, got $port")
  require(
    maxMessageSize > 0,
    s"maxMessageSize must be > 0, got $maxMessageSize"
  )
  require(
    keepAliveInterval > Duration.Zero,
    s"keepAliveInterval must be > 0, got $keepAliveInterval"
  )
  require(
    keepAliveTimeout > Duration.Zero,
    s"keepAliveTimeout must be > 0, got $keepAliveTimeout"
  )
  require(maxRetries >= 0, s"maxRetries must be >= 0, got $maxRetries")
  require(
    retryBackoff >= Duration.Zero,
    s"retryBackoff must be >= 0, got $retryBackoff"
  )
  require(
    maxRetryBackoff >= retryBackoff,
    s"maxRetryBackoff ($maxRetryBackoff) must be >= retryBackoff ($retryBackoff)"
  )
  require(
    maxInboundQueueSize > 0,
    s"maxInboundQueueSize must be > 0, got $maxInboundQueueSize"
  )

object GrpcConfig:
  /** Error-accumulating smart constructor validating all [[GrpcConfig]] bounds
    * and TLS certificate requirements.
    */
  def validate(
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
      verifyPeerIdentity: Boolean = true,
      maxRetries: Int = 5,
      retryBackoff: FiniteDuration = 25.millis,
      maxRetryBackoff: FiniteDuration = 200.millis,
      maxInboundQueueSize: Int = 4096
  ): Validated[ConfigError, GrpcConfig] =
    val baseChecks = List(
      Validated.check(
        host.trim.nonEmpty,
        ConfigError.InvalidField("GrpcConfig.host", "must be non-empty")
      ),
      Validated.check(
        port >= 0 && port <= 65535,
        ConfigError.InvalidField(
          "GrpcConfig.port",
          s"must be in 0..65535, got $port"
        )
      ),
      Validated.check(
        maxMessageSize > 0,
        ConfigError.InvalidPositiveInt(
          "GrpcConfig.maxMessageSize",
          maxMessageSize
        )
      ),
      Validated.check(
        keepAliveInterval > Duration.Zero,
        ConfigError.InvalidDuration(
          "GrpcConfig.keepAliveInterval",
          s"must be > 0, got $keepAliveInterval"
        )
      ),
      Validated.check(
        keepAliveTimeout > Duration.Zero,
        ConfigError.InvalidDuration(
          "GrpcConfig.keepAliveTimeout",
          s"must be > 0, got $keepAliveTimeout"
        )
      ),
      Validated.check(
        maxRetries >= 0,
        ConfigError.InvalidField(
          "GrpcConfig.maxRetries",
          s"must be >= 0, got $maxRetries"
        )
      ),
      Validated.check(
        retryBackoff >= Duration.Zero,
        ConfigError.InvalidDuration(
          "GrpcConfig.retryBackoff",
          s"must be >= 0, got $retryBackoff"
        )
      ),
      Validated.check(
        maxRetryBackoff >= retryBackoff,
        ConfigError.InvalidIntervalOrdering(
          "GrpcConfig.maxRetryBackoff",
          s"maxRetryBackoff ($maxRetryBackoff) must be >= retryBackoff ($retryBackoff)"
        )
      ),
      Validated.check(
        maxInboundQueueSize > 0,
        ConfigError.InvalidPositiveInt(
          "GrpcConfig.maxInboundQueueSize",
          maxInboundQueueSize
        )
      )
    )

    val tlsChecks =
      if useTls then
        List(
          Validated.check(
            tlsCertPath.exists(_.trim.nonEmpty),
            ConfigError.InvalidField(
              "GrpcConfig.tlsCertPath",
              "tlsCertPath is required when useTls is true"
            )
          ),
          Validated.check(
            tlsKeyPath.exists(_.trim.nonEmpty),
            ConfigError.InvalidField(
              "GrpcConfig.tlsKeyPath",
              "tlsKeyPath is required when useTls is true"
            )
          )
        )
      else Nil

    Validated.sequence_(baseChecks ++ tlsChecks).map { _ =>
      GrpcConfig(
        host = host,
        port = port,
        maxMessageSize = maxMessageSize,
        keepAliveInterval = keepAliveInterval,
        keepAliveTimeout = keepAliveTimeout,
        useTls = useTls,
        tlsCertPath = tlsCertPath,
        tlsKeyPath = tlsKeyPath,
        tlsTrustCertPath = tlsTrustCertPath,
        tlsClientAuth = tlsClientAuth,
        verifyPeerIdentity = verifyPeerIdentity,
        maxRetries = maxRetries,
        retryBackoff = retryBackoff,
        maxRetryBackoff = maxRetryBackoff,
        maxInboundQueueSize = maxInboundQueueSize
      )
    }
