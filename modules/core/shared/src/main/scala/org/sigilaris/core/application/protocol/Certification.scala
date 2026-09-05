package org.sigilaris.core.application.protocol

import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.datatype.Utf8

final case class ApplicationValidatorId(value: Utf8)

object ApplicationValidatorId:
  def parse(value: String): Either[String, ApplicationValidatorId] =
    Either.cond(
      value.trim.nonEmpty,
      ApplicationValidatorId(Utf8(value)),
      "application validator id must be non-blank",
    )
  given ByteEncoder[ApplicationValidatorId] = ByteEncoder.derived

final case class HistoricalApplicationValidatorSet(
    epoch: ApplicationEpoch,
    hash: ApplicationValidatorSetHash,
    validators: Vector[ApplicationValidatorId],
)

@SuppressWarnings(Array("org.wartremover.warts.Nothing"))
object HistoricalApplicationValidatorSet:
  def quorumSize(validatorCount: Int): Int =
    ((validatorCount * 2) / 3) + 1

  def validate(
      value: HistoricalApplicationValidatorSet,
  ): Either[ApplicationCertificateFailure, Unit] =
    if value.validators.isEmpty then
      Left(ApplicationCertificateFailure.EmptyValidatorSet)
    else
      value.validators
        .groupBy(_.value.asString)
        .collectFirst:
          case (id, entries) if entries.sizeCompare(1) > 0 => id
      match
        case Some(id) =>
          Left(ApplicationCertificateFailure.DuplicateValidator(id))
        case None => Right(())

final case class ApplicationLockVoteSubject(
    protocolVersion: ProtocolVersion,
    configurationDigest: ApplicationConfigurationDigest,
    epoch: ApplicationEpoch,
    validatorSetHash: ApplicationValidatorSetHash,
    executionId: ExecutionId,
    dependencyPlanDigest: DependencyPlanDigest,
    lastInclusionHeight: InclusionHeight,
    inputIds: Vector[ApplicationInputId],
)

object ApplicationLockVoteSubject:
  private val Domain = "sigilaris.application.lock.vote.v1"

  private final case class Preimage(
      domain: Utf8,
      subject: ApplicationLockVoteSubject,
  )
  private object Preimage:
    given ByteEncoder[Preimage] = ByteEncoder.derived

  given ByteEncoder[ApplicationLockVoteSubject] = ByteEncoder.derived

  def signingPreimage(subject: ApplicationLockVoteSubject): ByteVector =
    Preimage(Utf8(Domain), canonical(subject)).toBytes

  def canonical(
      subject: ApplicationLockVoteSubject,
  ): ApplicationLockVoteSubject =
    subject.copy(inputIds = subject.inputIds.sortBy(_.toHex))

final case class ApplicationLockVote(
    subject: ApplicationLockVoteSubject,
    signer: ApplicationValidatorId,
    signature: ByteVector,
)

object ApplicationLockVote:
  private final case class Encoding(
      subject: ApplicationLockVoteSubject,
      signer: ApplicationValidatorId,
      signature: ByteVector,
  )
  private object Encoding:
    given ByteEncoder[Encoding] = ByteEncoder.derived

  given ByteEncoder[ApplicationLockVote] =
    ByteEncoder[Encoding].contramap: vote =>
      Encoding(
        ApplicationLockVoteSubject.canonical(vote.subject),
        vote.signer,
        vote.signature,
      )

