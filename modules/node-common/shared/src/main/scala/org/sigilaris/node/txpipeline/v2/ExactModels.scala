package org.sigilaris.node.txpipeline.v2

import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.ExecutionId
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8

import V2Codecs.given

enum ExactMode(val tag: Byte):
  case OrderedAtomic     extends ExactMode(1.toByte)
  case CertifiedAncestor extends ExactMode(2.toByte)

object ExactMode:
  given ByteEncoder[ExactMode] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[ExactMode] = V2Codecs.enumDecoder(
    "exact mode",
    Vector(OrderedAtomic, CertifiedAncestor).map(value => value.tag -> value),
  )
  val codec: CanonicalCodec[ExactMode] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

final case class StageIntent(
    txId: Hash,
    signedTransaction: Bytes,
    manifestDigest: Hash,
    sourceKind: Byte,
    declaration: Declaration,
    fullInputCommitment: Hash,
    lockSubsetCommitment: Hash,
)

object StageIntent:
  given ByteEncoder[StageIntent]         = ByteEncoder.derived
  given ByteDecoder[StageIntent]         = ByteDecoder.derived
  val codec: CanonicalCodec[StageIntent] = CanonicalCodec.derived(validate)
  def validate(value: StageIntent): Either[CoreFailure, Unit] =
    for
      _ <- ExactValidation.source(value.sourceKind)
      _ <- V2Validation.require(
        value.signedTransaction.nonEmpty,
        FailureCode.InvalidLength,
        "stage.signedTransaction",
      )
      _ <- Declaration.codec.encode(value.declaration)
    yield ()

final case class ExactPlanIntent(
    format: Long,
    context: DomainContext,
    applicationPipelineId: Text,
    profile: DependencyProfileBinding,
    laneId: Text,
    mode: ExactMode,
    stages: Vector[StageIntent],
    producerIndex: Long,
    consumerIndex: Long,
    outputSlot: Long,
    consumerFieldId: Text,
    referenceSchemaDigest: Hash,
    consumerReference: Bytes,
    lastInclusionHeight: Height,
)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object ExactPlanIntent:
  given ByteEncoder[ExactPlanIntent] = ByteEncoder.derived
  // The two-stage bound is checked before a vector can allocate its elements.
  given ByteDecoder[ExactPlanIntent] = for
    format                <- ByteDecoder[Long]
    context               <- ByteDecoder[DomainContext]
    applicationPipelineId <- ByteDecoder[Text]
    profile               <- ByteDecoder[DependencyProfileBinding]
    laneId                <- ByteDecoder[Text]
    mode                  <- ByteDecoder[ExactMode]
    stages                <- V2Codecs.boundedVectorDecoder[StageIntent](2L)
    producerIndex         <- ByteDecoder[Long]
    consumerIndex         <- ByteDecoder[Long]
    outputSlot            <- ByteDecoder[Long]
    consumerFieldId       <- ByteDecoder[Text]
    referenceSchemaDigest <- ByteDecoder[Hash]
    consumerReference     <- ByteDecoder[Bytes]
    lastInclusionHeight   <- ByteDecoder[Height]
  yield ExactPlanIntent(
    format,
    context,
    applicationPipelineId,
    profile,
    laneId,
    mode,
    stages,
    producerIndex,
    consumerIndex,
    outputSlot,
    consumerFieldId,
    referenceSchemaDigest,
    consumerReference,
    lastInclusionHeight,
  )
  val codec: CanonicalCodec[ExactPlanIntent] = CanonicalCodec.derived(validate)

  def validate(value: ExactPlanIntent): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.format(value.format, 2L, "exactIntent.format")
      _ <- DomainContext.validateActive(value.context)
      _ <- V2Validation.identifier(
        value.applicationPipelineId,
        "applicationPipelineId",
      )
      _ <- DependencyProfileBinding.validate(value.profile)
      _ <- V2Validation.identifier(value.laneId, "laneId")
      _ <- V2Validation.identifier(value.consumerFieldId, "consumerFieldId")
      _ <- ExactValidation.check(
        value.stages.sizeCompare(2) == 0,
        "exactIntent.stages",
      )
      _ <- V2Validation.all(value.stages.map(StageIntent.validate))
      _ <- ExactValidation.check(
        value.stages.map(_.txId).distinct.sizeCompare(2) == 0 && value.stages
          .map(_.signedTransaction)
          .distinct
          .sizeCompare(2) == 0,
        "exactIntent.distinctStages",
      )
      _ <- ExactValidation.check(
        value.producerIndex == 0L && value.consumerIndex == 1L,
        "exactIntent.edge",
      )
      _ <- ExactValidation.check(
        value.outputSlot >= 0L,
        "exactIntent.outputSlot",
      )
      _ <- V2Validation.require(
        value.consumerReference.nonEmpty,
        FailureCode.InvalidLength,
        "consumerReference",
      )
      _ <- V2Validation.require(
        value.lastInclusionHeight.toBigNat.toBigInt > 0,
        FailureCode.InvalidDeadline,
        "lastInclusionHeight",
      )
    yield ()

  def signingBytes(value: ExactPlanIntent): Either[CoreFailure, Bytes] =
    codec
      .encode(value)
      .map(bytes =>
        Commitment
          .preimage(SignedExactPlan.Domain, ByteVector(0.toByte) ++ bytes),
      )

  def referenceDigest(value: ExactPlanIntent): Either[CoreFailure, Hash] =
    validate(value).map(_ =>
      Commitment.hash(
        Utf8("sigilaris.tx-pipeline.exact.reference.v2"),
        ByteEncoder[Bytes].encode(value.consumerReference),
      ),
    )

