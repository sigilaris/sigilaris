package org.sigilaris.node.jvm.runtime.application.v2

import java.nio.file.Path
import cats.data.EitherT
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import org.sigilaris.core.application.protocol.{
  ApplicationLockVoteSubject,
  ApplicationConfigurationDigest,
  ApplicationEpoch,
  ApplicationValidatorSetHash,
  DependencyPlanDigest,
  ExecutionId,
  ProtocolVersion,
}
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8
import V2Codecs.given

/** An explicit supported legacy adapter installed at original deployment birth.
  * No unmodified M1/M2 store is inferred to have retained this evidence.
  */
final case class HistoricalIssuancePolicy(
    format: Long,
    context: DomainContext,
    signerId: Text,
    publicKey: Bytes,
    lockIssuance: IssuanceCapability,
    effectIssuance: IssuanceCapability,
    maxLifetime: Long,
    baseUpperBound: Height,
    originalDeploymentEvidence: Bytes,
)
object HistoricalIssuancePolicy:
  given ByteEncoder[HistoricalIssuancePolicy] = ByteEncoder.derived
  given ByteDecoder[HistoricalIssuancePolicy] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[HistoricalIssuancePolicy](v =>
    for
      _ <- V2Validation.format(v.format, 1L, "historicalIssuance.policy")
      _ <- DomainContext.validate(v.context)
      _ <- InitialValidator.validate(InitialValidator(v.signerId, v.publicKey))
      _ <- V2Validation.require(
        v.context.protocolVersion == 1L && v.maxLifetime > 0L &&
          v.originalDeploymentEvidence.nonEmpty && v.effectIssuance == IssuanceCapability.Unavailable,
        FailureCode.UnsupportedTuple,
        "historicalIssuance.supportedProfile",
      )
    yield (),
  )
  val Domain = Utf8("sigilaris.application.historical-issuance.policy.v1")
  def digest(v: HistoricalIssuancePolicy): Either[CoreFailure, Hash] =
    codec.encode(v).map(Commitment.hash(Domain, _))

/** Field-for-field M1 subject codec; the actual legacy signing encoder remains
  * authoritative. The new request adds the full chain context and original
  * source evidence without changing those old signed bytes.
  */
final case class HistoricalLockFields(
    protocolVersion: Long,
    configurationDigest: Hash,
    epoch: Long,
    validatorSetHash: Hash,
    executionId: ExecutionId,
    dependencyPlanDigest: Hash,
    lastInclusionHeight: Height,
    inputIds: Vector[InputId],
):
  def subject: ApplicationLockVoteSubject = ApplicationLockVoteSubject(
    ProtocolVersion.M1,
    ApplicationConfigurationDigest(configurationDigest),
    ApplicationEpoch(epoch),
    ApplicationValidatorSetHash(validatorSetHash),
    executionId,
    DependencyPlanDigest(dependencyPlanDigest),
    lastInclusionHeight,
    inputIds,
  )
object HistoricalLockFields:
  given ByteEncoder[HistoricalLockFields] = ByteEncoder.derived
  given ByteDecoder[HistoricalLockFields] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[HistoricalLockFields](v =>
    for
      _ <- V2Validation.require(
        v.protocolVersion == 1L && v.epoch >= 0L && v.inputIds.nonEmpty,
        FailureCode.UnsupportedTuple,
        "historicalIssuance.lockSubject",
      )
      _ <- V2Validation
        .sortedUnique(v.inputIds.map(_.toHex), "historicalIssuance.inputs")
    yield (),
  )
  def from(subject: ApplicationLockVoteSubject): HistoricalLockFields =
    HistoricalLockFields(
      subject.protocolVersion.value.toLong,
      subject.configurationDigest.toUInt256,
      subject.epoch.value,
      subject.validatorSetHash.toUInt256,
      subject.executionId,
      subject.dependencyPlanDigest.toUInt256,
      subject.lastInclusionHeight,
      subject.inputIds,
    )

