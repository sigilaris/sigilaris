package org.sigilaris.node.jvm.transport.armeria

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

  test("resource starts an Armeria server from server endpoints"):
    ArmeriaServer
      .resource[IO](ArmeriaServerConfig(port = 0), List(healthEndpoint))
      .use: server =>
        IO:
          assert(server.activeLocalPort() > 0)
