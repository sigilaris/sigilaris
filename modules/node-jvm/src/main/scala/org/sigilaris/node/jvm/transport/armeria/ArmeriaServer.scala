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

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class ArmeriaServerConfig(
    port: Int,
    maxRequestLength: Long = 128L * 1024L * 1024L,
    requestTimeout: Duration = Duration.ofMinutes(10),
)

object ArmeriaServer:
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

  def start[F[_]: Async](
      config: ArmeriaServerConfig,
      dispatcher: Dispatcher[F],
      endpoints: List[ServerEndpoint[Any, F]],
  ): F[Server] =
    Async[F].flatMap(build(config, dispatcher, endpoints)): server =>
      Async[F].map(
        Async[F].fromCompletableFuture(Async[F].delay(server.start())),
      )(_ => server)

  def resource[F[_]: Async](
      config: ArmeriaServerConfig,
      endpoints: List[ServerEndpoint[Any, F]],
  ): Resource[F, Server] =
    for
      dispatcher <- Dispatcher.parallel[F]
      server <- Resource.fromAutoCloseable(start(config, dispatcher, endpoints))
    yield server
