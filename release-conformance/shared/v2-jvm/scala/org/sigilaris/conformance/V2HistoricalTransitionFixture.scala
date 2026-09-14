package org.sigilaris.conformance

import java.nio.file.Files

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.InclusionHeight
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.given
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.application.v2.*

/** Actual four-controller, original-state transition evidence. Empty
  * application issuance is established from its original physical namespace AND
  * complete live controller history; it is not inferred from an empty later
  * lock map.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Throw",
  ),
)
object V2HistoricalTransitionFixture:
  import V2HistoricalFixture.{check, core}
  import V2RequestConformance.{height, value, unavailable}
  final case class IssuanceArchive(
      format: Long,
      context: DomainContext,
      issued: Vector[Bytes],
  )
  object IssuanceArchive:
    given ByteEncoder[IssuanceArchive] = ByteEncoder.derived
    given ByteDecoder[IssuanceArchive] = ByteDecoder.derived
    val codec = CanonicalCodec.derived[IssuanceArchive](value =>
      V2Validation
        .format(value.format, 1L, "originalIssuance.format")
        .flatMap(_ => DomainContext.validate(value.context)),
    )
  val IssuanceDomain = Utf8("neutral.historical-issuance.inventory.v1")
  final case class Transition(
      evidence: HandoverEvidence,
      verified: VerifiedHandover,
      verifier: TransitionEvidenceVerifier[IO],
      baseline: EvidenceBaseline,
      artifacts: Map[EvidenceRef, Bytes],
      original: HistoricalContinuationRepository[IO],
      proof: HistoricalContinuationProof,
      continuation: HistoricalContinuationAuthentication,
  )
  def initialize(material: V2HistoricalFixture.Material): IO[Unit] =
    V2HistoricalFixture.durable(
      material.root.resolve("original-applications"),
      value(
        IssuanceArchive.codec.encode(
          IssuanceArchive(1L, material.old, Vector.empty),
        ),
      ),
    )
  def issuance(
      material: V2HistoricalFixture.Material,
  ): Result[IO, (Bytes, Hash)] = for
    bytes <- EitherT.liftF(
      IO.blocking(
        ByteVector.view(
          Files.readAllBytes(material.root.resolve("original-applications")),
        ),
      ),
    )
    archive <- core(IssuanceArchive.codec.decode(bytes))
    _       <- check(
      archive.context == material.old,
      "actual original issuance namespace context changed",
    )
  yield bytes -> Commitment.hash(IssuanceDomain, bytes)

  def prepare(
      material: V2HistoricalFixture.Material,
      nodes: Vector[V2HistoricalControllerFixture.Node],
  ): Result[IO, Transition] = prepare(material, nodes, DrainKind.JournalCovered)

  def prepare(
      material: V2HistoricalFixture.Material,
      nodes: Vector[V2HistoricalControllerFixture.Node],
      kind: DrainKind,
  ): Result[IO, Transition] = prepare(material, nodes, kind, 3L)

  def prepare(
      material: V2HistoricalFixture.Material,
      nodes: Vector[V2HistoricalControllerFixture.Node],
      kind: DrainKind,
      finalizedHeight: Long,
  ): Result[IO, Transition] = for
    _ <- check(
      nodes.sizeCompare(4) == 0,
      "complete four-validator historical scope required",
    )
    intent = V2HistoricalControllerFixture.transition(material)
    appFences <- nodes.traverse { node =>
      for
        current <- node.controller.audit
        promise <- current.signedFences
          .find(value =>
            value.record.context == material.old && value.record.scope == FenceScope.ApplicationIssuance && value.record.boundary == height(
              6L,
            ),
          )
          .fold(
            node.controller
              .prepareFence(
                intent,
                material.old,
                FenceScope.ApplicationIssuance,
                height(6L),
              )
              .flatMap(node.controller.enforce(intent, _)),
          )(EitherT.pure[IO, V2RuntimeFailure](_))
      yield promise
    }
    oldFences <- nodes.traverse { node =>
      for
        current <- node.controller.audit
        promise <- current.signedFences
          .find(value =>
            value.record.context == material.old && value.record.scope == FenceScope.ConsensusProfileAtOrAbove && value.record.boundary == height(
              6L,
            ),
          )
          .fold(
            node.controller
              .prepareFence(
                intent,
                material.old,
                FenceScope.ConsensusProfileAtOrAbove,
                height(6L),
              )
              .flatMap(node.controller.enforce(intent, _)),
          )(EitherT.pure[IO, V2RuntimeFailure](_))
      yield promise
    }
    proposals    <- EitherT.liftF(material.retained.get)
    certificates <- EitherT.liftF(material.certificates.get)
    finality     <- material.finalized(finalizedHeight)
    drain        <- material.consensus.finalized(finality)
    f      = drain.ordered.head
    suffix = proposals.values.toVector
      .filter(p =>
        p.block.height.toBigNat.toBigInt > finalizedHeight && p.block.height.toBigNat.toBigInt <= 5,
      )
      .sortBy(_.block.height.toBigNat.toBigInt)
    _ <- nodes.traverse_ { node =>
      for
        current <- node.safety.recover
        _       <-
          if current.state.highQc == certificates(suffix.last.proposalId) then
            EitherT.pure[IO, V2RuntimeFailure](())
          else
            node.safety.observe(
              suffix.last,
              certificates(suffix.last.proposalId),
            )
        recovered <- node.safety.recover
        _         <-
          if recovered.state.fenceBoundary.contains(height(6L)) then
            EitherT.pure[IO, V2RuntimeFailure](())
          else node.safety.fence(height(6L))
      yield ()
    }
    safety <- nodes
      .traverse(_.safety.archive)
      .map(_.sortBy(row => V2Validation.textKey(row.profile.voter)))
    proof = HistoricalContinuationProof(
      1L,
      finality,
      suffix.map(p =>
        HistoricalCertifiedMaterial(p, certificates(p.proposalId)),
      ),
      safety,
    )
    proofBytes  <- core(HistoricalContinuationProof.codec.encode(proof))
    proofDigest <- core(HistoricalContinuationProof.digest(proof))
    _           <- EitherT.liftF(
      V2HistoricalFixture.durable(
        material.root.resolve("continuation-original"),
        proofBytes,
      ),
    )
    bundle <- V2HistoricalDrainFixture.prepare(
      material,
      nodes,
      intent,
      appFences,
      finality,
      kind,
    )
    drainRecord = bundle.record
    drainDigest      <- core(DrainEvidence.digest(drainRecord))
    transitionDigest <- core(TransitionIntent.digest(intent))
    fixed = EvidenceBaseline(
      1L,
      transitionDigest,
      material.old.chainId,
      material.target.chainId,
      finality.anchorBlockId.toUInt256,
      f.replay.nextStateRoot,
      intent.authorityPolicyDigest,
      Vector(EvidenceRef(EvidenceKind.Drain, drainDigest)),
    )
    baselineDigest <- core(EvidenceBaseline.digest(fixed))
    artifactMap = bundle.artifacts
    original    = new HistoricalContinuationRepository[IO]:
      def read(digest: Hash): Result[IO, Bytes] = for
        bytes <- EitherT.liftF(
          IO.blocking(
            ByteVector.view(
              Files.readAllBytes(material.root.resolve("continuation-original")),
            ),
          ),
        )
        decoded <- core(HistoricalContinuationProof.codec.decode(bytes))
        actual  <- core(HistoricalContinuationProof.digest(decoded))
        _       <- check(
          actual == digest && digest == proofDigest,
          "actual immutable old continuation bytes differ",
        )
      yield bytes
      def installedGroup(digest: Hash): Result[IO, VerifiedActiveGroup] =
        unavailable(
          "original transition evidence alone is not an atomic activation decision",
        )
    concrete = new HistoricalContinuationAuthentication(
      material.consensus,
      material.history,
      material.validators,
      original,
      nodes.map(_.controller),
      nodes.map(_.safety),
      V2HistoricalControllerFixture.ScopeDigest,
    )
    verifiedSafety <- nodes.traverse(_.safety.recover)
    certified      <- suffix.traverse(p =>
      material.consensus.certified(p, certificates(p.proposalId)),
    )
    entries <- certified.traverse { p =>
      for profile <- core(HistoricalProfileRange.digest(p.execution.selected))
      yield CertifiedSuffixEntry(
        InclusionHeight(p.execution.proposal.block.height.toBigNat),
        p.execution.proposal.targetBlockId.toUInt256,
        p.execution.parent.targetBlockId.toUInt256,
        profile,
        p.proposalDigest,
        p.quorumDigest,
        p.execution.replay.resultInventoryDigest,
      )
    }
    handover = HandoverEvidence(
      1L,
      intent,
      material.old,
      material.target,
      finality.anchorBlockId.toUInt256,
      InclusionHeight(finality.anchorHeight.toBigNat),
      f.replay.nextStateRoot,
      drainDigest,
      height(6L),
      suffix.last.targetBlockId.toUInt256,
      certified.last.execution.replay.nextStateRoot,
      entries,
      verifiedSafety
        .map(_.binding)
        .sortBy(row => V2Validation.textKey(row.signerId)),
      oldFences.sortBy(row => V2Validation.textKey(row.record.signerId)),
      proofDigest,
      material.target.configurationDigest,
      baselineDigest,
    )
    repository = new TransitionEvidenceRepository[IO]:
      def read(reference: EvidenceRef): Result[IO, Bytes] =
        EitherT.fromOption[IO](
          artifactMap.get(reference),
          V2RuntimeFailure.at(
            RuntimeFailureCode.EvidenceMissing,
            "original fixed transition artifact missing",
          ),
        )
      def baseline(digest: Hash): Result[IO, EvidenceBaseline] =
        check(digest == baselineDigest, "fixed original evidence baseline").as(
          fixed,
        )
      def sourceFence(digest: Hash): Result[IO, SignedFencePromise] =
        unavailable("handover source has no initial-bootstrap snapshot fence")
      def restoreAuthentication(
          evidence: RestoreEvidence,
      ): Result[IO, SignatureEnvelope] = unavailable(
        "restore requires its actual current complete-group authentication",
      )
    authentication = new TransitionAuditAuthentication[IO]:
      def artifact(reference: EvidenceRef, bytes: Bytes): Result[IO, Unit] =
        check(
          artifactMap.get(reference).contains(bytes),
          "actual canonical original artifact changed",
        )
      def source(
          binding: SourceBinding,
          evidence: TransitionEvidenceRepository[IO],
      ): Result[IO, SourceSnapshotClosure] = unavailable(
        "initial snapshot is a separate proof language",
      )
      def neverEnabled(
          record: NeverEnabled,
          evidence: TransitionEvidenceRepository[IO],
      ): Result[IO, NeverEnabledAudit] = bundle.never(record)
      def absence(
          record: ArchiveAbsence,
          baseline: EvidenceBaseline,
          evidence: TransitionEvidenceRepository[IO],
      ): Result[IO, HistoricalAbsenceAudit] = unavailable(
        "no historical absence is claimed",
      )
      def presentArchive(
          record: PresentArchive,
          evidence: TransitionEvidenceRepository[IO],
      ): Result[IO, PresentArchiveAudit] = unavailable(
        "no retired-domain archive is claimed",
      )
      def fence(
          promise: SignedFencePromise,
          intent: TransitionIntent,
          evidence: TransitionEvidenceRepository[IO],
      ): Result[IO, EnforcedFenceAudit] = concrete.fence(promise, intent)
      def continuation(
          record: HandoverEvidence,
          evidence: TransitionEvidenceRepository[IO],
      ): Result[IO, ContinuationAudit] = concrete.continuation(record)
      def drain(
          record: DrainEvidence,
          evidence: TransitionEvidenceRepository[IO],
      ): Result[IO, DrainAudit] = for
        _ <- check(
          record == drainRecord,
          "fixed original drain route or summary changed",
        )
        _ <- appFences.traverse_(promise => concrete.fence(promise, intent))
        actual <- bundle.current()
      yield actual
      def restoreFence(
          promise: SignedFencePromise,
          restore: RestoreEvidence,
          policy: TransitionPolicy,
          evidence: TransitionEvidenceRepository[IO],
      ): Result[IO, EnforcedFenceAudit] = unavailable(
        "restore current inventory is authenticated by the complete group fixture",
      )
      def restore(
          evidence: RestoreEvidence,
          repository: TransitionEvidenceRepository[IO],
      ): Result[IO, RestoreAudit] = unavailable(
        "restore current inventory is authenticated by the complete group fixture",
      )
    verifier <- EitherT.fromEither[IO](
      TransitionEvidenceVerifier.authenticated(
        TransitionPolicy(
          intent,
          material.manifest,
          fixed,
          Vector(
            TrustedTransitionAuthority(
              Utf8("neutral-original-authority"),
              V2HistoricalControllerFixture.AuthorityKey.publicKey.toBytes,
              Set(
                TransitionAuthorityRole.Restore,
                TransitionAuthorityRole.NeverEnabled,
              ),
            ),
          ),
          Vector(material.old.chainId),
          Vector(material.old),
        ),
        repository,
        authentication,
      ),
    )
    verified <- verifier.verifyHandover(handover)
    _        <- EitherT.liftF(
      V2HistoricalFixture.durable(
        material.root.resolve("fixed-baseline"),
        value(EvidenceBaseline.codec.encode(fixed)),
      ),
    )
  yield Transition(
    handover,
    verified,
    verifier,
    fixed,
    artifactMap,
    original,
    proof,
    concrete,
  )
