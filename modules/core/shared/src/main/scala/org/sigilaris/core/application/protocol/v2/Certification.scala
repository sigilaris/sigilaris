package org.sigilaris.core.application.protocol.v2

import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.{
  ApplicationResultDigest,
  ApplicationStateRoot,
  ExecutionId,
}
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.core.datatype.Utf8

import V2Codecs.given

enum AdmissionBase:
  case Finalized(blockId: Hash, height: Height, stateRoot: Hash)
  case InitialAnchor(
      blockId: Hash,
      height: Height,
      stateRoot: Hash,
      bundleDigest: Hash,
  )

@SuppressWarnings(Array("org.wartremover.warts.Nothing"))
object AdmissionBase:
  given ByteEncoder[AdmissionBase] =
    case AdmissionBase.Finalized(blockId, height, stateRoot) =>
      ByteVector(1.toByte) ++ ByteEncoder[Hash].encode(blockId) ++
        ByteEncoder[Height].encode(height) ++ ByteEncoder[Hash].encode(
          stateRoot,
        )
    case AdmissionBase.InitialAnchor(
          blockId,
          height,
          stateRoot,
          bundleDigest,
        ) =>
      ByteVector(2.toByte) ++ ByteEncoder[Hash].encode(blockId) ++
        ByteEncoder[Height].encode(height) ++ ByteEncoder[Hash].encode(
          stateRoot,
        ) ++
        ByteEncoder[Hash].encode(bundleDigest)

  given ByteDecoder[AdmissionBase] = ByteDecoder[Byte].flatMap:
    case 1 =>
      for
        blockId   <- ByteDecoder[Hash]
        height    <- ByteDecoder[Height]
        stateRoot <- ByteDecoder[Hash]
      yield AdmissionBase.Finalized(blockId, height, stateRoot)
    case 2 =>
      for
        blockId   <- ByteDecoder[Hash]
        height    <- ByteDecoder[Height]
        stateRoot <- ByteDecoder[Hash]
        bundle    <- ByteDecoder[Hash]
      yield AdmissionBase.InitialAnchor(blockId, height, stateRoot, bundle)
    case _ => _ => Left(DecodeFailure("unsupported admission base tag"))

  val codec: CanonicalCodec[AdmissionBase] =
    CanonicalCodec.derived(_ => Right(()))

  def height(value: AdmissionBase): Height = value match
    case AdmissionBase.Finalized(_, height, _)        => height
    case AdmissionBase.InitialAnchor(_, height, _, _) => height

final case class LockSubject(
    context: DomainContext,
    txId: Hash,
    executionId: ExecutionId,
    manifestDigest: Hash,
    admissionBase: AdmissionBase,
    fullInputCommitment: Hash,
    lockSubsetCommitment: Hash,
    dependencyPlanDigest: Hash,
    lastInclusionHeight: Height,
    inputs: Vector[InputId],
)

object LockSubject:
  val Domain: Text               = Utf8("sigilaris.application.lock.vote.v2")
  given ByteEncoder[LockSubject] = ByteEncoder.derived
  given ByteDecoder[LockSubject] = ByteDecoder.derived
  val codec: CanonicalCodec[LockSubject] = CanonicalCodec.derived(validate)

  def validate(value: LockSubject): Either[CoreFailure, Unit] =
    for
      _ <- DomainContext.validateActive(value.context)
      _ <- V2Validation.require(
        value.admissionBase match
          case AdmissionBase.Finalized(_, _, _) => true
          case _                                => false,
        FailureCode.ProofInvalid,
        "lock.admissionBase",
      )
      _ <- V2Validation.require(
        value.inputs.nonEmpty,
        FailureCode.InvalidLength,
        "lock.inputs",
      )
      _ <- V2Validation.all(
        value.inputs.map(id =>
          V2Validation.require(
            id.bytes.nonEmpty && id.bytes.size <= 256L,
            FailureCode.InvalidIdentifier,
            "lock.inputs",
          ),
        ),
      )
      _ <- V2Validation.sortedUnique(value.inputs.map(_.toHex), "lock.inputs")
      _ <- V2Validation.require(
        value.lastInclusionHeight.toBigNat.toBigInt > AdmissionBase
          .height(value.admissionBase)
          .toBigNat
          .toBigInt,
        FailureCode.InvalidDeadline,
        "lock.lastInclusionHeight",
      )
    yield ()

  def signingPreimage(value: LockSubject): Either[CoreFailure, Bytes] =
    codec.encode(value).map(Commitment.preimage(Domain, _))

