package org.sigilaris.node.jvm.transport.armeria

import java.nio.file.{Files, Path}

import cats.effect.Sync
import io.circe.syntax.*
import sttp.apispec.openapi.OpenAPI
import sttp.apispec.openapi.circe.*
import sttp.apispec.openapi.circe.yaml.*
import sttp.tapir.AnyEndpoint
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

/** Supported output formats for OpenAPI specification documents. */
enum OpenApiFormat:
  /** YAML format output. */
  case Yaml
  /** JSON format output. */
  case Json

/** Configuration for exporting an OpenAPI specification document.
  *
  * @param output
  *   file path where the specification will be written
  * @param format
  *   output format (YAML or JSON)
  * @param title
  *   API title included in the specification
  * @param version
  *   API version string included in the specification
  */
final case class OpenApiExportConfig(
    output: Path,
    format: OpenApiFormat,
    title: String,
    version: String,
)

/** Generates and writes OpenAPI specification documents from Tapir endpoint definitions. */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
object TapirOpenApiExporter:
  /** Generates an OpenAPI document from the given Tapir endpoints.
    *
    * @param title
    *   API title
    * @param version
    *   API version string
    * @param endpoints
    *   Tapir endpoints to document
    * @param enrich
    *   optional transformation applied to the generated OpenAPI model
    * @return
    *   the generated OpenAPI specification
    */
  def document(
      title: String,
      version: String,
      endpoints: List[AnyEndpoint],
      enrich: OpenAPI => OpenAPI = identity,
  ): OpenAPI =
    enrich(OpenAPIDocsInterpreter().toOpenAPI(endpoints, title, version))

  /** Generates an OpenAPI document and writes it to disk.
    *
    * @tparam F
    *   the effect type
    * @param config
    *   export configuration (output path, format, title, version)
    * @param endpoints
    *   Tapir endpoints to document
    * @param enrich
    *   optional transformation applied to the generated OpenAPI model
    * @return
    *   the absolute path of the written file
    */
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
