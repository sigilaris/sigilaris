package org.sigilaris.node.jvm.runtime.application.v2

import cats.Monad
import cats.data.EitherT
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.crypto.{CryptoOps, PublicKey, Signature}
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  ValidatorId,
  ValidatorMember,
  ValidatorSet,
}

type VerifiedBootstrapSource =
  TransitionEvidenceVerifier.VerifiedBootstrapSource
type VerifiedNeverEnabled      = TransitionEvidenceVerifier.VerifiedNeverEnabled
type VerifiedHistoricalAbsence =
  TransitionEvidenceVerifier.VerifiedHistoricalAbsence
type VerifiedDrain     = TransitionEvidenceVerifier.VerifiedDrain
type VerifiedHandover  = TransitionEvidenceVerifier.VerifiedHandover
type VerifiedBootstrap = TransitionEvidenceVerifier.VerifiedBootstrap
type VerifiedRestore   = TransitionEvidenceVerifier.VerifiedRestore

trait SourceSnapshotVerifier[F[_]]:
  def verify(bundle: SignedBootstrapBundle): Result[F, VerifiedBootstrapSource]
trait TransitionEvidenceVerifier[F[_]]:
  def verifyNeverEnabled(
      record: SignedNeverEnabled,
  ): Result[F, VerifiedNeverEnabled]
  def verifyAbsence(
      record: SignedArchiveAbsence,
      baseline: EvidenceBaseline,
  ): Result[F, VerifiedHistoricalAbsence]
  def verifyDrain(evidence: DrainEvidence): Result[F, VerifiedDrain]
  def verifyHandover(evidence: HandoverEvidence): Result[F, VerifiedHandover]
  def verifyBootstrap(
      bundle: SignedBootstrapBundle,
  ): Result[F, VerifiedBootstrap]
  def verifyRestore(evidence: RestoreEvidence): Result[F, VerifiedRestore]

