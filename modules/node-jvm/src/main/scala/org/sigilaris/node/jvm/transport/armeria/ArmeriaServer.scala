package org.sigilaris.node.jvm.transport.armeria

import java.time.Duration

import cats.effect.Async
import cats.effect.kernel.Resource
import cats.effect.std.Dispatcher

import com.linecorp.armeria.server.Server
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.armeria.cats.{
  ArmeriaCatsServerInterpreter,
  ArmeriaCatsServerOptions,
}

/** Configuration for an Armeria HTTP server instance.
  *
  * @param port
  *   the TCP port to listen on
  * @param maxRequestLength
  *   maximum allowed request body size in bytes (default 128 MiB)
  * @param requestTimeout
  *   global maximum duration to wait for a request to complete (default 10
  *   minutes). Do not set this to zero for a mixed finite/streaming production
  *   assembly; long-lived streams need a route-specific timeout override so
  *   finite endpoints keep their generic timeout guard.
  */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class ArmeriaServerConfig(
    port: Int,
    maxRequestLength: Long = 128L * 1024L * 1024L,
    requestTimeout: Duration = Duration.ofMinutes(10),
)

/** Factory for building and managing Armeria HTTP servers backed by Tapir endpoints. */
object ArmeriaServer:
  /** Builds an Armeria server without starting it.
    *
    * @tparam F
    *   the effect type
    * @param config
    *   server configuration
    * @param dispatcher
    *   cats-effect dispatcher for bridging async boundaries
    * @param endpoints
    *   Tapir server endpoints to serve
    * @return
    *   the constructed (but not yet started) server
    */
  def build[F[_]: Async](
      config: ArmeriaServerConfig,
      dispatcher: Dispatcher[F],
      endpoints: List[ServerEndpoint[Fs2Streams[F], F]],
  ): F[Server] =
    Async[F].delay:
      val options =
        ArmeriaCatsServerOptions.customiseInterceptors[F](dispatcher).options
      val service =
        ArmeriaCatsServerInterpreter[F](options).toService(endpoints)
      Server
        .builder()
        .maxRequestLength(config.maxRequestLength)
        .requestTimeout(config.requestTimeout)
        .http(config.port)
        .service(service)
        .build()

  /** Builds an Armeria server that keeps the finite endpoint timeout guard
    * while binding one long-lived stream route with its own timeout.
    *
    * @tparam F
    *   the effect type
    * @param config
    *   server configuration for finite endpoints
    * @param dispatcher
    *   cats-effect dispatcher for bridging async boundaries
    * @param finiteEndpoints
    *   finite request/response Tapir endpoints
    * @param streamEndpoints
    *   long-lived streaming Tapir endpoints
    * @param streamRoutePath
    *   Armeria route path pattern for the streaming endpoint
    * @param streamRequestTimeout
    *   request timeout for the stream route; `Duration.ZERO` disables Armeria's
    *   request timeout for the long-lived response
    * @return
    *   the constructed (but not yet started) server
    */
  def buildWithScopedStreamTimeout[F[_]: Async](
      config: ArmeriaServerConfig,
      dispatcher: Dispatcher[F],
      finiteEndpoints: List[ServerEndpoint[Fs2Streams[F], F]],
      streamEndpoints: List[ServerEndpoint[Fs2Streams[F], F]],
      streamRoutePath: String,
      streamRequestTimeout: Duration,
  ): F[Server] =
    Async[F].delay:
      // `Duration.ZERO` intentionally disables Armeria's request timeout for
      // the long-lived stream route while preserving the finite-route guard.
      val options =
        ArmeriaCatsServerOptions.customiseInterceptors[F](dispatcher).options
      val interpreter = ArmeriaCatsServerInterpreter[F](options)
      val finiteService =
        interpreter.toService(finiteEndpoints)
      val streamService =
        interpreter.toService(streamEndpoints)
      Server
        .builder()
        .maxRequestLength(config.maxRequestLength)
        .requestTimeout(config.requestTimeout)
        .http(config.port)
        .service(finiteService)
        .route()
        .path(streamRoutePath)
        .requestTimeout(streamRequestTimeout)
        .build(streamService)
        .build()

  /** Builds and starts an Armeria server, returning the running server instance.
    *
    * @tparam F
    *   the effect type
    * @param config
    *   server configuration
    * @param dispatcher
    *   cats-effect dispatcher for bridging async boundaries
    * @param endpoints
    *   Tapir server endpoints to serve
    * @return
    *   the started server
    */
  def start[F[_]: Async](
      config: ArmeriaServerConfig,
      dispatcher: Dispatcher[F],
      endpoints: List[ServerEndpoint[Fs2Streams[F], F]],
  ): F[Server] =
    Async[F].flatMap(build(config, dispatcher, endpoints)): server =>
      Async[F].map(
        Async[F].fromCompletableFuture(Async[F].delay(server.start())),
      )(_ => server)

  /** Builds and starts an Armeria server with a route-scoped stream timeout. */
  def startWithScopedStreamTimeout[F[_]: Async](
      config: ArmeriaServerConfig,
      dispatcher: Dispatcher[F],
      finiteEndpoints: List[ServerEndpoint[Fs2Streams[F], F]],
      streamEndpoints: List[ServerEndpoint[Fs2Streams[F], F]],
      streamRoutePath: String,
      streamRequestTimeout: Duration,
  ): F[Server] =
    Async[F].flatMap(
      buildWithScopedStreamTimeout(
        config = config,
        dispatcher = dispatcher,
        finiteEndpoints = finiteEndpoints,
        streamEndpoints = streamEndpoints,
        streamRoutePath = streamRoutePath,
        streamRequestTimeout = streamRequestTimeout,
      ),
    ): server =>
      Async[F].map(
        Async[F].fromCompletableFuture(Async[F].delay(server.start())),
      )(_ => server)

  /** Creates a managed resource that starts an Armeria server and shuts it down on release.
    *
    * @tparam F
    *   the effect type
    * @param config
    *   server configuration
    * @param endpoints
    *   Tapir server endpoints to serve
    * @return
    *   a resource that manages the server lifecycle
    */
  def resource[F[_]: Async](
      config: ArmeriaServerConfig,
      endpoints: List[ServerEndpoint[Fs2Streams[F], F]],
  ): Resource[F, Server] =
    for
      dispatcher <- Dispatcher.parallel[F]
      server <- Resource.fromAutoCloseable(start(config, dispatcher, endpoints))
    yield server

  /** Creates a managed server resource with a route-scoped stream timeout. */
  def resourceWithScopedStreamTimeout[F[_]: Async](
      config: ArmeriaServerConfig,
      finiteEndpoints: List[ServerEndpoint[Fs2Streams[F], F]],
      streamEndpoints: List[ServerEndpoint[Fs2Streams[F], F]],
      streamRoutePath: String,
      streamRequestTimeout: Duration,
  ): Resource[F, Server] =
    for
      dispatcher <- Dispatcher.parallel[F]
      server <- Resource.fromAutoCloseable(
        startWithScopedStreamTimeout(
          config = config,
          dispatcher = dispatcher,
          finiteEndpoints = finiteEndpoints,
          streamEndpoints = streamEndpoints,
          streamRoutePath = streamRoutePath,
          streamRequestTimeout = streamRequestTimeout,
        ),
      )
    yield server