final case class SignedExactPlan(intent: ExactPlanIntent, authorization: Bytes)

object SignedExactPlan:
  val Domain: Text = Utf8("sigilaris.tx-pipeline.exact.plan.v2")
  given ByteEncoder[SignedExactPlan]         = ByteEncoder.derived
  given ByteDecoder[SignedExactPlan]         = ByteDecoder.derived
  val codec: CanonicalCodec[SignedExactPlan] = CanonicalCodec.derived(validate)
  def validate(value: SignedExactPlan): Either[CoreFailure, Unit] =
    ExactPlanIntent.validate(value.intent)
  def digest(value: SignedExactPlan): Either[CoreFailure, Hash] =
    codec.encode(value).map(Commitment.hash(Domain, _))

final case class ExactIdentityBinding(
    format: Long,
    context: DomainContext,
    nodePipelineId: Text,
    applicationPipelineId: Text,
    signedPlanDigest: Hash,
    executionIds: Vector[ExecutionId],
)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object ExactIdentityBinding:
  val Domain: Text = Utf8("sigilaris.tx-pipeline.exact.identity-binding.v2")
  given ByteEncoder[ExactIdentityBinding] = ByteEncoder.derived
  given ByteDecoder[ExactIdentityBinding] = for
    format                <- ByteDecoder[Long]
    context               <- ByteDecoder[DomainContext]
    nodePipelineId        <- ByteDecoder[Text]
    applicationPipelineId <- ByteDecoder[Text]
    signedPlanDigest      <- ByteDecoder[Hash]
    executionIds          <- V2Codecs.boundedVectorDecoder[ExecutionId](2L)
  yield ExactIdentityBinding(
    format,
    context,
    nodePipelineId,
    applicationPipelineId,
    signedPlanDigest,
    executionIds,
  )
  val codec: CanonicalCodec[ExactIdentityBinding] =
    CanonicalCodec.derived(validate)

  def validate(value: ExactIdentityBinding): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.format(value.format, 3L, "exactBinding.format")
      _ <- DomainContext.validateActive(value.context)
      _ <- V2Validation.identifier(value.nodePipelineId, "nodePipelineId")
      _ <- V2Validation.identifier(
        value.applicationPipelineId,
        "applicationPipelineId",
      )
      _ <- ExactValidation.check(
        value.executionIds.sizeCompare(2) == 0 && value.executionIds.distinct
          .sizeCompare(2) == 0,
        "exactBinding.executionIds",
      )
    yield ()

  /** A structural derivation, not transaction or outer-authorization
    * verification.
    */
  def derive(
      nodePipelineId: Text,
      signed: SignedExactPlan,
  ): Either[CoreFailure, ExactIdentityBinding] =
    for
      digest     <- SignedExactPlan.digest(signed)
      executions <- ExactValidation.traverse(signed.intent.stages)(stage =>
        for
          declaration <- Declaration.digest(stage.declaration)
          id          <- ExecutionIdentity.compute(
            ExecutionIdentityInput(
              signed.intent.context,
              stage.manifestDigest,
              stage.txId,
              stage.signedTransaction,
              stage.fullInputCommitment,
              stage.lockSubsetCommitment,
              declaration,
              digest,
              signed.intent.lastInclusionHeight,
            ),
          )
        yield id,
      )
      binding = ExactIdentityBinding(
        3L,
        signed.intent.context,
        nodePipelineId,
        signed.intent.applicationPipelineId,
        digest,
        executions,
      )
      _ <- validate(binding)
    yield binding

  def verify(
      value: ExactIdentityBinding,
      signed: SignedExactPlan,
  ): Either[CoreFailure, Unit] =
    for
      _       <- validate(value)
      derived <- derive(value.nodePipelineId, signed)
      _       <- ExactValidation.check(
        value == derived,
        "exactBinding.derivedIdentity",
      )
    yield ()

  def digest(value: ExactIdentityBinding): Either[CoreFailure, Hash] =
    codec.encode(value).map(Commitment.hash(Domain, _))