final case class EffectSubject(
    context: DomainContext,
    txId: Hash,
    executionId: ExecutionId,
    manifestDigest: Hash,
    inputLockCertificateId: Hash,
    dependencyPlanDigest: Hash,
    lastInclusionHeight: Height,
    resultDigest: ApplicationResultDigest,
    stateRoot: ApplicationStateRoot,
    actualFootprintCommitment: Hash,
)

object EffectSubject:
  val Domain: Text = Utf8("sigilaris.application.effect.vote.v2")
  given ByteEncoder[EffectSubject]         = ByteEncoder.derived
  given ByteDecoder[EffectSubject]         = ByteDecoder.derived
  val codec: CanonicalCodec[EffectSubject] = CanonicalCodec.derived(validate)

  def validate(value: EffectSubject): Either[CoreFailure, Unit] =
    DomainContext
      .validateActive(value.context)
      .flatMap(_ =>
        V2Validation.require(
          value.lastInclusionHeight.toBigNat.toBigInt > 0,
          FailureCode.InvalidDeadline,
          "effect.lastInclusionHeight",
        ),
      )

  def signingPreimage(value: EffectSubject): Either[CoreFailure, Bytes] =
    codec.encode(value).map(Commitment.preimage(Domain, _))

final case class ValidatorSignature(validatorId: Text, signature: Bytes)

object ValidatorSignature:
  private val CurveOrder = BigInt(
    "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141",
    16,
  )
  given ByteEncoder[ValidatorSignature]         = ByteEncoder.derived
  given ByteDecoder[ValidatorSignature]         = ByteDecoder.derived
  val codec: CanonicalCodec[ValidatorSignature] =
    CanonicalCodec.derived(validate)

  def validate(value: ValidatorSignature): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.identifier(value.validatorId, "vote.validatorId")
      _ <- V2Validation.require(
        value.signature.size == 72L,
        FailureCode.InvalidSignature,
        "vote.signature",
      )
      recovery = BigInt(1, value.signature.take(8L).toArray)
      r        = BigInt(1, value.signature.slice(8L, 40L).toArray)
      s        = BigInt(1, value.signature.drop(40L).toArray)
      _ <- V2Validation.require(
        recovery >= 27 && recovery <= 30 && r > 0 && r < CurveOrder && s > 0 && s <= CurveOrder / 2,
        FailureCode.InvalidSignature,
        "vote.signature",
      )
    yield ()

final case class LockVote(subject: LockSubject, vote: ValidatorSignature)

object LockVote:
  given ByteEncoder[LockVote]         = ByteEncoder.derived
  given ByteDecoder[LockVote]         = ByteDecoder.derived
  val codec: CanonicalCodec[LockVote] = CanonicalCodec.derived(value =>
    LockSubject
      .validate(value.subject)
      .flatMap(_ => ValidatorSignature.validate(value.vote)),
  )

final case class EffectVote(subject: EffectSubject, vote: ValidatorSignature)

object EffectVote:
  given ByteEncoder[EffectVote]         = ByteEncoder.derived
  given ByteDecoder[EffectVote]         = ByteDecoder.derived
  val codec: CanonicalCodec[EffectVote] = CanonicalCodec.derived(value =>
    EffectSubject
      .validate(value.subject)
      .flatMap(_ => ValidatorSignature.validate(value.vote)),
  )

