package org.sigilaris.node.jvm.transport.armeria

import java.time.Duration

import cats.effect.Async
import cats.effect.kernel.Resource
import cats.effect.std.Dispatcher

import com.linecorp.armeria.server.Server
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
  *   maximum duration to wait for a request to complete (default 10 minutes)
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
      endpoints: List[ServerEndpoint[Any, F]],
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
      endpoints: List[ServerEndpoint[Any, F]],
  ): F[Server] =
    Async[F].flatMap(build(config, dispatcher, endpoints)): server =>
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
      endpoints: List[ServerEndpoint[Any, F]],
  ): Resource[F, Server] =
    for
      dispatcher <- Dispatcher.parallel[F]
      server <- Resource.fromAutoCloseable(start(config, dispatcher, endpoints))
    yield server
