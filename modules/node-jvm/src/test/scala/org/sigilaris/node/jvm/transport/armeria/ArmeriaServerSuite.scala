package org.sigilaris.node.jvm.transport.armeria

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

import cats.effect.IO
import munit.CatsEffectSuite
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint

final class ArmeriaServerSuite extends CatsEffectSuite:

  private val healthEndpoint: ServerEndpoint[Any, IO] =
    endpoint.get
      .in("health")
      .out(stringBody)
      .serverLogicSuccess[IO](_ => IO.pure("ok"))

  private val versionEndpoint: ServerEndpoint[Any, IO] =
    endpoint.get
      .in("version")
      .out(stringBody)
      .serverLogicSuccess[IO](_ => IO.pure("v1"))

  test("resource starts an Armeria server from server endpoints"):
    ArmeriaServer
      .resource[IO](ArmeriaServerConfig(port = 0), List(healthEndpoint))
      .use: server =>
        IO:
          assert(server.activeLocalPort() > 0)

  test(
    "resource serves HTTP responses for multiple endpoints and applies config",
  ):
    val config = ArmeriaServerConfig(
      port = 0,
      maxRequestLength = 1024L,
      requestTimeout = Duration.ofSeconds(3),
    )

    ArmeriaServer
      .resource[IO](config, List(healthEndpoint, versionEndpoint))
      .use: server =>
        IO.blocking:
          val client = HttpClient.newHttpClient()
          val request = HttpRequest
            .newBuilder(
              URI
                .create(s"http://127.0.0.1:${server.activeLocalPort()}/version"),
            )
            .GET()
            .build()

          val response =
            client.send(request, HttpResponse.BodyHandlers.ofString())
          assertEquals(response.statusCode(), 200)
          assertEquals(response.body(), "v1")
          val serviceConfig = server.config().serviceConfigs().get(0)
          assertEquals(serviceConfig.maxRequestLength(), 1024L)
          assertEquals(serviceConfig.requestTimeoutMillis(), 3000L)