final case class LockCertificate(
    subject: LockSubject,
    votes: Vector[ValidatorSignature],
)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object LockCertificate:
  val Domain: Text = Utf8("sigilaris.application.lock.certificate.v2")
  given ByteEncoder[LockCertificate]         = ByteEncoder.derived
  given ByteDecoder[LockCertificate]         = ByteDecoder.derived
  val codec: CanonicalCodec[LockCertificate] =
    CanonicalCodec.derived(validateShape)

  def validateShape(value: LockCertificate): Either[CoreFailure, Unit] =
    LockSubject
      .validate(value.subject)
      .flatMap(_ => CertificationChecks.voteShape(value.votes))

  def id(value: LockCertificate): Either[CoreFailure, Hash] =
    codec.encode(value).map(Commitment.hash(Domain, _))

  def verify(
      value: LockCertificate,
      manifest: ProtocolManifest,
      authentication: ArtifactAuthentication,
  ): Either[CoreFailure, Unit] =
    for
      _ <- validateShape(value)
      _ <- CertificationChecks.context(
        value.subject.context,
        value.subject.manifestDigest,
        manifest,
      )
      _ <- CertificationChecks.deadline(
        value.subject.admissionBase,
        value.subject.lastInclusionHeight,
        manifest.maxLockLifetimeBlocks,
      )
      _ <- authentication.verifyFinalizedBase(
        value.subject.context,
        value.subject.admissionBase,
      )
      preimage <- LockSubject.signingPreimage(value.subject)
      _        <- CertificationChecks.quorum(
        value.subject.context,
        value.votes,
        preimage,
        authentication,
      )
    yield ()

final case class EffectCertificate(
    subject: EffectSubject,
    votes: Vector[ValidatorSignature],
)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object EffectCertificate:
  val Domain: Text = Utf8("sigilaris.application.effect.certificate.v2")
  given ByteEncoder[EffectCertificate]         = ByteEncoder.derived
  given ByteDecoder[EffectCertificate]         = ByteDecoder.derived
  val codec: CanonicalCodec[EffectCertificate] =
    CanonicalCodec.derived(validateShape)

  def validateShape(value: EffectCertificate): Either[CoreFailure, Unit] =
    EffectSubject
      .validate(value.subject)
      .flatMap(_ => CertificationChecks.voteShape(value.votes))

  def id(value: EffectCertificate): Either[CoreFailure, Hash] =
    codec.encode(value).map(Commitment.hash(Domain, _))

  def verify(
      value: EffectCertificate,
      lock: LockCertificate,
      manifest: ProtocolManifest,
      authentication: ArtifactAuthentication,
  ): Either[CoreFailure, Unit] =
    for
      _ <- validateShape(value)
      _ <- LockCertificate.verify(lock, manifest, authentication)
      _ <- CertificationChecks.context(
        value.subject.context,
        value.subject.manifestDigest,
        manifest,
      )
      lockId <- LockCertificate.id(lock)
      _      <- V2Validation.require(
        value.subject.inputLockCertificateId == lockId &&
          value.subject.context == lock.subject.context &&
          value.subject.txId == lock.subject.txId &&
          value.subject.executionId == lock.subject.executionId &&
          value.subject.manifestDigest == lock.subject.manifestDigest &&
          value.subject.dependencyPlanDigest == lock.subject.dependencyPlanDigest &&
          value.subject.lastInclusionHeight == lock.subject.lastInclusionHeight,
        FailureCode.CertificateMismatch,
        "effect.subject",
      )
      preimage <- EffectSubject.signingPreimage(value.subject)
      _        <- CertificationChecks.quorum(
        value.subject.context,
        value.votes,
        preimage,
        authentication,
      )
    yield ()

