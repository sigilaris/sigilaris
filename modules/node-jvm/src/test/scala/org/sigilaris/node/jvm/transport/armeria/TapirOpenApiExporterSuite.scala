package org.sigilaris.node.jvm.transport.armeria

import java.nio.file.Files

import cats.effect.{IO, Resource}
import munit.CatsEffectSuite
import sttp.apispec.openapi.PathItem
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

  test("write emits an OpenAPI json file for provided endpoints"):
    Resource
      .make(IO.blocking(Files.createTempDirectory("sigilaris-openapi-json")))(dir =>
        IO.blocking:
          Files.deleteIfExists(dir.resolve("spec.json"))
          Files.deleteIfExists(dir)
          ()
      )
      .use: dir =>
        TapirOpenApiExporter
          .write[IO](
            OpenApiExportConfig(
              output = dir.resolve("spec.json"),
              format = OpenApiFormat.Json,
              title = "Sigilaris Node JVM",
              version = "0.0.0-test",
            ),
            List(statusEndpoint),
          )
          .flatMap: path =>
            IO:
              val rendered = Files.readString(path)
              assert(rendered.trim.startsWith("{"))
              assert(rendered.contains("\"openapi\""))

  test("document applies the enrichment callback without mutating endpoint generation"):
    IO:
      val docs = TapirOpenApiExporter.document(
        title = "Sigilaris Node JVM",
        version = "0.0.0-test",
        endpoints = List(statusEndpoint),
        enrich = _.addPathItem("/custom", PathItem()),
      )

      assert(docs.paths.pathItems.contains("/status"))
      assert(docs.paths.pathItems.contains("/custom"))