final case class HistoricalIssuanceRequest(
    domain: Text,
    format: Long,
    context: DomainContext,
    baseHeight: Height,
    subject: HistoricalLockFields,
    sourceEvidence: Bytes,
)
object HistoricalIssuanceRequest:
  val Domain = Utf8("sigilaris.application.historical-issuance.request.v1")
  given ByteEncoder[HistoricalIssuanceRequest] = ByteEncoder.derived
  given ByteDecoder[HistoricalIssuanceRequest] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[HistoricalIssuanceRequest](v =>
    for
      _ <- V2Validation.format(v.format, 1L, "historicalIssuance.request")
      _ <- DomainContext.validate(v.context)
      _ <- HistoricalLockFields.codec.encode(v.subject).map(_ => ())
      _ <- V2Validation.require(
        v.domain == Domain && v.context.protocolVersion == v.subject.protocolVersion &&
          v.context.configurationDigest == v.subject.configurationDigest && v.context.epoch == v.subject.epoch &&
          v.context.validatorSetHash == v.subject.validatorSetHash && v.sourceEvidence.nonEmpty,
        FailureCode.MembershipMismatch,
        "historicalIssuance.requestContext",
      )
      _ <- V2Validation.require(
        ByteEncoder[ApplicationLockVoteSubject].encode(v.subject.subject) ==
          ByteEncoder[HistoricalLockFields].encode(v.subject),
        FailureCode.NonCanonicalEncoding,
        "historicalIssuance.originalSubjectBytes",
      )
    yield (),
  )
  def digest(v: HistoricalIssuanceRequest): Either[CoreFailure, Hash] =
    codec.encode(v).map(Commitment.hash(Domain, _))
  def signingPreimage(v: HistoricalIssuanceRequest): Bytes =
    ApplicationLockVoteSubject.signingPreimage(v.subject.subject)

/** Original authentication reads independent retained birth/source/finality
  * artifacts. No callback may reacquire the controller or lifecycle gate.
  * Returning a finalized height requires actual same-context finality AND
  * nonapplication for this exact execution, never a supplied numeric horizon.
  */
trait HistoricalIssuanceAuthentication:
  def policy(value: HistoricalIssuancePolicy): Result[IO, Unit]
  def lock(
      policy: HistoricalIssuancePolicy,
      request: HistoricalIssuanceRequest,
  ): Result[IO, Unit]
  def finalizedNonapplication(
      policy: HistoricalIssuancePolicy,
      request: HistoricalIssuanceRequest,
      originalProof: Bytes,
  ): Result[IO, Height]
  def currentFinalizedHeight(
      policy: HistoricalIssuancePolicy,
  ): Result[IO, Height]

final case class HistoricalIssuanceRecord(
    format: Long,
    sequence: Long,
    previousDigest: Hash,
    policyDigest: Hash,
    request: Bytes,
    terminalEvidence: Option[Bytes],
)
object HistoricalIssuanceRecord:
  given ByteEncoder[HistoricalIssuanceRecord] = ByteEncoder.derived
  given ByteDecoder[HistoricalIssuanceRecord] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[HistoricalIssuanceRecord](v =>
    for
      _ <- V2Validation.format(v.format, 1L, "historicalIssuance.record")
      _ <- V2Validation.require(
        v.sequence > 0L && v.request.nonEmpty && v.terminalEvidence.forall(
          _.nonEmpty,
        ),
        FailureCode.InvalidLength,
        "historicalIssuance.recordFields",
      )
      _ <- HistoricalIssuanceRequest.codec.decode(v.request).map(_ => ())
    yield (),
  )
  val Domain = Utf8("sigilaris.application.historical-issuance.record.v1")
  def digest(v: HistoricalIssuanceRecord): Either[CoreFailure, Hash] =
    codec.encode(v).map(Commitment.hash(Domain, _))