trait ArtifactAuthentication:
  def historicalValidators(
      context: DomainContext,
  ): Either[CoreFailure, Vector[Text]]
  def verifySignature(
      context: DomainContext,
      signer: Text,
      preimage: Bytes,
      signature: Bytes,
  ): Either[CoreFailure, Unit]
  def verifyFinalizedBase(
      context: DomainContext,
      base: AdmissionBase,
  ): Either[CoreFailure, Unit]

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
private[v2] object CertificationChecks:
  def family(
      digest: Hash,
      manifest: ProtocolManifest,
  ): Either[CoreFailure, InputManifest] =
    manifest.families
      .traverse(value => InputManifest.digest(value).map(_ -> value))
      .flatMap(values =>
        values
          .collectFirst { case (hash, value) if hash == digest => value }
          .toRight(
            CoreFailure.at(FailureCode.ManifestMismatch, "manifestDigest"),
          ),
      )

  def context(
      value: DomainContext,
      familyDigest: Hash,
      manifest: ProtocolManifest,
  ): Either[CoreFailure, Unit] =
    for
      _        <- ProtocolManifest.validate(manifest)
      expected <- ProtocolManifest.context(manifest)
      _        <- V2Validation.require(
        value == expected,
        FailureCode.ManifestMismatch,
        "context",
      )
      _ <- family(familyDigest, manifest)
    yield ()

  def deadline(
      base: AdmissionBase,
      last: Height,
      maximum: Long,
  ): Either[CoreFailure, Unit] =
    val start = AdmissionBase.height(base).toBigNat.toBigInt
    val end   = last.toBigNat.toBigInt
    V2Validation.require(
      maximum > 0 && start < end && end <= start + BigInt(maximum),
      FailureCode.InvalidDeadline,
      "lastInclusionHeight",
    )

  def voteShape(votes: Vector[ValidatorSignature]): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.require(
        votes.nonEmpty,
        FailureCode.InvalidLength,
        "votes",
      )
      _ <- V2Validation.sortedUnique(
        votes.map(value => V2Validation.textKey(value.validatorId)),
        "votes",
      )
      _ <- V2Validation.all(votes.map(ValidatorSignature.validate))
    yield ()

  def quorum(
      context: DomainContext,
      votes: Vector[ValidatorSignature],
      preimage: Bytes,
      authentication: ArtifactAuthentication,
  ): Either[CoreFailure, Unit] =
    for
      validators <- authentication.historicalValidators(context)
      _          <- V2Validation.require(
        validators.nonEmpty,
        FailureCode.ProofInvalid,
        "validators",
      )
      _ <- V2Validation.all(
        validators.map(value => V2Validation.identifier(value, "validators")),
      )
      keys = validators.map(V2Validation.textKey)
      _ <- V2Validation.require(
        keys.distinct.sizeCompare(keys.size) == 0,
        FailureCode.DuplicateSigner,
        "validators",
      )
      _ <- V2Validation.require(
        votes.size.toLong >= (validators.size.toLong * 2L) / 3L + 1L,
        FailureCode.QuorumNotReached,
        "votes",
      )
      members = keys.toSet
      _ <- votes.traverse_(vote =>
        V2Validation
          .require(
            members.contains(V2Validation.textKey(vote.validatorId)),
            FailureCode.UnknownSigner,
            "vote.validatorId",
          )
          .flatMap(_ =>
            authentication.verifySignature(
              context,
              vote.validatorId,
              preimage,
              vote.signature,
            ),
          ),
      )
    yield ()