final case class ExactSubmitRequest(
    format: Long,
    signedPlan: SignedExactPlan,
    idempotencyKey: Option[Text],
)

object ExactSubmitRequest:
  val Domain: Text = Utf8("sigilaris.tx-pipeline.exact.request.v3")
  given ByteEncoder[ExactSubmitRequest]         = ByteEncoder.derived
  given ByteDecoder[ExactSubmitRequest]         = ByteDecoder.derived
  val codec: CanonicalCodec[ExactSubmitRequest] =
    CanonicalCodec.derived(validate)
  def validate(value: ExactSubmitRequest): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.format(value.format, 3L, "exactRequest.format")
      _ <- SignedExactPlan.validate(value.signedPlan)
      _ <- V2Validation.all(
        value.idempotencyKey.toList.toVector
          .map(V2Validation.identifier(_, "idempotencyKey")),
      )
    yield ()
  def digest(value: ExactSubmitRequest): Either[CoreFailure, Hash] =
    validate(value)
      .flatMap(_ => codec.encode(value.copy(idempotencyKey = None)))
      .map(Commitment.hash(Domain, _))

/** Explicit transport discriminator; historical envelope dispatch remains
  * unchanged.
  */
final case class ExactSubmitEnvelope(request: ExactSubmitRequest)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object ExactSubmitEnvelope:
  def toJson(value: ExactSubmitEnvelope): Either[CoreFailure, Json] =
    ExactSubmitRequest.codec
      .encode(value.request)
      .map(bytes =>
        Json.obj(
          "kind"    -> Json.fromString("exactV3"),
          "payload" -> Json.fromString(bytes.toHex),
        ),
      )

  // Encoding an invalid in-memory request remains observable through toJson.
  // The Circe encoder delegates to the raw primitive encoder, and strict decoding
  // always validates the complete canonical payload before returning a request.
  given Encoder[ExactSubmitEnvelope] = Encoder.instance(value =>
    Json.obj(
      "kind"    -> Json.fromString("exactV3"),
      "payload" -> Json.fromString(
        ByteEncoder[ExactSubmitRequest].encode(value.request).toHex,
      ),
    ),
  )
  given Decoder[ExactSubmitEnvelope] = Decoder.instance: cursor =>
    for
      obj <- cursor.value.asObject.toRight(
        DecodingFailure("exactV3 must be an object", cursor.history),
      )
      _ <- Either.cond(
        obj.keys.toSet == Set("kind", "payload"),
        (),
        DecodingFailure("exactV3 has missing or extra fields", cursor.history),
      )
      kind <- cursor.get[String]("kind")
      _    <- Either.cond(
        kind == "exactV3",
        (),
        DecodingFailure("unsupported exact discriminator", cursor.history),
      )
      hex <- cursor.get[String]("payload")
      _   <- Either.cond(
        hex.nonEmpty && hex.length % 2 == 0 && hex.forall(c =>
          (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'),
        ),
        (),
        DecodingFailure(
          "payload must be canonical lowercase even hex",
          cursor.history,
        ),
      )
      bytes <- ByteVector
        .fromHexDescriptive(hex)
        .left
        .map(message => DecodingFailure(message, cursor.history))
      request <- ExactSubmitRequest.codec
        .decode(bytes)
        .left
        .map(error => DecodingFailure(error.message, cursor.history))
    yield ExactSubmitEnvelope(request)

private[v2] object ExactValidation:
  def check(condition: Boolean, field: String): Either[CoreFailure, Unit] =
    V2Validation.require(condition, FailureCode.MembershipMismatch, field)
  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  def source(value: Byte): Either[CoreFailure, Unit] = V2Validation.require(
    value == 1.toByte || value == 2.toByte,
    FailureCode.UnsupportedFormat,
    "sourceKind",
  )
  def traverse[A, B](
      values: Vector[A],
  )(f: A => Either[CoreFailure, B]): Either[CoreFailure, Vector[B]] =
    values.foldLeft[Either[CoreFailure, Vector[B]]](
      Right[CoreFailure, Vector[B]](Vector.empty[B]),
    )((acc, value) =>
      for previous <- acc; next <- f(value) yield previous :+ next,
    )