/** Canonical inventory commitment shared with the installer. Values describe
  * original content; a digest never creates an absent namespace or watermark.
  */
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object TransitionInventory:
  import V2Codecs.given
  private final case class Record(format: Long, entries: Vector[InventoryEntry])
  private given ByteEncoder[InventoryEntry] = ByteEncoder.derived
  private given ByteDecoder[InventoryEntry] = ByteDecoder.derived
  private given ByteEncoder[Record]         = ByteEncoder.derived
  private given ByteDecoder[Record]         = ByteDecoder.derived
  private val codec = CanonicalCodec.derived[Record](value =>
    for
      _ <- V2Validation.format(value.format, 1L, "transitionInventory.format")
      _ <- value.entries.traverse_(entry =>
        V2Validation
          .identifier(entry.namespace, "inventory.namespace")
          .flatMap(_ => V2Validation.identifier(entry.key, "inventory.key")),
      )
      keys = value.entries.map(v =>
        (V2Validation.textKey(v.namespace), V2Validation.textKey(v.key)),
      )
      _ <- V2Validation.require(
        keys == keys.sorted && keys.distinct.sizeCompare(keys.size) == 0,
        FailureCode.MembershipMismatch,
        "inventory.entries",
      )
    yield (),
  )
  def encode(entries: Vector[InventoryEntry]): Either[CoreFailure, Bytes] =
    codec.encode(Record(1L, entries))
  def decode(bytes: Bytes): Either[CoreFailure, Vector[InventoryEntry]] =
    codec.decode(bytes).map(_.entries)
  def digest(entries: Vector[InventoryEntry]): Either[CoreFailure, Hash] =
    encode(entries).map(
      Commitment.hash(Utf8("sigilaris.application.transition.inventory.v1"), _),
    )

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object TransitionFenceInventory:
  import V2Codecs.given
  private final case class Record(
      format: Long,
      promises: Vector[SignedFencePromise],
  )
  private given ByteEncoder[Record] = ByteEncoder.derived
  private given ByteDecoder[Record] = ByteDecoder.derived
  private val codec                 = CanonicalCodec.derived[Record](value =>
    for
      _    <- V2Validation.format(value.format, 1L, "fenceInventory.format")
      _    <- value.promises.traverse_(SignedFencePromise.validate)
      keys <- value.promises.traverse(p =>
        DomainContext.codec
          .encode(p.record.context)
          .map(bytes =>
            (
              bytes.toHex,
              V2Validation.textKey(p.record.signerId),
              p.record.scope.tag,
            ),
          ),
      )
      _ <- V2Validation.require(
        keys == keys.sorted && keys.distinct.sizeCompare(keys.size) == 0,
        FailureCode.MembershipMismatch,
        "fenceInventory.promises",
      )
    yield (),
  )
  def encode(promises: Vector[SignedFencePromise]): Either[CoreFailure, Bytes] =
    codec.encode(Record(1L, promises))
  def decode(bytes: Bytes): Either[CoreFailure, Vector[SignedFencePromise]] =
    codec.decode(bytes).map(_.promises)
  def digest(promises: Vector[SignedFencePromise]): Either[CoreFailure, Hash] =
    encode(promises).map(
      Commitment.hash(Utf8("sigilaris.application.fence-inventory.v1"), _),
    )

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object TransitionEvidenceVerifier:
  final class VerifiedBootstrapSource private[TransitionEvidenceVerifier] (
      val bundle: SignedBootstrapBundle,
      val closure: SourceSnapshotClosure,
      val bundleDigest: Hash,
  )
  final class VerifiedNeverEnabled private[TransitionEvidenceVerifier] (
      val record: SignedNeverEnabled,
      val audit: NeverEnabledAudit,
      val digest: Hash,
  )
  final class VerifiedHistoricalAbsence private[TransitionEvidenceVerifier] (
      val record: SignedArchiveAbsence,
      val baseline: EvidenceBaseline,
      val digest: Hash,
  )
  final class VerifiedDrain private[TransitionEvidenceVerifier] (
      val evidence: DrainEvidence,
      val audit: DrainAudit,
      val digest: Hash,
  )
  final class VerifiedHandover private[TransitionEvidenceVerifier] (
      val evidence: HandoverEvidence,
      val continuation: ContinuationAudit,
      val drain: VerifiedDrain,
      val digest: Hash,
  )
  final class VerifiedBootstrap private[TransitionEvidenceVerifier] (
      val source: VerifiedBootstrapSource,
      val baseline: EvidenceBaseline,
      val drains: Vector[VerifiedDrain],
      val digest: Hash,
  )
  final class VerifiedRestore private[TransitionEvidenceVerifier] (
      val evidence: RestoreEvidence,
      val audit: RestoreAudit,
      val digest: Hash,
  )

  /** The returned verifier is bound to one immutable local policy/baseline.
    * Capabilities preserve the exact original evidence for durable retention
    * and repeat verification. They authorize no write or signature by
    * themselves.
    */
  def authenticated[F[_]: Monad](
      policy: TransitionPolicy,
      evidence: TransitionEvidenceRepository[F],
      authentication: TransitionAuditAuthentication[F],
  ): Either[V2RuntimeFailure, TransitionEvidenceVerifier[
    F,
  ] & SourceSnapshotVerifier[F]] =
    for
      _      <- RuntimeCheck.core(TransitionIntent.validate(policy.intent))
      target <- RuntimeCheck.core(
        ProtocolManifest.context(policy.targetManifest),
      )
      intentDigest <- RuntimeCheck.core(TransitionIntent.digest(policy.intent))
      _ <- RuntimeCheck.core(EvidenceBaseline.validate(policy.baseline))
      _ <- RuntimeCheck.require(
        target.chainId == policy.intent.targetChain && target.configurationDigest == policy.intent.targetManifestDigest && target.validatorSetHash == policy.intent.targetValidatorSetHash && policy.baseline.transitionIntentDigest == intentDigest && policy.baseline.sourceDomain == policy.intent.sourceDomain && policy.baseline.targetChain == policy.intent.targetChain && policy.baseline.authorityPolicyDigest == policy.intent.authorityPolicyDigest,
        RuntimeFailureCode.EvidenceContradictory,
        "transition policy, manifest and fixed baseline differ",
      )
      _ <- RuntimeCheck.core(
        V2Validation.sortedUnique(
          policy.sourceAndRetiredDomains.map(V2Validation.textKey),
          "policy.domains",
        ),
      )
      _ <- RuntimeCheck.require(
        policy.sourceAndRetiredDomains.contains(
          policy.intent.sourceDomain,
        ) && policy.authorities.nonEmpty && policy.authorities
          .map(_.id)
          .distinct
          .sizeCompare(
            policy.authorities.size,
          ) == 0 && policy.applicationDomains.distinct.sizeCompare(
          policy.applicationDomains.size,
        ) == 0 && policy.applicationDomains.forall(v =>
          policy.sourceAndRetiredDomains.contains(v.chainId),
        ),
        RuntimeFailureCode.InvalidRequest,
        "incomplete or duplicate configured transition scope",
      )
      _ <- policy.authorities.traverse_(a =>
        RuntimeCheck.core(
          InitialValidator.validate(InitialValidator(a.id, a.publicKey)),
        ),
      )
      _ <- policy.applicationDomains.traverse_(v =>
        RuntimeCheck.core(DomainContext.validate(v)),
      )
    yield new Implementation(policy, evidence, authentication)

  private final class Implementation[F[_]: Monad](
      policy: TransitionPolicy,
      evidence: TransitionEvidenceRepository[F],
      authentication: TransitionAuditAuthentication[F],
  ) extends TransitionEvidenceVerifier[F]
      with SourceSnapshotVerifier[F]:
    private def core[A](value: Either[CoreFailure, A]): Result[F, A] =
      EitherT.fromEither[F](RuntimeCheck.core(value))
    private def check(value: Boolean, detail: String): Result[F, Unit] =
      EitherT.fromEither[F](
        RuntimeCheck.require(
          value,
          RuntimeFailureCode.EvidenceContradictory,
          detail,
        ),
      )
    private def unavailable[A](detail: String): Result[F, A] =
      EitherT.leftT[F, A](
        V2RuntimeFailure.at(RuntimeFailureCode.EvidenceMissing, detail),
      )
    private def required[A](value: Option[A], detail: String): Result[F, A] =
      EitherT.fromEither[F](
        value.toRight(
          V2RuntimeFailure.at(RuntimeFailureCode.EvidenceMissing, detail),
        ),
      )
    private def refs(
        values: Vector[EvidenceRef],
    ): Result[F, Vector[EvidenceArtifact]] = values.traverse(ref =>
      for
        bytes <- evidence.read(ref)
        _     <- authentication.artifact(ref, bytes)
      yield EvidenceArtifact(ref, bytes),
    )
    private def fixedBaseline: Result[F, EvidenceBaseline] =
      for
        digest <- core(EvidenceBaseline.digest(policy.baseline))
        found  <- evidence.baseline(digest)
        _      <- core(EvidenceBaseline.validate(found))
        _      <- check(
          found == policy.baseline,
          "the fixed transition baseline was lost or replaced",
        )
        _ <- refs(found.entries)
      yield found
    private def signature(
        preimage: Bytes,
        envelope: SignatureEnvelope,
        key: Bytes,
    ): Result[F, Unit] =
      for
        _ <- core(SignatureEnvelope.validate(envelope))
        _ <- check(
          envelope.publicKey == key,
          "signature key differs from the independently trusted authority",
        )
        raw = envelope.signature
        sig = Signature(
          BigInt(1, raw.take(8L).toArray).toInt,
          UInt256.unsafeFromBytesBE(raw.slice(8L, 40L)),
          UInt256.unsafeFromBytesBE(raw.drop(40L)),
        )
        recovered <- EitherT.fromEither[F](
          CryptoOps
            .recover(sig, CryptoOps.keccak256(preimage.toArray))
            .left
            .map(error =>
              V2RuntimeFailure
                .at(RuntimeFailureCode.InvalidSignature, error.msg),
            ),
        )
        _ <- check(
          recovered.toBytes == key,
          "transition evidence signature does not authenticate its exact canonical subject",
        )
      yield ()
    private def authority(
        role: TransitionAuthorityRole,
        preimage: Bytes,
        envelope: SignatureEnvelope,
    ): Result[F, Unit] =
      for
        trusted <- required(
          policy.authorities.find(v =>
            v.id == envelope.authorityId && v.roles.contains(role),
          ),
          "no local authority is configured for this evidence class",
        )
        _ <- signature(preimage, envelope, trusted.publicKey)
      yield ()
    private def fence(
        promise: SignedFencePromise,
    ): Result[F, EnforcedFenceAudit] =
      for
        _      <- core(SignedFencePromise.validate(promise))
        intent <- core(TransitionIntent.digest(policy.intent))
        _      <- check(
          promise.record.transitionBinding == intent && policy.sourceAndRetiredDomains
            .contains(promise.record.context.chainId),
          "fence does not bind the fixed intent and authorized source scope",
        )
        audit <- authentication.fence(promise, policy.intent, evidence)
        _     <- check(
          audit.promise == promise && audit.authorizedScopeDigest == policy.intent.sourceAuthorityScopeDigest && audit.enforcementInventory.nonEmpty,
          "active fence proof differs from the exact promise or source authority scope",
        )
        _ <- core(TransitionInventory.digest(audit.enforcementInventory))
        expected = audit.originalSignedHeights
          .maxByOption(_.toBigNat.toBigInt)
        _ <- check(
          expected == promise.record.greatestPreviouslySignedHeight,
          "fence omitted or changed its retained signing watermark",
        )
        _ <- check(
          promise.record.scope != FenceScope.ConsensusProfileAtOrAbove || audit.originalSignedHeights
            .forall(
              _.toBigNat.toBigInt < promise.record.boundary.toBigNat.toBigInt,
            ),
          "old subject already signed at or above the proposed boundary",
        )
        preimage <- core(FencePromise.signingPreimage(promise.record))
        _ <- signature(preimage, promise.authentication, audit.signerPublicKey)
      yield audit
    private def noLive(
        stores: Vector[SurvivingApplicationInventory],
    ): Result[F, Unit] =
      check(
        stores.forall(v =>
          v.liveLockClaims.isEmpty && v.liveReservationOwners.isEmpty && v.nonterminalExactExecutions.isEmpty,
        ),
        "surviving application state contains unresolved claims or exact executions",
      )
    private def neverEmpty(
        stores: Vector[SurvivingApplicationInventory],
    ): Result[F, Unit] =
      noLive(stores).flatMap(_ =>
        check(
          stores.forall(_.issuedVoteSubjects.isEmpty),
          "surviving issued application subjects contradict never-enabled history",
        ),
      )
    private def coverage(
        actual: Vector[ScopedInterval],
        scope: Vector[ScopedInterval],
    ): Result[F, Unit] =
      val validScope = scope.forall(v => v.start < v.end) && scope.distinct
        .sizeCompare(scope.size) == 0 && scope
        .groupBy(_.id)
        .values
        .forall(v =>
          v.sortBy(_.start).zip(v.sortBy(_.start).drop(1)).forall {
            case (a, b) => a.end <= b.start
          },
        )
      val contained = actual.forall(v =>
        scope.exists(s => v.id == s.id && v.start >= s.start && v.end <= s.end),
      )
      val covered = scope.forall(s =>
        val rows = actual
          .filter(v => v.id == s.id && v.start >= s.start && v.end <= s.end)
          .sortBy(_.start)
        rows.headOption.exists(_.start == s.start) && rows.lastOption.exists(
          _.end == s.end,
        ) && rows.zip(rows.drop(1)).forall { case (a, b) => a.end == b.start },
      )
      check(
        validScope && contained && covered,
        "deployment or authorized signer lifetime has a gap, overlap or unaccounted interval",
      )

    /** Different deployments and authorized keys may overlap. Their union must
      * cover the whole attested lifetime, independently of per-identity tiling.
      */
    private def lifetimeCoverage(
        scope: Vector[ScopedInterval],
        start: Long,
        end: Long,
    ): Result[F, Unit] =
      val covered =
        scope.sortBy(_.start).foldLeft(Option(start)) { (through, interval) =>
          through
            .filter(value =>
              interval.start >= start && interval.start <= value &&
                interval.start < interval.end && interval.end <= end,
            )
            .map(value => math.max(value, interval.end))
        }
      check(
        scope.nonEmpty && covered.contains(end),
        "authenticated audit scope does not cover the complete attested lifetime",
      )

    def verifyNeverEnabled(
        record: SignedNeverEnabled,
    ): Result[F, VerifiedNeverEnabled] =
      for
        _ <- core(SignedNeverEnabled.validate(record))
        _ <- check(
          policy.sourceAndRetiredDomains.contains(record.record.domain),
          "never-enabled evidence is outside the configured source/retired scope",
        )
        preimage <- core(NeverEnabled.signingPreimage(record.record))
        _        <- authority(
          TransitionAuthorityRole.NeverEnabled,
          preimage,
          record.authentication,
        )
        _     <- refs(record.record.supportingEvidence)
        audit <- authentication.neverEnabled(record.record, evidence)
        r = record.record
        _ <- check(
          audit.domain == r.domain && audit.lifetimeStart == r.lifetimeStart && audit.lifetimeEnd == r.lifetimeEnd && audit.authorizedSignerInventory == r.authorizedSignerInventory,
          "never-enabled evidence differs from the independently authenticated lifetime inventory",
        )
        _ <- check(
          audit.authorizedSigners.forall(v =>
            v.start >= r.lifetimeStart && v.end <= r.lifetimeEnd,
          ) && audit.deployments.forall(v =>
            v.start >= r.lifetimeStart && v.end <= r.lifetimeEnd,
          ),
          "authorized roster lies outside the attested lifetime",
        )
        _ <- lifetimeCoverage(audit.deployments, r.lifetimeStart, r.lifetimeEnd)
        _ <- lifetimeCoverage(
          audit.authorizedSigners,
          r.lifetimeStart,
          r.lifetimeEnd,
        )
        _ <- coverage(
          r.deployments.map(v =>
            ScopedInterval(v.deploymentId, v.start, v.end),
          ),
          audit.deployments,
        )
        _ <- coverage(
          r.keyUses.map(v => ScopedInterval(v.signerKeyId, v.start, v.end)),
          audit.authorizedSigners,
        )
        disabled = Set(
          IssuanceCapability.Unavailable,
          IssuanceCapability.ContinuouslyDisabled,
        )
        _ <- check(
          r.deployments.forall(v =>
            disabled.contains(v.lockIssuance) && disabled.contains(
              v.effectIssuance,
            ),
          ) && r.keyUses.forall(
            _.usePaths.forall(v =>
              disabled.contains(v.lockIssuance) && disabled.contains(
                v.effectIssuance,
              ),
            ),
          ),
          "enabled or unknown issuance paths cannot establish never-enabled voting",
        )
        _ <- r.deployments.traverse_(d =>
          d.signerKeyIds.traverse_(id =>
            coverage(
              r.keyUses
                .filter(k =>
                  k.signerKeyId == id && k.start < d.end && k.end > d.start,
                )
                .map(k =>
                  ScopedInterval(
                    id,
                    math.max(k.start, d.start),
                    math.min(k.end, d.end),
                  ),
                ),
              Vector(ScopedInterval(id, d.start, d.end)),
            ),
          ),
        )
        _ <- check(
          audit.survivingStores.forall(_.context.chainId == r.domain),
          "never-enabled reconciliation substituted another domain's stores",
        )
        _      <- neverEmpty(audit.survivingStores)
        digest <- core(SignedNeverEnabled.digest(record))
      yield new VerifiedNeverEnabled(record, audit, digest)

    def verifyAbsence(
        record: SignedArchiveAbsence,
        baseline: EvidenceBaseline,
    ): Result[F, VerifiedHistoricalAbsence] =
      for
        fixed <- fixedBaseline
        _     <- check(
          baseline == fixed,
          "historical absence cannot replace the fixed evidence baseline",
        )
        _        <- core(SignedArchiveAbsence.validate(record))
        preimage <- core(ArchiveAbsence.signingPreimage(record.record))
        _        <- authority(
          TransitionAuthorityRole.HistoricalAbsence,
          preimage,
          record.authentication,
        )
        digest <- core(SignedArchiveAbsence.digest(record))
        _      <- check(
          fixed.entries.contains(
            EvidenceRef(EvidenceKind.HistoricalAbsence, digest),
          ) && record.record.targetChain == policy.intent.targetChain && policy.sourceAndRetiredDomains
            .contains(record.record.retiredDomain),
          "absence attestation is not the original baseline entry",
        )
        _ <- refs(record.record.survivingEvidence)
        _ <- refs(
          Vector(
            EvidenceRef(
              EvidenceKind.Rollback,
              record.record.rollbackDecisionDigest,
            ),
            EvidenceRef(
              EvidenceKind.NonAncestry,
              record.record.nonAncestryProofDigest,
            ),
          ),
        )
        neverBytes <- evidence.read(
          EvidenceRef(
            EvidenceKind.NeverEnabled,
            record.record.neverEnabledDigest,
          ),
        )
        neverRecord <- core(SignedNeverEnabled.codec.decode(neverBytes))
        never       <- verifyNeverEnabled(neverRecord)
        _           <- check(
          never.digest == record.record.neverEnabledDigest && never.record.record.domain == record.record.retiredDomain,
          "absence does not bind independent never-enabled history for the affected domain",
        )
        audit          <- authentication.absence(record.record, fixed, evidence)
        baselineDigest <- core(EvidenceBaseline.digest(fixed))
        _              <- check(
          audit.retiredDomain == record.record.retiredDomain && audit.sourceBinding == record.record.sourceBinding && audit.targetChain == record.record.targetChain && audit.missingScopes == record.record.missingScopes && audit.baselineDigest == baselineDigest && audit.independentlyEstablishedBeforeBaseline == record.record.missingScopes,
          "missing scope was not independently established before this fixed baseline",
        )
        _ <- neverEmpty(audit.survivingStores)
      yield new VerifiedHistoricalAbsence(record, fixed, digest)

    def verifyDrain(record: DrainEvidence): Result[F, VerifiedDrain] =
      for
        _      <- core(DrainEvidence.validate(record))
        intent <- core(TransitionIntent.digest(policy.intent))
        _      <- check(
          record.transitionIntentDigest == intent && policy.applicationDomains
            .contains(record.domain),
          "drain evidence is outside the exact configured application domain",
        )
        _ <- refs(
          Vector(
            EvidenceRef(
              if record.kind == DrainKind.NeverEnabled then
                EvidenceKind.NeverEnabled
              else EvidenceKind.Drain,
              record.coverageProofDigest,
            ),
          ),
        )
        audit <- authentication.drain(record, evidence)
        _     <- check(
          audit.context == record.domain && audit.reconciledInventoryDigest == record.reconciledInventoryDigest && audit.greatestRecordedDeadline == record.greatestRecordedDeadline && audit.authenticatedBaseUpperBound == record.baseUpperBound && audit.oldMaximumLifetime == record.oldMaximumLifetime && audit.zeroLiveEvidenceDigest == record.zeroLiveEvidenceDigest,
          "drain summary differs from its original coverage and reconciliation proofs",
        )
        fd <- core(SignedFencePromise.digest(audit.fence))
        _  <- check(
          fd == record.fenceDigest && audit.fence.record.context == record.domain && audit.fence.record.scope != FenceScope.ConsensusProfileAtOrAbove,
          "application issuance drain has no matching enforceable domain fence",
        )
        _ <- fence(audit.fence)
        _ <- noLive(audit.survivingStores)
        _ <- check(
          audit.survivingStores.forall(_.context == record.domain),
          "new-chain state cannot discharge original-domain claims",
        )
        _ <- record.kind match
          case DrainKind.NeverEnabled =>
            for
              bytes <- evidence.read(
                EvidenceRef(
                  EvidenceKind.NeverEnabled,
                  record.coverageProofDigest,
                ),
              )
              neverRecord <- core(SignedNeverEnabled.codec.decode(bytes))
              never       <- verifyNeverEnabled(neverRecord)
              _           <- check(
                never.digest == record.coverageProofDigest && never.record.record.domain == record.domain.chainId && audit.coveredSubjects.isEmpty && audit.expectedIssuedSubjectIds.isEmpty && audit.finality.isEmpty,
                "never-enabled drain manufactured issuance history or finality",
              )
              _ <- neverEmpty(audit.survivingStores)
            yield ()
          case kind =>
            for
              _ <- check(
                audit.coveredSubjects
                  .map(_.subjectDigest)
                  .distinct
                  .sizeCompare(
                    audit.coveredSubjects.size,
                  ) == 0 && audit.expectedIssuedSubjectIds.distinct
                  .sizeCompare(audit.expectedIssuedSubjectIds.size) == 0,
                "duplicate issuance subjects in drain coverage",
              )
              _ <- check(
                kind != DrainKind.JournalCovered || audit.expectedIssuedSubjectIds.toSet == audit.coveredSubjects
                  .map(_.subjectDigest)
                  .toSet,
                "journal coverage omitted still-certifiable subjects",
              )
              _ <- audit.coveredSubjects.traverse_(v =>
                check(
                  v.context == record.domain && v.canonicalSubject.nonEmpty && v.ownDurableRecord.nonEmpty && v.recordSequence > 0L && v.recordSequence <= v.issuanceSequence && v.exactPlan
                    .forall(p =>
                      p.context == v.context && p.executionId == v.executionId && p.deadline == v.deadline && p.canonicalRecord.nonEmpty,
                    ),
                  "subject lacks its own pre-issuance durable deadline record or matching exact plan",
                ),
              )
              greatest = audit.coveredSubjects
                .map(_.deadline)
                .maxByOption(_.toBigNat.toBigInt)
              _ <- check(
                greatest.forall(v =>
                  record.greatestRecordedDeadline.exists(
                    _.toBigNat.toBigInt >= v.toBigNat.toBigInt,
                  ),
                ),
                "recorded drain horizon omitted a retained subject deadline",
              )
              _ <- check(
                kind != DrainKind.JournalCovered || greatest == record.greatestRecordedDeadline,
                "complete journal horizon differs from its actual subjects",
              )
              finalized <- required(
                audit.finality,
                "original-domain finality is missing",
              )
              _ <- check(
                finalized.context == record.domain && finalized.originalProof.nonEmpty && record.finalizedEvidenceDigest
                  .contains(finalized.evidenceDigest),
                "finality has wrong domain or proof identity",
              )
              inferred = record.baseUpperBound.map(
                _.toBigNat.toBigInt + BigInt(record.oldMaximumLifetime),
              )
              horizons = record.greatestRecordedDeadline
                .map(_.toBigNat.toBigInt)
                .toList ++ inferred.toList
              _ <- check(
                horizons.forall(finalized.height.toBigNat.toBigInt > _),
                "original-domain finality is not strictly above every deadline horizon",
              )
            yield ()
        digest <- core(DrainEvidence.digest(record))
      yield new VerifiedDrain(record, audit, digest)

    private def allDrains(
        baseline: EvidenceBaseline,
    ): Result[F, Vector[VerifiedDrain]] =
      for
        found <- baseline.entries
          .filter(_.kind == EvidenceKind.Drain)
          .traverse(ref =>
            for
              bytes    <- evidence.read(ref)
              record   <- core(DrainEvidence.codec.decode(bytes))
              verified <- verifyDrain(record)
              _        <- check(
                verified.digest == ref.digest,
                "baseline drain reference differs from original canonical evidence",
              )
            yield verified,
          )
        _ <- check(
          found
            .map(_.evidence.domain)
            .distinct
            .sizeCompare(found.size) == 0 && found
            .map(_.evidence.domain)
            .toSet == policy.applicationDomains.toSet,
          "not every configured source/retired application domain supplied its own drain route",
        )
      yield found

    private def validatorSet(
        values: Vector[InitialValidator],
    ): Result[F, ValidatorSet] =
      for
        _       <- values.traverse_(v => core(InitialValidator.validate(v)))
        members <- values.traverse(v =>
          for
            id <- EitherT.fromEither[F](
              ValidatorId
                .parse(v.validatorId.asString)
                .left
                .map(error =>
                  V2RuntimeFailure.at(RuntimeFailureCode.InvalidRequest, error),
                ),
            )
            key <- EitherT.fromEither[F](
              PublicKey
                .fromByteArray(v.publicKey.toArray)
                .left
                .map(error =>
                  V2RuntimeFailure
                    .at(RuntimeFailureCode.InvalidRequest, error.msg),
                ),
            )
          yield ValidatorMember(id, key),
        )
        set <- EitherT.fromEither[F](
          ValidatorSet(members).left.map(error =>
            V2RuntimeFailure
              .at(RuntimeFailureCode.InvalidRequest, error.message),
          ),
        )
      yield set

    def verify(
        bundle: SignedBootstrapBundle,
    ): Result[F, VerifiedBootstrapSource] =
      for
        _ <- core(SignedBootstrapBundle.validate(bundle))
        _ <- check(
          bundle.bundle.transitionIntent == policy.intent && policy.intent.kind == TransitionKind.InitialBootstrap && !policy.sourceAndRetiredDomains
            .contains(bundle.bundle.target.chainId),
          "bootstrap bundle changed the fixed intent or reused a source/retired signing domain",
        )
        preimage <- core(BootstrapBundle.signingPreimage(bundle.bundle))
        _        <- authority(
          TransitionAuthorityRole.Bootstrap,
          preimage,
          bundle.authentication,
        )
        expected <- core(ProtocolManifest.context(policy.targetManifest))
        _        <- check(
          bundle.bundle.target == expected,
          "bootstrap target differs from its installed manifest",
        )
        set <- validatorSet(bundle.bundle.validators)
        _   <- check(
          set.hash.toUInt256 == bundle.bundle.target.validatorSetHash,
          "bootstrap validator ordering does not match the actual historical set hash",
        )
        closure <- authentication.source(bundle.bundle.source, evidence)
        _       <- check(
          closure.source == bundle.bundle.source && closure.sourceAuthorityScopeDigest == policy.intent.sourceAuthorityScopeDigest && closure.ancestry == bundle.bundle.ancestryKind,
          "source proof changed the selected schema/root/provenance/ancestry",
        )
        inventory <- core(TransitionInventory.digest(closure.dataInventory))
        _         <- check(
          inventory == bundle.bundle.source.dataInventoryDigest,
          "inherited source data inventory is incomplete or changed",
        )
        _ <- core(
          V2Validation.sortedUnique(
            closure.retiredDomains.map(V2Validation.textKey),
            "source.retiredDomains",
          ),
        )
        _ <- check(
          (closure.retiredDomains :+ closure.source.domain).toSet == policy.sourceAndRetiredDomains.toSet && !closure.retiredDomains
            .contains(
              closure.source.domain,
            ) && ((closure.ancestry == SourceAncestry.NoPriorDomain) == closure.retiredDomains.isEmpty),
          "source proof omitted a retired domain or contradicted no-prior-domain ancestry",
        )
        fd <- core(SignedFencePromise.digest(closure.sourceFence))
        _  <- check(
          fd == bundle.bundle.sourceWriteFenceDigest && closure.sourceFence.record.scope == FenceScope.SourceOrRetiredDomainWritesAndSigning && closure.sourceFence.record.context.chainId == closure.source.domain,
          "snapshot was not selected under its enforceable source write fence",
        )
        _      <- fence(closure.sourceFence)
        digest <- core(SignedBootstrapBundle.digest(bundle))
      yield new VerifiedBootstrapSource(bundle, closure, digest)

    private final case class RetiredPreservation(
        domain: Text,
        kind: EvidenceKind,
        scopes: Vector[Text],
    )

    private def retired(
        ref: EvidenceRef,
        source: VerifiedBootstrapSource,
        baseline: EvidenceBaseline,
    ): Result[F, RetiredPreservation] =
      for
        _ <- check(
          baseline.entries.contains(ref),
          "retired evidence was not retained in the fixed baseline",
        )
        bytes        <- evidence.read(ref)
        sourceDigest <- core(SourceBinding.digest(source.closure.source))
        domain       <- ref.kind match
          case EvidenceKind.PresentContent =>
            for
              archive <- core(PresentArchive.codec.decode(bytes))
              digest  <- core(PresentArchive.digest(archive))
              _       <- check(
                digest == ref.digest && archive.sourceBinding == sourceDigest && archive.targetChain == policy.intent.targetChain,
                "present archive differs from the fixed source/target binding",
              )
              audit  <- authentication.presentArchive(archive, evidence)
              actual <- core(TransitionInventory.digest(audit.contents))
              _      <- check(
                audit.archive == archive && actual == archive.inventoryDigest,
                "present archive lost or changed original content",
              )
              fd <- core(SignedFencePromise.digest(audit.retirementFence))
              _  <- check(
                fd == archive.retirementFenceDigest && audit.retirementFence.record.context.chainId == archive.retiredDomain && audit.retirementFence.record.scope == FenceScope.SourceOrRetiredDomainWritesAndSigning,
                "retired archive has no matching write/signing fence",
              )
              _ <- core(
                V2Validation.sortedUnique(
                  audit.presentScopes.map(V2Validation.textKey),
                  "presentArchive.presentScopes",
                ),
              )
              _ <- check(
                audit.presentScopes.nonEmpty && audit.presentScopes.forall(
                  _.asString.nonEmpty,
                ),
                "present archive has no authenticated original scope inventory",
              )
              _ <- fence(audit.retirementFence)
            yield RetiredPreservation(
              archive.retiredDomain,
              ref.kind,
              audit.presentScopes,
            )
          case EvidenceKind.HistoricalAbsence =>
            for
              absence  <- core(SignedArchiveAbsence.codec.decode(bytes))
              verified <- verifyAbsence(absence, baseline)
              _        <- check(
                verified.digest == ref.digest && absence.record.sourceBinding == sourceDigest,
                "historical absence binds another selected source",
              )
              fenceRefs = baseline.entries.filter(_.kind == EvidenceKind.Fence)
              promises <- fenceRefs.traverse(value =>
                for
                  raw     <- evidence.read(value)
                  promise <- core(SignedFencePromise.codec.decode(raw))
                  digest  <- core(SignedFencePromise.digest(promise))
                  _       <- check(
                    digest == value.digest,
                    "baseline retirement fence content changed",
                  )
                yield promise,
              )
              requiredFence <- required(
                promises.find(p =>
                  p.record.context.chainId == absence.record.retiredDomain && p.record.scope == FenceScope.SourceOrRetiredDomainWritesAndSigning,
                ),
                "historically absent domain has no enforceable retirement fence",
              )
              _ <- fence(requiredFence)
            yield RetiredPreservation(
              absence.record.retiredDomain,
              ref.kind,
              absence.record.missingScopes,
            )
          case _ =>
            unavailable[RetiredPreservation](
              "bootstrap retired evidence must be original present content or accepted historical absence",
            )
      yield domain

    def verifyBootstrap(
        bundle: SignedBootstrapBundle,
    ): Result[F, VerifiedBootstrap] =
      for
        source   <- verify(bundle)
        baseline <- fixedBaseline
        digest   <- core(EvidenceBaseline.digest(baseline))
        _        <- check(
          bundle.bundle.evidenceBaselineDigest == digest && baseline.sourceCheckpoint == source.closure.source.checkpointId && baseline.sourceStateRoot == source.closure.source.stateRoot,
          "bootstrap changed the fixed evidence baseline or selected source state",
        )
        retiredDomains <- bundle.bundle.retiredEvidence.traverse(ref =>
          retired(ref, source, baseline),
        )
        _ <- check(
          retiredDomains
            .map(value => (value.domain, value.kind))
            .distinct
            .sizeCompare(
              retiredDomains.size,
            ) == 0 && retiredDomains
            .map(_.domain)
            .toSet == source.closure.retiredDomains.toSet &&
            retiredDomains.groupBy(_.domain).values.forall { records =>
              val scopes = records.flatMap(_.scopes)
              scopes.distinct.sizeCompare(scopes.size) == 0
            },
          "bootstrap omitted, duplicated or substituted retired-domain preservation evidence",
        )
        drains <- allDrains(baseline)
      yield new VerifiedBootstrap(source, baseline, drains, source.bundleDigest)

    def verifyHandover(record: HandoverEvidence): Result[F, VerifiedHandover] =
      for
        _ <- core(HandoverEvidence.validate(record))
        _ <- check(
          record.transitionIntent == policy.intent && policy.intent.kind == TransitionKind.Handover,
          "handover changed its already-promised future-boundary intent",
        )
        expected <- core(ProtocolManifest.context(policy.targetManifest))
        _        <- check(
          record.target == expected,
          "handover target differs from its manifest",
        )
        baseline       <- fixedBaseline
        baselineDigest <- core(EvidenceBaseline.digest(baseline))
        _              <- check(
          record.evidenceBaselineDigest == baselineDigest && baseline.sourceCheckpoint == record.drainCheckpointId && baseline.sourceStateRoot == record.drainStateRoot,
          "handover baseline changed its drain checkpoint",
        )
        drains <- allDrains(baseline)
        drain  <- required(
          drains.find(v =>
            v.digest == record.drainEvidenceDigest && v.evidence.domain == record.source,
          ),
          "handover has no source-domain drain evidence",
        )
        _ <- check(
          drain.audit.finality.forall(v =>
            v.checkpointId == record.drainCheckpointId && v.height == record.drainCheckpointHeight && v.stateRoot == record.drainStateRoot,
          ),
          "drain finality differs from handover checkpoint",
        )
        continuation <- authentication.continuation(record, evidence)
        _            <- check(
          continuation.source == record.source && continuation.target == record.target && continuation.drainCheckpointId == record.drainCheckpointId && continuation.drainCheckpointHeight == record.drainCheckpointHeight && continuation.drainStateRoot == record.drainStateRoot && continuation.parentId == record.continuationParentId && continuation.parentStateRoot == record.continuationParentRoot && continuation.parentHeight.toBigNat.toBigInt + 1 == record.boundaryHeight.toBigNat.toBigInt && continuation.retainedSuffix == record.retainedSuffix && continuation.retainedSafety == record.safety,
          "continuation proof changed parent, suffix replay state or retained consensus safety",
        )
        _ <- check(
          continuation.allOldSignedHeights.forall(
            _.toBigNat.toBigInt < record.boundaryHeight.toBigNat.toBigInt,
          ),
          "future boundary would relabel a previously signed old-profile subject",
        )
        sets = continuation.historicalFenceSets
        _ <- check(
          sets.nonEmpty && sets
            .map(_.context)
            .distinct
            .sizeCompare(sets.size) == 0,
          "unknown or duplicated still-certifiable historical validator set",
        )
        _ <- record.fencePromises.traverse_(p => fence(p).void)
        _ <- sets.traverse_(historical =>
          for
            set <- validatorSet(historical.validators)
            _   <- check(
              set.hash.toUInt256 == historical.context.validatorSetHash && historical.context.chainId == record.source.chainId,
              "historical fence quorum set has an invalid signing context",
            )
            promises = record.fencePromises.filter(p =>
              p.record.context == historical.context && p.record.scope == FenceScope.ConsensusProfileAtOrAbove,
            )
            _ <- check(
              promises
                .map(_.record.signerId)
                .distinct
                .sizeCompare(promises.size) == 0 && promises.forall(
                _.record.boundary == record.boundaryHeight,
              ),
              "duplicate signer or changed boundary in old-profile fence promises",
            )
            _ <- promises.traverse_(p =>
              for
                member <- required(
                  historical.validators.find(
                    _.validatorId == p.record.signerId,
                  ),
                  "fence signer is outside the actual historical set",
                )
                preimage <- core(FencePromise.signingPreimage(p.record))
                _ <- signature(preimage, p.authentication, member.publicKey)
              yield (),
            )
            n = BigInt(historical.validators.size)
            q = BigInt(promises.size)
            f = (n - 1) / 3
            _ <- check(
              q >= (2 * n) / 3 + 1 && 2 * q > n + f,
              "enforced old-profile promises cannot intersect every still-certifiable quorum",
            )
          yield (),
        )
        _ <- check(
          record.fencePromises
            .filter(_.record.scope == FenceScope.ConsensusProfileAtOrAbove)
            .forall(p => sets.exists(_.context == p.record.context)),
          "handover includes a fence from an unaccounted historical set",
        )
        digest <- core(HandoverEvidence.digest(record))
      yield new VerifiedHandover(record, continuation, drain, digest)

    def verifyRestore(record: RestoreEvidence): Result[F, VerifiedRestore] =
      for
        _        <- core(RestoreEvidence.validate(record))
        _        <- fixedBaseline
        envelope <- evidence.restoreAuthentication(record)
        _        <- check(
          envelope.authorityId == record.authorityId,
          "restore authority differs from its original evidence",
        )
        preimage <- core(RestoreEvidence.signingPreimage(record))
        _     <- authority(TransitionAuthorityRole.Restore, preimage, envelope)
        audit <- authentication.restore(record, evidence)
        _     <- check(
          audit.sourceGroupDigest == record.sourceGroupDigest && audit.currentSafetyInventoryDigest == record.currentSafetyInventoryDigest && audit.sourceGroup.nonEmpty && audit.sourceFences.nonEmpty && audit.requiredFenceContexts.nonEmpty,
          "restore did not authenticate the complete old/current consistency group",
        )
        sourceGroup <- core(
          ConsistencyGroupRecord.codec.decode(audit.sourceGroupRecord),
        )
        sourceDigest    <- core(ConsistencyGroupRecord.digest(sourceGroup))
        sourceInventory <- core(
          ConsistencyGroupStore.originalInventory(sourceGroup),
        )
        sourceHistory <- EitherT.fromEither[F](
          ControllerHistory.recover(sourceGroup.controllerSnapshot),
        )
        _ <- check(
          sourceGroup.baseline == policy.baseline && sourceInventory == audit.sourceGroup &&
            sourceHistory.closures
              .get(sourceGroup.writeClosureDigest)
              .exists(c =>
                c.transition == policy.intent && c.context == sourceGroup.canonical.context,
              ),
          "restore original group, namespace closure or irreversible old writer fence differs",
        )
        currentDigest <- core(
          TransitionInventory.digest(audit.currentSafetyInventory),
        )
        promiseDigest <- core(
          TransitionInventory.digest(audit.preservedPromises),
        )
        canonicalDigest <- core(
          TransitionInventory.digest(audit.preservedCanonicalWrites),
        )
        _ <- core(TransitionInventory.digest(audit.currentPromises))
        _ <- core(TransitionInventory.digest(audit.currentCanonicalWrites))
        _ <- check(
          sourceDigest == record.sourceGroupDigest && currentDigest == record.currentSafetyInventoryDigest && promiseDigest == record.preservedPromiseInventoryDigest && canonicalDigest == record.preservedCanonicalWriteInventoryDigest,
          "restore preservation inventory digest changed",
        )
        _ <- check(
          audit.currentPromises.forall(
            audit.preservedPromises.contains,
          ) && audit.currentCanonicalWrites.forall(
            audit.preservedCanonicalWrites.contains,
          ),
          "restore would discard an issued promise or canonical/safety write",
        )
        target <- core(ProtocolManifest.context(policy.targetManifest))
        intent <- core(TransitionIntent.digest(policy.intent))
        _      <- check(
          audit.requiredFenceContexts.distinct.sizeCompare(
            audit.requiredFenceContexts.size,
          ) == 0 && audit.requiredFenceContexts.forall(c =>
            policy.sourceAndRetiredDomains.contains(c.chainId) || c == target,
          ) && (policy.sourceAndRetiredDomains.toSet + target.chainId)
            .subsetOf(audit.requiredFenceContexts.map(_.chainId).toSet),
          "restore omitted a source, retired or newly active target signing/writing domain",
        )
        _ <- check(
          audit.sourceFences
            .map(_.record.context)
            .toSet == audit.requiredFenceContexts.toSet,
          "restore did not fence every independently inventoried current signing/writing context",
        )
        _ <- audit.sourceFences.traverse_(p =>
          for
            _ <- core(SignedFencePromise.validate(p))
            _ <- check(
              p.record.transitionBinding == intent && p.record.scope == FenceScope.SourceOrRetiredDomainWritesAndSigning,
              "restore fence has a different transition or scope",
            )
            actual <- authentication.restoreFence(p, record, policy, evidence)
            _      <- check(
              actual.promise == p && actual.authorizedScopeDigest == currentDigest && actual.enforcementInventory.nonEmpty && actual.originalSignedHeights
                .maxByOption(
                  _.toBigNat.toBigInt,
                ) == p.record.greatestPreviouslySignedHeight,
              "restore fence omitted current key authority or issued safety history",
            )
            _ <- core(TransitionInventory.digest(actual.enforcementInventory))
            signBytes <- core(FencePromise.signingPreimage(p.record))
            _ <- signature(signBytes, p.authentication, actual.signerPublicKey)
          yield (),
        )
        fd <- core(TransitionFenceInventory.digest(audit.sourceFences))
        _  <- check(
          fd == record.fenceEvidenceDigest,
          "restore fence inventory changed",
        )
        original <- evidence.read(
          EvidenceRef(EvidenceKind.Fence, record.fenceEvidenceDigest),
        )
        retained <- core(TransitionFenceInventory.decode(original))
        _        <- check(
          retained == audit.sourceFences,
          "restore lost an original enforceable fence promise",
        )
        digest <- core(RestoreEvidence.digest(record))
      yield new VerifiedRestore(record, audit, digest)