final case class HistoricalIssuanceArchive(
    policy: HistoricalIssuancePolicy,
    records: Vector[Bytes],
)
object HistoricalIssuanceArchive:
  given ByteEncoder[HistoricalIssuanceArchive] = ByteEncoder.derived
  given ByteDecoder[HistoricalIssuanceArchive] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[HistoricalIssuanceArchive](v =>
    HistoricalIssuancePolicy.codec.encode(v.policy).map(_ => ()),
  )
  val Domain = Utf8("sigilaris.application.historical-issuance.archive.v1")
  def digest(v: HistoricalIssuanceArchive): Either[CoreFailure, Hash] =
    codec.encode(v).map(Commitment.hash(Domain, _))

final case class HistoricalIssuedClaim(
    request: HistoricalIssuanceRequest,
    requestDigest: Hash,
    terminalEvidence: Option[Bytes],
    terminalHeight: Option[Height],
)
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object HistoricalIssuanceSafety:
  private val zero =
    org.sigilaris.core.datatype.UInt256.unsafeFromBigIntUnsigned(BigInt(0))
  private def core[A](v: Either[CoreFailure, A]): Result[IO, A] =
    EitherT.fromEither[IO](RuntimeCheck.core(v))
  private def check(c: Boolean, d: String): Result[IO, Unit] =
    EitherT.fromEither[IO](
      RuntimeCheck.require(c, RuntimeFailureCode.ProofInvalid, d),
    )
  final class Verified private[HistoricalIssuanceSafety] (
      val archive: HistoricalIssuanceArchive,
      val policyDigest: Hash,
      val sequence: Long,
      val digest: Hash,
      val claims: Vector[HistoricalIssuedClaim],
  ):
    def live: Vector[HistoricalIssuedClaim] =
      claims.filter(_.terminalEvidence.isEmpty)
    def greatestDeadline: Option[Height] =
      claims.map(_.request.subject.lastInclusionHeight).maxOption
  def verify(
      archive: HistoricalIssuanceArchive,
      authentication: HistoricalIssuanceAuthentication,
  ): Result[IO, Verified] = for
    policyDigest <- core(HistoricalIssuancePolicy.digest(archive.policy))
    _            <- authentication.policy(archive.policy)
    state        <- archive.records.foldLeft(
      EitherT.pure[IO, V2RuntimeFailure](
        new Verified(archive, policyDigest, 0L, zero, Vector.empty),
      ),
    )((result, raw) =>
      result.flatMap { current =>
        for
          record  <- core(HistoricalIssuanceRecord.codec.decode(raw))
          request <- core(
            HistoricalIssuanceRequest.codec.decode(record.request),
          )
          requestDigest <- core(HistoricalIssuanceRequest.digest(request))
          _             <- check(
            current.sequence < Long.MaxValue && record.sequence == current.sequence + 1L &&
              record.previousDigest == current.digest && record.policyDigest == policyDigest,
            "original issuance history sequence, predecessor or birth policy changed",
          )
          _ <- check(
            request.context == archive.policy.context && archive.policy.lockIssuance == IssuanceCapability.Enabled &&
              request.baseHeight.toBigNat.toBigInt <= archive.policy.baseUpperBound.toBigNat.toBigInt &&
              request.subject.lastInclusionHeight.toBigNat.toBigInt > request.baseHeight.toBigNat.toBigInt &&
              request.subject.lastInclusionHeight.toBigNat.toBigInt <= request.baseHeight.toBigNat.toBigInt + BigInt(
                archive.policy.maxLifetime,
              ),
            "original issuance exceeds its birth-bound source context, base or signed deadline policy",
          )
          _ <- authentication.lock(archive.policy, request)
          existing = current.claims.find(
            _.request.subject.executionId == request.subject.executionId,
          )
          next <- record.terminalEvidence match
            case None =>
              for
                _ <- check(
                  existing.isEmpty,
                  "original issuance repeated or changed an immutable execution subject",
                )
                _ <- check(
                  current.live.forall(c =>
                    !c.request.subject.inputIds
                      .exists(request.subject.inputIds.contains),
                  ),
                  "original lock issuance conflicts with a retained live original claim",
                )
              yield current.claims :+ HistoricalIssuedClaim(
                request,
                requestDigest,
                None,
                None,
              )
            case Some(proof) =>
              for
                claim <- EitherT.fromOption[IO](
                  existing,
                  V2RuntimeFailure.at(
                    RuntimeFailureCode.EvidenceMissing,
                    "expiry has no original issued claim",
                  ),
                )
                _ <- check(
                  claim.request == request && claim.terminalEvidence.isEmpty,
                  "expiry changed its original subject or repeated a terminal transition",
                )
                finalized <- authentication.finalizedNonapplication(
                  archive.policy,
                  request,
                  proof,
                )
                _ <- check(
                  finalized.toBigNat.toBigInt > request.subject.lastInclusionHeight.toBigNat.toBigInt,
                  "original claim cannot expire at or before its signed deadline",
                )
              yield current.claims.map(c =>
                if c.requestDigest == requestDigest then
                  c.copy(
                    terminalEvidence = Some(proof),
                    terminalHeight = Some(finalized),
                  )
                else c,
              )
          digest <- core(HistoricalIssuanceRecord.digest(record))
        yield new Verified(archive, policyDigest, record.sequence, digest, next)
      },
    )
  yield state

