package org.sigilaris.conformance

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*
import org.sigilaris.core.application.protocol.ApplicationLockVoteSubject
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.FinalizedAnchorSuggestion

/** Reconciles original birth policies, every original signer and every forced
  * own deadline row. Its three routes share actual surviving-store checks.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.OptionPartial"),
)
object V2HistoricalDrainFixture:
  import V2HistoricalFixture.{core, check}
  import V2RequestConformance.{height, value, signed}
  private val Binary  = Utf8("neutral.historical-supported-issuance.binary.v1")
  private val Subject = Utf8("neutral.historical-original-subject.v1")
  private val LifetimeStart = 0L
  private val LifetimeEnd   = 6L
  private val Authority     = Utf8("neutral-original-authority")

  final class Bundle private[V2HistoricalDrainFixture] (
      val record: DrainEvidence,
      val artifacts: Map[EvidenceRef, Bytes],
      val current: () => Result[IO, DrainAudit],
      val never: NeverEnabled => Result[IO, NeverEnabledAudit],
  )

  private final case class Inventory(
      archives: Vector[HistoricalIssuanceArchive],
      verified: Vector[HistoricalIssuanceSafety.Verified],
      coverage: Bytes,
      digest: Hash,
      subjects: Vector[DurableIssuedSubject],
      stores: Vector[SurvivingApplicationInventory],
      greatest: Option[Height],
      maximumLifetime: Long,
      baseUpper: Height,
  )

  private def inventory(
      material: V2HistoricalFixture.Material,
      nodes: Vector[V2HistoricalControllerFixture.Node],
  ): Result[IO, Inventory] = for
    _ <- check(
      nodes.map(_.controller.signerId).toSet == material.source.keys
        .map(_._1)
        .toSet && nodes.sizeCompare(4) == 0,
      "drain requires the complete independently installed four-key original scope",
    )
    ordered = nodes.sortBy(node =>
      V2Validation.textKey(node.controller.signerId),
    )
    verified <- ordered.traverse(_.issuance.recover)
    audits   <- ordered.traverse(_.controller.audit)
    _        <- ordered.zip(verified).zip(audits).traverse_ {
      case ((node, original), audit) =>
        val old = audit.possibleSigningIntents.filter(
          _.material.context == material.old,
        )
        check(
          original.archive.policy == node.issuanceInstallation.policy && old
            .forall { intent =>
              intent.material.kind == ControllerSigningKind.Consensus ||
              (intent.material.kind == ControllerSigningKind.ApplicationLock &&
                HistoricalIssuanceRequest.codec
                  .decode(intent.request)
                  .toOption
                  .exists(request =>
                    original.claims.exists(_.request == request) &&
                      intent.material.canonicalPreimage == HistoricalIssuanceRequest
                        .signingPreimage(request),
                  ))
            },
          "original key history contains an uncovered application issuance or substituted birth policy",
        )
    }
    archives = verified.map(_.archive)
    _ <- check(
      archives.map(_.policy.maxLifetime).distinct.sizeCompare(1) == 0 &&
        archives.map(_.policy.baseUpperBound).distinct.sizeCompare(1) == 0,
      "source-wide historical issuance policy requires its complete original bounds",
    )
    bytes <- archives.traverse(a =>
      core(HistoricalIssuanceArchive.codec.encode(a)),
    )
    coverage <- core(
      V2HistoricalTransitionFixture.IssuanceArchive.codec.encode(
        V2HistoricalTransitionFixture.IssuanceArchive(1L, material.old, bytes),
      ),
    )
    digest = Commitment.hash(
      V2HistoricalTransitionFixture.IssuanceDomain,
      coverage,
    )
    subjects <- verified
      .flatMap(_.claims)
      .distinctBy(_.requestDigest)
      .traverse { claim =>
        for
          subject <- core(
            HistoricalLockFields.codec.encode(claim.request.subject),
          )
          row <- EitherT.fromOption[IO](
            archives
              .flatMap(_.records)
              .flatMap(raw =>
                HistoricalIssuanceRecord.codec
                  .decode(raw)
                  .toOption
                  .map(raw -> _),
              )
              .find { case (_, record) =>
                record.terminalEvidence.isEmpty && HistoricalIssuanceRequest.codec
                  .decode(record.request)
                  .toOption
                  .contains(claim.request)
              },
            V2RuntimeFailure.at(
              RuntimeFailureCode.EvidenceMissing,
              "original subject lost its own pre-issuance deadline row",
            ),
          )
          _ <- check(
            ByteEncoder[ApplicationLockVoteSubject]
              .encode(claim.request.subject.subject) == subject,
            "original subject codec differs from immutable M1 signing bytes",
          )
        yield DurableIssuedSubject(
          Commitment.hash(Subject, subject),
          material.old,
          claim.request.subject.executionId,
          claim.request.subject.lastInclusionHeight,
          subject,
          row._1,
          row._2.sequence,
          row._2.sequence,
          None,
        )
      }
    stores = verified.map { original =>
      def ids(claims: Vector[HistoricalIssuedClaim]): Vector[Hash] =
        claims.map(claim =>
          Commitment.hash(
            Subject,
            value(HistoricalLockFields.codec.encode(claim.request.subject)),
          ),
        )
      SurvivingApplicationInventory(
        material.old,
        value(HistoricalIssuanceArchive.digest(original.archive)),
        ids(original.claims),
        ids(original.live),
        Vector.empty,
        Vector.empty,
      )
    }
  yield Inventory(
    archives,
    verified,
    coverage,
    digest,
    subjects.sortBy(_.subjectDigest.bytes.toHex),
    stores,
    subjects.map(_.deadline).maxOption,
    archives.head.policy.maxLifetime,
    archives.head.policy.baseUpperBound,
  )

  def prepare(
      material: V2HistoricalFixture.Material,
      nodes: Vector[V2HistoricalControllerFixture.Node],
      intent: TransitionIntent,
      appFences: Vector[SignedFencePromise],
      finality: FinalizedAnchorSuggestion,
      kind: DrainKind,
  ): Result[IO, Bundle] = for
    original <- inventory(material, nodes)
    _        <- check(
      original.verified.forall(_.live.isEmpty),
      "live original claims cannot be frozen into a completed drain baseline",
    )
    previous <- V2HistoricalTransitionFixture.issuance(material)
    decoded  <- core(
      V2HistoricalTransitionFixture.IssuanceArchive.codec.decode(previous._1),
    )
    _ <- check(
      decoded.issued.isEmpty || previous._1 == original.coverage,
      "fixed original issuance coverage cannot be replaced after reconciliation",
    )
    _ <-
      if previous._1 == original.coverage then
        EitherT.pure[IO, V2RuntimeFailure](())
      else
        EitherT.liftF(
          V2HistoricalFixture.durable(
            material.root.resolve("original-applications"),
            original.coverage,
          ),
        )
    _ <- check(
      kind != DrainKind.NeverEnabled || original.archives.forall(a =>
        a.policy.lockIssuance == IssuanceCapability.ContinuouslyDisabled && a.records.isEmpty,
      ),
      "enabled or issued original policies cannot establish never-enabled history",
    )
    binary = Commitment.hash(
      Binary,
      V2HistoricalControllerFixture.SchemaDigest.bytes,
    )
    paths = original.archives.map(a =>
      SigningPath(
        a.policy.signerId,
        binary,
        value(HistoricalIssuancePolicy.digest(a.policy)),
        a.policy.lockIssuance,
        a.policy.effectIssuance,
      ),
    )
    deployments = original.archives.zip(paths).map { (a, path) =>
      DeploymentInterval(
        a.policy.signerId,
        LifetimeStart,
        LifetimeEnd,
        binary,
        path.configurationDigest,
        Vector(a.policy.signerId),
        a.policy.lockIssuance,
        a.policy.effectIssuance,
        Commitment.hash(Binary, value(SigningPath.codec.encode(path))),
      )
    }
    keyUses = original.archives.zip(paths).map { (a, path) =>
      KeyUseInterval(
        a.policy.signerId,
        LifetimeStart,
        LifetimeEnd,
        Vector(path),
        value(HistoricalIssuanceArchive.digest(a)),
      )
    }
    neverRecord = NeverEnabled(
      1L,
      material.old.chainId,
      LifetimeStart,
      LifetimeEnd,
      V2HistoricalControllerFixture.ScopeDigest,
      deployments,
      keyUses,
      Vector(EvidenceRef(EvidenceKind.Drain, original.digest)),
      Authority,
    )
    neverPreimage <- core(NeverEnabled.signingPreimage(neverRecord))
    never = SignedNeverEnabled(
      neverRecord,
      SignatureEnvelope(
        Authority,
        1.toByte,
        V2HistoricalControllerFixture.AuthorityKey.publicKey.toBytes,
        signed(V2HistoricalControllerFixture.AuthorityKey, neverPreimage),
      ),
    )
    neverBytes  <- core(SignedNeverEnabled.codec.encode(never))
    neverDigest <- core(SignedNeverEnabled.digest(never))
    finalityBytes  = ByteEncoder[FinalizedAnchorSuggestion].encode(finality)
    finalityDigest = Commitment.hash(
      Utf8("neutral.historical-finality.original.v1"),
      finalityBytes,
    )
    fenceDigest  <- core(SignedFencePromise.digest(appFences.head))
    intentDigest <- core(TransitionIntent.digest(intent))
    record = DrainEvidence(
      1L,
      intentDigest,
      material.old,
      kind,
      fenceDigest,
      Option.when(kind == DrainKind.JournalCovered)(original.digest),
      original.greatest,
      Option.when(kind == DrainKind.InferredHorizon)(original.baseUpper),
      original.maximumLifetime,
      if kind == DrainKind.NeverEnabled then neverDigest else original.digest,
      Option.when(kind != DrainKind.NeverEnabled)(finalityDigest),
      Some(original.digest),
    )
    recordBytes  <- core(DrainEvidence.codec.encode(record))
    recordDigest <- core(DrainEvidence.digest(record))
    defCurrent = () =>
      for
        actual <- inventory(material, nodes)
        fixed  <- V2HistoricalTransitionFixture.issuance(material)
        _      <- check(
          actual.coverage == original.coverage && fixed._1 == original.coverage,
          "actual original policies, forced issuance rows or fixed coverage changed",
        )
        oldFinality <-
          if kind == DrainKind.NeverEnabled then
            EitherT.pure[IO, V2RuntimeFailure](None)
          else
            material.consensus.finalized(finality).map { checked =>
              val first = checked.ordered.head
              Some(
                OriginalDomainFinality(
                  material.old,
                  first.proposal.targetBlockId.toUInt256,
                  height(first.proposal.block.height.toBigNat.toBigInt.toLong),
                  first.replay.nextStateRoot,
                  finalityDigest,
                  finalityBytes,
                ),
              )
            }
      yield DrainAudit(
        material.old,
        appFences.head,
        actual.subjects.map(_.subjectDigest),
        actual.subjects,
        record.reconciledInventoryDigest,
        actual.greatest,
        record.baseUpperBound,
        actual.maximumLifetime,
        oldFinality,
        Some(actual.digest),
        actual.stores,
      )
    defNever = (submitted: NeverEnabled) =>
      for
        actual <- inventory(material, nodes)
        fixed  <- V2HistoricalTransitionFixture.issuance(material)
        _      <- check(
          submitted == neverRecord && actual.coverage == original.coverage && fixed._1 == original.coverage &&
            actual.archives.forall(a =>
              a.policy.lockIssuance == IssuanceCapability.ContinuouslyDisabled && a.records.isEmpty,
            ),
          "never-enabled proof changed its independently installed lifetime paths or original stores",
        )
      yield NeverEnabledAudit(
        material.old.chainId,
        LifetimeStart,
        LifetimeEnd,
        V2HistoricalControllerFixture.ScopeDigest,
        actual.archives.map(a =>
          ScopedInterval(a.policy.signerId, LifetimeStart, LifetimeEnd),
        ),
        actual.archives.map(a =>
          ScopedInterval(a.policy.signerId, LifetimeStart, LifetimeEnd),
        ),
        actual.stores,
      )
  yield new Bundle(
    record,
    Map(
      EvidenceRef(EvidenceKind.Drain, recordDigest)       -> recordBytes,
      EvidenceRef(EvidenceKind.Drain, original.digest)    -> original.coverage,
      EvidenceRef(EvidenceKind.NeverEnabled, neverDigest) -> neverBytes,
    ),
    defCurrent,
    defNever,
  )

  /** Independent original deployments exercise all three proof routes; an
    * inferred bound equal to original finality must stall without a handover.
    */
  def run(root: java.nio.file.Path): IO[Unit] =
    Vector(
      DrainKind.JournalCovered,
      DrainKind.InferredHorizon,
      DrainKind.NeverEnabled,
    ).traverse_ { kind =>
      V2HistoricalFixture
        .material(root.resolve(kind.toString))
        .flatMap { material =>
          V2HistoricalControllerFixture.nodes(material).map(material -> _)
        }
        .use { (material, nodes) =>
          V2HistoricalFixture.accepted(for
            _          <- material.buildOld(nodes.map(_.signer))
            transition <- V2HistoricalTransitionFixture
              .prepare(material, nodes, kind)
            verified <- transition.verifier.verifyHandover(transition.evidence)
            _        <- check(
              verified.digest == transition.verified.digest,
              "original drain route must remain fully re-verifiable",
            )
            raw <- EitherT.fromOption[IO](
              transition.artifacts.get(
                EvidenceRef(
                  EvidenceKind.Drain,
                  transition.evidence.drainEvidenceDigest,
                ),
              ),
              V2RuntimeFailure.at(
                RuntimeFailureCode.EvidenceMissing,
                "actual fixed drain record absent",
              ),
            )
            drain <- core(DrainEvidence.codec.decode(raw))
            _     <- check(
              drain.kind == kind && drain.oldMaximumLifetime == 2L,
              "route must preserve actual independently installed lifetime bound",
            )
          yield ())
        }
    } *> V2HistoricalFixture
      .material(root.resolve("stalled"))
      .flatMap { material =>
        V2HistoricalControllerFixture.nodes(material).map(material -> _)
      }
      .use { (material, nodes) =>
        for
          _ <- V2HistoricalFixture.accepted(
            material.buildOld(nodes.map(_.signer)),
          )
          attempt <- V2HistoricalTransitionFixture
            .prepare(material, nodes, DrainKind.InferredHorizon, 2L)
            .value
          _ <- V2HistoricalFixture.accepted(
            check(
              attempt.left
                .exists(_.code == RuntimeFailureCode.EvidenceContradictory),
              "actual original finality at D must not discharge inferred horizon",
            ),
          )
          audits <- V2HistoricalFixture.accepted(
            nodes.traverse(_.controller.audit),
          )
          _ <- V2HistoricalFixture.accepted(
            check(
              audits.forall(
                _.signedFences
                  .exists(_.record.scope == FenceScope.ApplicationIssuance),
              ),
              "stalled drain must preserve its original future issuance fences",
            ),
          )
        yield ()
      }
