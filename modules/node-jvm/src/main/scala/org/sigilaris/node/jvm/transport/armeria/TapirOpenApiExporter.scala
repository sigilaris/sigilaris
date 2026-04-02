package org.sigilaris.node.jvm.transport.armeria

import java.nio.file.{Files, Path}

import cats.effect.Sync
import io.circe.syntax.*
import sttp.apispec.openapi.OpenAPI
import sttp.apispec.openapi.circe.*
import sttp.apispec.openapi.circe.yaml.*
import sttp.tapir.AnyEndpoint
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

enum OpenApiFormat:
  case Yaml
  case Json

final case class OpenApiExportConfig(
    output: Path,
    format: OpenApiFormat,
    title: String,
    version: String,
)

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
object TapirOpenApiExporter:
  def document(
      title: String,
      version: String,
      endpoints: List[AnyEndpoint],
      enrich: OpenAPI => OpenAPI = identity,
  ): OpenAPI =
    enrich(OpenAPIDocsInterpreter().toOpenAPI(endpoints, title, version))

  def write[F[_]: Sync](
      config: OpenApiExportConfig,
      endpoints: List[AnyEndpoint],
      enrich: OpenAPI => OpenAPI = identity,
  ): F[Path] =
    val docs = document(config.title, config.version, endpoints, enrich)
    val rendered = config.format match
      case OpenApiFormat.Yaml => docs.toYaml
      case OpenApiFormat.Json => docs.asJson.spaces2

    Sync[F]
      .blocking:
        Option(config.output.getParent).foreach(dir =>
          Files.createDirectories(dir),
        )
        Files.writeString(config.output, rendered)
        config.output.toAbsolutePath