sealed trait HistoricalIssuanceSafetyStore:
  def policy: HistoricalIssuancePolicy
  def beforeLock(request: HistoricalIssuanceRequest): Result[IO, Bytes]
  def issue(request: HistoricalIssuanceRequest): Result[IO, ControllerSignature]
  private[v2] def issueWith(
      controller: FenceController,
      request: HistoricalIssuanceRequest,
  ): Result[IO, ControllerSignature]
  def authenticateLock(
      raw: Bytes,
      signerId: Text,
      publicKey: Bytes,
  ): Result[IO, ControllerSigningMaterial]
  def authorizeLock(
      raw: Bytes,
      signerId: Text,
      publicKey: Bytes,
  ): Result[IO, Unit]
  def expire(raw: Bytes, originalProof: Bytes): Result[IO, Unit]
  def recover: Result[IO, HistoricalIssuanceSafety.Verified]
  def archive: Result[IO, HistoricalIssuanceArchive]

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object HistoricalIssuanceSafetyStore:
  private final case class Permission(request: Bytes, records: Vector[Bytes])
  def coordinated(
      original: HistoricalIssuanceSafetyStore,
      controller: FenceController,
      lifecycle: ConsistencyMutationGate,
  ): Result[IO, HistoricalIssuanceSafetyStore] =
    lifecycle
      .verifyController(controller, original.policy.context)
      .as(new HistoricalIssuanceSafetyStore:
        val policy = original.policy
        def beforeLock(request: HistoricalIssuanceRequest): Result[IO, Bytes] =
          lifecycle.mutate(original.beforeLock(request))
        def issue(
            request: HistoricalIssuanceRequest,
        ): Result[IO, ControllerSignature] =
          lifecycle.mutate(original.issueWith(controller, request))
        private[v2] def issueWith(
            c: FenceController,
            r: HistoricalIssuanceRequest,
        ): Result[IO, ControllerSignature] =
          if c eq controller then issue(r)
          else
            EitherT.leftT(
              V2RuntimeFailure.at(
                RuntimeFailureCode.InvalidRequest,
                "issuance controller changed",
              ),
            )
        def authenticateLock(
            raw: Bytes,
            id: Text,
            key: Bytes,
        ): Result[IO, ControllerSigningMaterial] =
          original.authenticateLock(raw, id, key)
        def authorizeLock(raw: Bytes, id: Text, key: Bytes): Result[IO, Unit] =
          original.authorizeLock(raw, id, key)
        def expire(raw: Bytes, proof: Bytes): Result[IO, Unit] =
          lifecycle.mutate(original.expire(raw, proof))
        def recover: Result[IO, HistoricalIssuanceSafety.Verified] =
          original.recover
        def archive: Result[IO, HistoricalIssuanceArchive] = original.archive)
  def resource(
      path: Path,
      installed: HistoricalIssuancePolicy,
      authentication: HistoricalIssuanceAuthentication,
      maximumBytes: Long,
      faults: JournalFaultInjector[IO],
  ): Resource[IO, HistoricalIssuanceSafetyStore] =
    def required[A](v: Result[IO, A]): IO[A] = v.value.flatMap(
      _.fold(e => IO.raiseError(new JournalOpenException(e)), IO.pure),
    )
    Resource
      .eval(required(for
        bytes <- EitherT.fromEither[IO](
          RuntimeCheck.core(HistoricalIssuancePolicy.codec.encode(installed)),
        )
        _ <- authentication.policy(installed)
      yield bytes))
      .flatMap(identity =>
        CanonicalAppendLog
          .resource(path, identity, maximumBytes, faults)
          .flatMap(log =>
            Resource
              .eval(
                (
                  Semaphore[IO](1L),
                  Ref.of[IO, Boolean](false),
                  Ref.of[IO, Option[Permission]](None),
                ).tupled,
              )
              .flatMap { (gate, ready, permission) =>
                val store = new HistoricalIssuanceSafetyStore:
                  val policy = installed
                  private def core[A](
                      v: Either[CoreFailure, A],
                  ): Result[IO, A] =
                    EitherT.fromEither[IO](RuntimeCheck.core(v))
                  private def check(c: Boolean, d: String): Result[IO, Unit] =
                    EitherT.fromEither[IO](
                      RuntimeCheck
                        .require(c, RuntimeFailureCode.ProofInvalid, d),
                    )
                  private def current
                      : Result[IO, HistoricalIssuanceSafety.Verified] =
                    log.recover.flatMap(rows =>
                      HistoricalIssuanceSafety.verify(
                        HistoricalIssuanceArchive(policy, rows),
                        authentication,
                      ),
                    )
                  private def under[A](recovering: Boolean)(
                      run: Result[IO, A],
                  ): Result[IO, A] = EitherT(
                    gate.permit.use(_ =>
                      IO.uncancelable(_ =>
                        ready.get.flatMap(enabled =>
                          if !enabled && !recovering then
                            IO.pure(
                              Left(
                                V2RuntimeFailure.at(
                                  RuntimeFailureCode.RecoveryRequired,
                                  "original issuance journal requires recovery",
                                ),
                              ),
                            )
                          else
                            run.value.attempt.flatMap {
                              case Right(Right(value)) => IO.pure(Right(value))
                              case Right(Left(error))  =>
                                ready.set(false).as(Left(error))
                              case Left(_) =>
                                ready
                                  .set(false)
                                  .as(
                                    Left(
                                      V2RuntimeFailure.at(
                                        RuntimeFailureCode.StorageUnknown,
                                        "original issuance outcome is unknown",
                                      ),
                                    ),
                                  )
                            },
                        ),
                      ),
                    ),
                  )
                  private def retained(
                      raw: Bytes,
                      id: Text,
                      key: Bytes,
                  ): Result[
                    IO,
                    (HistoricalIssuanceSafety.Verified, HistoricalIssuedClaim),
                  ] = for
                    enabled <- EitherT.liftF(ready.get)
                    _       <- check(enabled, "original issuance is not ready")
                    request <- core(HistoricalIssuanceRequest.codec.decode(raw))
                    _       <- check(
                      id == policy.signerId && key == policy.publicKey,
                      "original issuance signer/key differs from birth policy",
                    )
                    state <- current
                    claim <- EitherT.fromOption[IO](
                      state.claims.find(_.request == request),
                      V2RuntimeFailure.at(
                        RuntimeFailureCode.EvidenceMissing,
                        "no original forced issuance row for key use",
                      ),
                    )
                  yield state -> claim
                  private def append(
                      before: HistoricalIssuanceSafety.Verified,
                      raw: Bytes,
                      proof: Option[Bytes],
                  ): Result[IO, Unit] = for
                    record <- core(
                      HistoricalIssuanceRecord.codec.encode(
                        HistoricalIssuanceRecord(
                          1L,
                          before.sequence + 1L,
                          before.digest,
                          before.policyDigest,
                          raw,
                          proof,
                        ),
                      ),
                    )
                    _ <- HistoricalIssuanceSafety.verify(
                      before.archive
                        .copy(records = before.archive.records :+ record),
                      authentication,
                    )
                    _ <- log.append(record)
                  yield ()
                  private def prepare(
                      request: HistoricalIssuanceRequest,
                  ): Result[IO, Bytes] = for
                    raw <- core(HistoricalIssuanceRequest.codec.encode(request))
                    before    <- current
                    finalized <- authentication.currentFinalizedHeight(policy)
                    _         <- check(
                      finalized.toBigNat.toBigInt < request.subject.lastInclusionHeight.toBigNat.toBigInt,
                      "original key use has no future inclusive admission height",
                    )
                    existing = before.claims.find(
                      _.request.subject.executionId == request.subject.executionId,
                    )
                    _ <- existing.fold(append(before, raw, None))(claim =>
                      check(
                        claim.request == request && claim.terminalEvidence.isEmpty,
                        "original immutable claim changed or is terminal",
                      ),
                    )
                  yield raw
                  def beforeLock(
                      request: HistoricalIssuanceRequest,
                  ): Result[IO, Bytes] = under(false)(prepare(request))
                  def issue(
                      request: HistoricalIssuanceRequest,
                  ): Result[IO, ControllerSignature] = EitherT.leftT(
                    V2RuntimeFailure.at(
                      RuntimeFailureCode.UnsafeBoundary,
                      "original issuer must be bound to the actual lifecycle/controller",
                    ),
                  )
                  private[v2] def issueWith(
                      controller: FenceController,
                      request: HistoricalIssuanceRequest,
                  ): Result[IO, ControllerSignature] = under(false)(for
                    _ <- check(
                      controller.signerId == policy.signerId && controller.publicKey == policy.publicKey,
                      "issuer controller differs from original policy",
                    )
                    raw       <- prepare(request)
                    state     <- current
                    signature <- EitherT(
                      (permission.set(
                        Some(Permission(raw, state.archive.records)),
                      ) *> controller.sign(raw).value)
                        .guarantee(permission.set(None)),
                    )
                  yield signature)
                  def authenticateLock(raw: Bytes, id: Text, key: Bytes)
                      : Result[IO, ControllerSigningMaterial] =
                    retained(raw, id, key).map((_, claim) =>
                      ControllerSigningMaterial(
                        policy.context,
                        ControllerSigningKind.ApplicationLock,
                        None,
                        HistoricalIssuanceRequest.signingPreimage(claim.request),
                      ),
                    )
                  def authorizeLock(raw: Bytes, id: Text, key: Bytes)
                      : Result[IO, Unit] = for
                    pair      <- retained(raw, id, key)
                    active    <- EitherT.liftF(permission.get)
                    finalized <- authentication.currentFinalizedHeight(policy)
                    _         <- check(
                      active.contains(
                        Permission(raw, pair._1.archive.records),
                      ) && pair._2.terminalEvidence.isEmpty &&
                        finalized.toBigNat.toBigInt < pair._2.request.subject.lastInclusionHeight.toBigNat.toBigInt,
                      "fresh original key use lacks the exact active issuance lease/live deadline",
                    )
                  yield ()
                  def expire(raw: Bytes, proof: Bytes): Result[IO, Unit] =
                    under(false)(for
                      before  <- current
                      request <- core(
                        HistoricalIssuanceRequest.codec.decode(raw),
                      )
                      existing <- EitherT.fromOption[IO](
                        before.claims.find(_.request == request),
                        V2RuntimeFailure.at(
                          RuntimeFailureCode.EvidenceMissing,
                          "expiry has no original claim",
                        ),
                      )
                      _ <- existing.terminalEvidence.fold(
                        append(before, raw, Some(proof)),
                      )(value =>
                        check(value == proof, "terminal original proof changed"),
                      )
                    yield ())
                  def recover: Result[IO, HistoricalIssuanceSafety.Verified] =
                    under(true)(
                      current.flatTap(_ => EitherT.liftF(ready.set(true))),
                    )
                  def archive: Result[IO, HistoricalIssuanceArchive] =
                    under(false)(current.map(_.archive))
                Resource.eval(required(store.recover)).as(store)
              },
          ),
      )