final case class ApplicationLockCertificate(
    subject: ApplicationLockVoteSubject,
    votes: Vector[ApplicationLockVote],
)

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object ApplicationLockCertificate:
  private val Domain = "sigilaris.application.lock.certificate.v1"

  private final case class Preimage(
      domain: Utf8,
      subject: ApplicationLockVoteSubject,
      votes: Vector[ApplicationLockVote],
  )
  private object Preimage:
    given ByteEncoder[Preimage] = ByteEncoder.derived

  def canonicalVotes(
      votes: Vector[ApplicationLockVote],
  ): Vector[ApplicationLockVote] =
    votes
      .map(vote =>
        vote.copy(subject = ApplicationLockVoteSubject.canonical(vote.subject)),
      )
      .sortBy(_.signer.value.asString)

  given ByteEncoder[ApplicationLockCertificate] =
    ByteEncoder[Preimage].contramap: certificate =>
      Preimage(
        Utf8(Domain),
        ApplicationLockVoteSubject.canonical(certificate.subject),
        canonicalVotes(certificate.votes),
      )

  def commitmentPreimage(
      certificate: ApplicationLockCertificate,
  ): ByteVector =
    Preimage(
      Utf8(Domain),
      ApplicationLockVoteSubject.canonical(certificate.subject),
      canonicalVotes(certificate.votes),
    ).toBytes

  def verify(
      certificate: ApplicationLockCertificate,
      historicalSet: HistoricalApplicationValidatorSet,
  )(
      verifySignature: (
          ApplicationValidatorId,
          ByteVector,
          ByteVector,
      ) => Boolean,
  ): Either[ApplicationCertificateFailure, Unit] =
    for
      _ <- validateInputShape(certificate.subject)
      _ <- ApplicationCertificateVerifier.verifyVotes(
        subject = certificate.subject,
        votes = certificate.votes,
        historicalSet = historicalSet,
        voteSubject = _.subject,
        voteSigner = _.signer,
        voteSignature = _.signature,
        signingPreimage = ApplicationLockVoteSubject.signingPreimage,
        verifySignature = verifySignature,
      )
    yield ()

  private def validateInputShape(
      subject: ApplicationLockVoteSubject,
  ): Either[ApplicationCertificateFailure, Unit] =
    if subject.inputIds.isEmpty then
      Left(ApplicationCertificateFailure.EmptyLockInputs)
    else
      subject.inputIds
        .groupBy(_.toHex)
        .collectFirst:
          case (_, entries) if entries.sizeCompare(1) > 0 => entries.headOption
        .flatten match
        case Some(duplicate) =>
          Left(ApplicationCertificateFailure.DuplicateLockInput(duplicate))
        case None =>
          Either.cond(
            subject.inputIds == subject.inputIds.sortBy(_.toHex),
            (),
            ApplicationCertificateFailure.NonCanonicalLockInputs,
          )

final case class CertifiedEffectVoteSubject(
    protocolVersion: ProtocolVersion,
    configurationDigest: ApplicationConfigurationDigest,
    epoch: ApplicationEpoch,
    validatorSetHash: ApplicationValidatorSetHash,
    executionId: ExecutionId,
    dependencyPlanDigest: DependencyPlanDigest,
    lastInclusionHeight: InclusionHeight,
    resultDigest: ApplicationResultDigest,
    stateRoot: ApplicationStateRoot,
)

object CertifiedEffectVoteSubject:
  private val Domain = "sigilaris.application.effect.vote.v1"

  private final case class Preimage(
      domain: Utf8,
      subject: CertifiedEffectVoteSubject,
  )
  private object Preimage:
    given ByteEncoder[Preimage] = ByteEncoder.derived

  given ByteEncoder[CertifiedEffectVoteSubject] = ByteEncoder.derived

  def signingPreimage(subject: CertifiedEffectVoteSubject): ByteVector =
    Preimage(Utf8(Domain), subject).toBytes

final case class CertifiedEffectVote(
    subject: CertifiedEffectVoteSubject,
    signer: ApplicationValidatorId,
    signature: ByteVector,
)

object CertifiedEffectVote:
  given ByteEncoder[CertifiedEffectVote] = ByteEncoder.derived

final case class CertifiedEffectCertificate(
    subject: CertifiedEffectVoteSubject,
    votes: Vector[CertifiedEffectVote],
)

@SuppressWarnings(Array("org.wartremover.warts.Nothing"))
object CertifiedEffectCertificate:
  private val Domain = "sigilaris.application.effect.certificate.v1"

  private final case class Preimage(
      domain: Utf8,
      subject: CertifiedEffectVoteSubject,
      votes: Vector[CertifiedEffectVote],
  )
  private object Preimage:
    given ByteEncoder[Preimage] = ByteEncoder.derived

  def canonicalVotes(
      votes: Vector[CertifiedEffectVote],
  ): Vector[CertifiedEffectVote] =
    votes.sortBy(_.signer.value.asString)

  given ByteEncoder[CertifiedEffectCertificate] =
    ByteEncoder[Preimage].contramap: certificate =>
      Preimage(
        Utf8(Domain),
        certificate.subject,
        canonicalVotes(certificate.votes),
      )

  def commitmentPreimage(
      certificate: CertifiedEffectCertificate,
  ): ByteVector =
    Preimage(
      Utf8(Domain),
      certificate.subject,
      canonicalVotes(certificate.votes),
    ).toBytes

  def verify(
      certificate: CertifiedEffectCertificate,
      historicalSet: HistoricalApplicationValidatorSet,
  )(
      verifySignature: (
          ApplicationValidatorId,
          ByteVector,
          ByteVector,
      ) => Boolean,
  ): Either[ApplicationCertificateFailure, Unit] =
    ApplicationCertificateVerifier.verifyVotes(
      subject = certificate.subject,
      votes = certificate.votes,
      historicalSet = historicalSet,
      voteSubject = _.subject,
      voteSigner = _.signer,
      voteSignature = _.signature,
      signingPreimage = CertifiedEffectVoteSubject.signingPreimage,
      verifySignature = verifySignature,
    )

