package org.sigilaris.node.jvm.transport.armeria

import java.nio.file.Files

import cats.effect.{IO, Resource}
import munit.CatsEffectSuite
import sttp.tapir.*

final class TapirOpenApiExporterSuite extends CatsEffectSuite:

  private val statusEndpoint =
    endpoint.get
      .in("status")
      .out(stringBody)

  test("write emits an OpenAPI yaml file for provided endpoints"):
    Resource
      .make(IO.blocking(Files.createTempDirectory("sigilaris-openapi")))(dir =>
        IO.blocking:
          Files.deleteIfExists(dir.resolve("spec.yaml"))
          Files.deleteIfExists(dir)
          ()
      )
      .use: dir =>
        TapirOpenApiExporter
          .write[IO](
            OpenApiExportConfig(
              output = dir.resolve("spec.yaml"),
              format = OpenApiFormat.Yaml,
              title = "Sigilaris Node JVM",
              version = "0.0.0-test",
            ),
            List(statusEndpoint),
          )
          .flatMap: path =>
            IO:
              val rendered = Files.readString(path)
              assert(rendered.contains("openapi"))
              assert(rendered.contains("/status"))