/** Certificate binding checks. The caller also authenticates input resolution,
  * signed transactions and actual execution through the configured entry
  * verifier.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object AdmissionValidation:
  def validateSource(
      entry: PlanEntry,
      input: InputDescriptor,
      lock: Option[LockCertificate],
      effect: Option[EffectCertificate],
      manifest: ProtocolManifest,
      authentication: ArtifactAuthentication,
  ): Either[CoreFailure, Unit] =
    for
      _       <- ProtocolManifest.validate(manifest)
      context <- ProtocolManifest.context(manifest)
      family  <- CertificationChecks.family(entry.manifestDigest, manifest)
      _       <- V2Validation.require(
        input.manifestDigest == entry.manifestDigest,
        FailureCode.ManifestMismatch,
        "input.manifestDigest",
      )
      inputs <- InputDerivation.lockInputs(family, input)
      eligible = inputs.map(_.stableId)
      _ <- V2Validation.require(
        input.fullInputCommitment == entry.fullInputCommitment && input.lockSubsetCommitment == entry.lockSubsetCommitment,
        FailureCode.CommitmentMismatch,
        "entry.inputs",
      )
      declarationDigest <- Declaration.digest(entry.declaration)
      _                 <- V2Validation.require(
        declarationDigest == entry.declarationDigest,
        FailureCode.CommitmentMismatch,
        "entry.declarationDigest",
      )
      source = entry.source match
        case PlanSource.ConsensusTransaction(txId, bytes, lockId) =>
          (txId, bytes, lockId, Option.empty[Hash])
        case PlanSource.CertifiedFastExecution(txId, bytes, lockId, effectId) =>
          (txId, bytes, Some(lockId), Some(effectId))
      (txId, signedTransaction, lockReference, effectReference) = source
      identity <- ExecutionIdentity.compute(
        ExecutionIdentityInput(
          context,
          entry.manifestDigest,
          txId,
          signedTransaction,
          entry.fullInputCommitment,
          entry.lockSubsetCommitment,
          entry.declarationDigest,
          entry.dependencyPlanDigest,
          entry.lastInclusionHeight,
        ),
      )
      _ <- V2Validation.require(
        identity == entry.executionId,
        FailureCode.CommitmentMismatch,
        "entry.executionId",
      )
      _ <- entry.source match
        case PlanSource.CertifiedFastExecution(_, _, _, _) =>
          V2Validation.require(
            eligible.nonEmpty,
            FailureCode.InvalidLength,
            "entry.source",
          )
        case _ => Right[CoreFailure, Unit](())
      _ <- validateLock(
        entry,
        txId,
        eligible,
        lockReference,
        lock,
        manifest,
        authentication,
      )
      _ <- entry.source match
        case PlanSource.ConsensusTransaction(_, _, _) =>
          V2Validation.require(
            effect.isEmpty,
            FailureCode.UnexpectedCertificate,
            "entry.source",
          )
        case PlanSource.CertifiedFastExecution(_, _, _, _) =>
          for
            _ <- V2Validation.require(
              entry.declaration match
                case Declaration.Exact(_) => true
                case _                    => false,
              FailureCode.ClassificationMismatch,
              "entry.declaration",
            )
            locked <- lock.toRight(
              CoreFailure.at(FailureCode.MissingCertificate, "entry.source"),
            )
            certified <- effect.toRight(
              CoreFailure.at(FailureCode.MissingCertificate, "entry.source"),
            )
            _ <- EffectCertificate.verify(
              certified,
              locked,
              manifest,
              authentication,
            )
            id <- EffectCertificate.id(certified)
            _  <- V2Validation.require(
              effectReference.contains(
                id,
              ) && certified.subject.actualFootprintCommitment == entry.actualFootprintCommitment,
              FailureCode.CertificateMismatch,
              "entry.effectCertificate",
            )
          yield ()
    yield ()

  private def validateLock(
      entry: PlanEntry,
      txId: Hash,
      eligible: Vector[InputId],
      reference: Option[Hash],
      certificate: Option[LockCertificate],
      manifest: ProtocolManifest,
      authentication: ArtifactAuthentication,
  ): Either[CoreFailure, Unit] =
    if eligible.isEmpty then
      V2Validation.require(
        reference.isEmpty && certificate.isEmpty,
        FailureCode.UnexpectedCertificate,
        "entry.source",
      )
    else
      for
        value <- certificate.toRight(
          CoreFailure.at(FailureCode.MissingCertificate, "entry.source"),
        )
        _  <- LockCertificate.verify(value, manifest, authentication)
        id <- LockCertificate.id(value)
        _  <- V2Validation.require(
          reference.contains(
            id,
          ) && value.subject.txId == txId && value.subject.executionId == entry.executionId &&
            value.subject.manifestDigest == entry.manifestDigest && value.subject.inputs == eligible &&
            value.subject.fullInputCommitment == entry.fullInputCommitment && value.subject.lockSubsetCommitment == entry.lockSubsetCommitment &&
            value.subject.dependencyPlanDigest == entry.dependencyPlanDigest && value.subject.lastInclusionHeight == entry.lastInclusionHeight,
          FailureCode.CertificateMismatch,
          "entry.lockCertificate",
        )
      yield ()
