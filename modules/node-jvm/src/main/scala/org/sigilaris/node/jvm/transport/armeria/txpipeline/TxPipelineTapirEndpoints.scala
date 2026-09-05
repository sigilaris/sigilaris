package org.sigilaris.node.jvm.transport.armeria.txpipeline

import sttp.tapir.*

/** Shared Tapir endpoint definitions for the transaction pipeline API. */
@SuppressWarnings(Array("org.wartremover.warts.Any"))
object TxPipelineTapirEndpoints:
  val SubmitPath: String            = "/tx-pipeline"
  val ApplicationStatusPath: String = "/tx-pipeline/application-status"

  val IdempotencyKeyHeaderName: String = "Idempotency-Key"

  def queryPath(pipelineId: String): String =
    s"/tx-pipeline/${pipelineId}"

  private[txpipeline] val idempotencyKeyHeader =
    header[Option[String]](IdempotencyKeyHeaderName)

  val submit =
    endpoint.post
      .in("tx-pipeline")
      .in(idempotencyKeyHeader)
      .in(
        stringBody.description(
          "Generic v1 request JSON, or {kind: exactV2, request: ...}",
        ),
      )
      .errorOut(statusCode.and(stringBody))
      .out(statusCode.and(stringBody))

  val query =
    endpoint.get
      .in("tx-pipeline" / path[String]("pipelineId"))
      .errorOut(statusCode.and(stringBody))
      .out(statusCode.and(stringBody))

  val applicationStatus =
    endpoint.get
      .in("tx-pipeline" / "application-status")
      .description(
        "Operator-only application lock, exact pipeline, and drain diagnostics; authentication is supplied by the embedder",
      )
      .errorOut(statusCode.and(stringBody))
      .out(statusCode.and(stringBody))

  val all: List[AnyEndpoint] =
    List(submit, query)

  val allWithApplicationStatus: List[AnyEndpoint] =
    List(submit, query, applicationStatus)