enum ApplicationCertificateFailure:
  case EmptyValidatorSet
  case DuplicateValidator(validatorId: String)
  case EpochMismatch
  case ValidatorSetHashMismatch
  case SubjectMismatch(validatorId: ApplicationValidatorId)
  case UnknownSigner(validatorId: ApplicationValidatorId)
  case DuplicateSigner(validatorId: ApplicationValidatorId)
  case InvalidSignature(validatorId: ApplicationValidatorId)
  case QuorumNotReached(required: Int, actual: Int)
  case EmptyLockInputs
  case DuplicateLockInput(inputId: ApplicationInputId)
  case NonCanonicalLockInputs

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
private object ApplicationCertificateVerifier:
  def verifyVotes[S, V](
      subject: S,
      votes: Vector[V],
      historicalSet: HistoricalApplicationValidatorSet,
      voteSubject: V => S,
      voteSigner: V => ApplicationValidatorId,
      voteSignature: V => ByteVector,
      signingPreimage: S => ByteVector,
      verifySignature: (
          ApplicationValidatorId,
          ByteVector,
          ByteVector,
      ) => Boolean,
  )(using
      subjectEpoch: SubjectEpoch[S],
  ): Either[ApplicationCertificateFailure, Unit] =
    for
      _ <- HistoricalApplicationValidatorSet.validate(historicalSet)
      _ <- Either.cond(
        subjectEpoch.epoch(subject) == historicalSet.epoch,
        (),
        ApplicationCertificateFailure.EpochMismatch,
      )
      _ <- Either.cond(
        subjectEpoch
          .validatorSetHash(subject)
          .toUInt256 == historicalSet.hash.toUInt256,
        (),
        ApplicationCertificateFailure.ValidatorSetHashMismatch,
      )
      _ <- verifyEach(
        subject,
        votes,
        historicalSet,
        voteSubject,
        voteSigner,
        voteSignature,
        signingPreimage,
        verifySignature,
      )
      required = HistoricalApplicationValidatorSet.quorumSize(
        historicalSet.validators.size,
      )
      _ <- Either.cond(
        votes.sizeCompare(required) >= 0,
        (),
        ApplicationCertificateFailure.QuorumNotReached(required, votes.size),
      )
    yield ()

  private def verifyEach[S, V](
      subject: S,
      votes: Vector[V],
      historicalSet: HistoricalApplicationValidatorSet,
      voteSubject: V => S,
      voteSigner: V => ApplicationValidatorId,
      voteSignature: V => ByteVector,
      signingPreimage: S => ByteVector,
      verifySignature: (
          ApplicationValidatorId,
          ByteVector,
          ByteVector,
      ) => Boolean,
  ): Either[ApplicationCertificateFailure, Unit] =
    votes
      .foldLeft[Either[ApplicationCertificateFailure, Set[String]]](
        Right(Set.empty),
      ):
        case (Right(seen), vote) =>
          val signer      = voteSigner(vote)
          val signerValue = signer.value.asString
          if voteSubject(vote) != subject then
            Left(ApplicationCertificateFailure.SubjectMismatch(signer))
          else if !historicalSet.validators.contains(signer) then
            Left(ApplicationCertificateFailure.UnknownSigner(signer))
          else if seen.contains(signerValue) then
            Left(ApplicationCertificateFailure.DuplicateSigner(signer))
          else if !verifySignature(
              signer,
              signingPreimage(subject),
              voteSignature(vote),
            )
          then Left(ApplicationCertificateFailure.InvalidSignature(signer))
          else Right(seen + signerValue)
        case (left @ Left(_), _) => left
      .map(_ => ())

private trait SubjectEpoch[S]:
  def epoch(subject: S): ApplicationEpoch
  def validatorSetHash(subject: S): ApplicationValidatorSetHash

private object SubjectEpoch:
  given SubjectEpoch[ApplicationLockVoteSubject] with
    override def epoch(subject: ApplicationLockVoteSubject): ApplicationEpoch =
      subject.epoch
    override def validatorSetHash(
        subject: ApplicationLockVoteSubject,
    ): ApplicationValidatorSetHash = subject.validatorSetHash

  given SubjectEpoch[CertifiedEffectVoteSubject] with
    override def epoch(subject: CertifiedEffectVoteSubject): ApplicationEpoch =
      subject.epoch
    override def validatorSetHash(
        subject: CertifiedEffectVoteSubject,
    ): ApplicationValidatorSetHash = subject.validatorSetHash

final case class VerifiedApplicationExecution(
    executionId: ExecutionId,
    input: ApplicationInputDescriptor,
    dependencyPlanDigest: DependencyPlanDigest,
    lastInclusionHeight: InclusionHeight,
    normalizedResult: NormalizedApplicationResult,
    stateRoot: ApplicationStateRoot,
)
