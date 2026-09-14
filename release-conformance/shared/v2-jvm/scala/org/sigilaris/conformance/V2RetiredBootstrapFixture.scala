package org.sigilaris.conformance

import java.nio.file.{Files, Path}
import cats.data.EitherT
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import scodec.bits.ByteVector
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.given
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.storage.file.FileApplicationJournal

/** Original present/absent history for a neutral retired format. The signed
  * report predates the fixed baseline and explicitly inventories every deployed
  * disabled signing path; present bytes remain mandatory after selection.
  */
object V2RetiredBootstrapFixture:
  import V2RequestConformance.*
  import V2VotingConformance.accepted
  import V2BootstrapFixture.{
    Seed,
    SourceDump,
    authorize,
    envelope,
    original,
    seedDomain,
    sourceDomain,
    authority,
    authorityId,
  }
  val retiredId: Text    = Utf8("retired-source-controller")
  val retiredKey         = CryptoOps.fromPrivate(BigInt(903))
  val reportDomain: Text = Utf8("neutral.bootstrap.original-retired-report.v1")
  final case class RetiredReport(
      domain: Text,
      sourceBinding: Hash,
      targetChain: Text,
      observedAt: Long,
      lifetimeStart: Long,
      lifetimeEnd: Long,
      signerId: Text,
      deploymentId: Text,
      binary: Bytes,
      configuration: Bytes,
      presentState: Option[Vector[Long]],
      missingScopes: Vector[Text],
      originalRollback: Text,
      nonAncestorCheckpoint: Hash,
  )
  object RetiredReport:
    given ByteEncoder[RetiredReport] = ByteEncoder.derived
    given ByteDecoder[RetiredReport] = ByteDecoder.derived
    val codec                        =
      CanonicalCodec.derived[RetiredReport](_ => Right[CoreFailure, Unit](()))
  private def core[A](v: Either[CoreFailure, A]): Result[IO, A] =
    EitherT.fromEither[IO](v.leftMap(V2RuntimeFailure.fromCore))
  private def check(ok: Boolean, detail: String): Result[IO, Unit] =
    EitherT.cond[IO](
      ok,
      (),
      V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, detail),
    )
  private def no[A]: Result[IO, A] = unavailable(
    "the retired fixture has no original proof for the requested class",
  )
  private def read(path: Path): Result[IO, Bytes] = EitherT(
    IO.blocking(ByteVector.view(Files.readAllBytes(path)))
      .attempt
      .map(
        _.leftMap(_ =>
          V2RuntimeFailure.at(
            RuntimeFailureCode.ProofUnavailable,
            "original retired evidence file is unavailable",
          ),
        ),
      ),
  )

  final class Material(
      val base: V2BootstrapFixture.Material,
      val controller: FenceController,
      val retiredContext: DomainContext,
      val retirementFence: SignedFencePromise,
      present: Boolean,
      partlyAbsent: Boolean,
      overlapping: Boolean,
  ):
    val root    = base.root
    val context = base.context
    val intent  = base.intent
    val source  = base.source
    val dump    = base.dump.copy(
      ancestry = SourceAncestry.RetiredNonAncestorDomains,
      retiredDomains = Vector(retiredContext.chainId),
    )
    val dumpRaw  = authorize(sourceDomain, value(SourceDump.codec.encode(dump)))
    val dumpPath = root.resolve("retired-selected-source.bin")
    val statePayload    = ByteEncoder[Vector[Long]].encode(dump.state)
    val stateRoot       = hash(statePayload)
    val sourceInventory = Vector(
      InventoryEntry(Utf8("neutral-state"), Utf8("cells"), stateRoot),
    )
    val binding = SourceBinding(
      1L,
      dump.domain,
      dump.checkpoint,
      dump.height,
      stateRoot,
      dump.stateSchema,
      value(TransitionInventory.digest(sourceInventory)),
      Commitment.hash(sourceDomain, dumpRaw),
      dump.replayPolicy,
    )
    val sourceRef = EvidenceRef(EvidenceKind.Source, binding.provenanceDigest)
    val sourceFenceRef = EvidenceRef(
      EvidenceKind.Fence,
      value(SignedFencePromise.digest(base.sourceFence)),
    )
    val retiredFenceRef = EvidenceRef(
      EvidenceKind.Fence,
      value(SignedFencePromise.digest(retirementFence)),
    )
    val report = RetiredReport(
      retiredContext.chainId,
      value(SourceBinding.digest(binding)),
      context.chainId,
      21L,
      1L,
      20L,
      retiredId,
      Utf8("retired-neutral-deployment"),
      bytes("010000"),
      bytes("020000"),
      Option.when(present)(Vector(12L, 5L)),
      if partlyAbsent && overlapping then Vector(Utf8("retired-state"))
      else if partlyAbsent then Vector(Utf8("retired-extra-state"))
      else if present then Vector.empty
      else Vector(Utf8("retired-state")),
      Utf8("signed pre-baseline external snapshot rollback"),
      dump.checkpoint,
    )
    val reportRaw =
      authorize(reportDomain, value(RetiredReport.codec.encode(report)))
    val reportPath     = root.resolve("original-retired-report.bin")
    val reportDigest   = Commitment.hash(reportDomain, reportRaw)
    val reportRef      = EvidenceRef(EvidenceKind.KeyUse, reportDigest)
    val rollbackRef    = EvidenceRef(EvidenceKind.Rollback, reportDigest)
    val ancestryRef    = EvidenceRef(EvidenceKind.NonAncestry, reportDigest)
    val retiredContent = report.presentState.toVector.map(values =>
      InventoryEntry(
        Utf8("retired-state"),
        Utf8("cells"),
        hash(ByteEncoder[Vector[Long]].encode(values)),
      ),
    )
    val archive = PresentArchive(
      1L,
      retiredContext.chainId,
      value(TransitionInventory.digest(retiredContent)),
      value(SourceBinding.digest(binding)),
      context.chainId,
      retiredFenceRef.digest,
    )
    val archiveRef = EvidenceRef(
      EvidenceKind.PresentContent,
      value(PresentArchive.digest(archive)),
    )
    val disabled = IssuanceCapability.Unavailable
    val path     = SigningPath(
      Utf8("neutral-disabled-v1"),
      hash(report.binary),
      hash(report.configuration),
      disabled,
      disabled,
    )
    val never = NeverEnabled(
      1L,
      report.domain,
      report.lifetimeStart,
      report.lifetimeEnd,
      reportDigest,
      Vector(
        DeploymentInterval(
          report.deploymentId,
          1L,
          20L,
          hash(report.binary),
          hash(report.configuration),
          Vector(retiredId),
          disabled,
          disabled,
          reportDigest,
        ),
      ),
      Vector(KeyUseInterval(retiredId, 1L, 20L, Vector(path), reportDigest)),
      Vector(reportRef),
      authorityId,
    )
    val signedNever = SignedNeverEnabled(
      never,
      envelope(value(NeverEnabled.signingPreimage(never))),
    )
    val neverRef = EvidenceRef(
      EvidenceKind.NeverEnabled,
      value(SignedNeverEnabled.digest(signedNever)),
    )
    val absence = ArchiveAbsence(
      1L,
      report.domain,
      value(SourceBinding.digest(binding)),
      context.chainId,
      if report.missingScopes.nonEmpty then report.missingScopes
      else Vector(Utf8("retired-state")),
      Utf8(
        "original signed inventory recorded absence before the fixed transition baseline",
      ),
      Some(20L),
      Some(21L),
      Vector(Utf8("original retired payload is unknown")),
      Vector(reportRef),
      reportDigest,
      reportDigest,
      neverRef.digest,
      authorityId,
    )
    val signedAbsence = SignedArchiveAbsence(
      absence,
      envelope(value(ArchiveAbsence.signingPreimage(absence))),
    )
    val absenceRef = EvidenceRef(
      EvidenceKind.HistoricalAbsence,
      value(SignedArchiveAbsence.digest(signedAbsence)),
    )
    val selectedRefs = (if present then Vector(archiveRef) else Vector.empty) ++
      (if !present || partlyAbsent then Vector(absenceRef) else Vector.empty)
    val baseline = EvidenceBaseline(
      1L,
      value(TransitionIntent.digest(intent)),
      binding.domain,
      context.chainId,
      binding.checkpointId,
      stateRoot,
      intent.authorityPolicyDigest,
      (Vector(
        sourceRef,
        sourceFenceRef,
        retiredFenceRef,
        reportRef,
      ) ++ selectedRefs ++
        (if present && !partlyAbsent then Vector.empty
         else Vector(neverRef, rollbackRef, ancestryRef))).distinct.sortBy(r =>
        (r.kind.tag, r.digest.bytes.toHex),
      ),
    )
    val policy = TransitionPolicy(
      intent,
      base.manifest,
      baseline,
      Vector(
        TrustedTransitionAuthority(
          authorityId,
          authority.publicKey.toBytes,
          Set(
            TransitionAuthorityRole.Bootstrap,
            TransitionAuthorityRole.HistoricalAbsence,
            TransitionAuthorityRole.NeverEnabled,
          ),
        ),
      ),
      Vector(binding.domain, retiredContext.chainId).sortBy(_.asString),
      Vector.empty,
    )
    val bundle = BootstrapBundle(
      1L,
      intent,
      binding,
      context,
      source.keys.map((id, key) => InitialValidator(id, key.publicKey.toBytes)),
      context.configurationDigest,
      123456L,
      sourceFenceRef.digest,
      SourceAncestry.RetiredNonAncestorDomains,
      selectedRefs.sortBy(ref => (ref.kind.tag, ref.digest.bytes.toHex)),
      value(EvidenceBaseline.digest(baseline)),
      intent.authorityPolicyDigest,
    )
    val signedBundle = SignedBootstrapBundle(
      bundle,
      envelope(value(BootstrapBundle.signingPreimage(bundle))),
    )
    val repository: TransitionEvidenceRepository[IO] =
      new TransitionEvidenceRepository[IO]:
        def read(ref: EvidenceRef): Result[IO, Bytes] =
          if ref == sourceRef then V2RetiredBootstrapFixture.read(dumpPath)
          else if Set(reportRef, rollbackRef, ancestryRef).contains(ref) then
            V2RetiredBootstrapFixture.read(reportPath)
          else if ref == sourceFenceRef then
            core(SignedFencePromise.codec.encode(base.sourceFence))
          else if ref == retiredFenceRef then
            core(SignedFencePromise.codec.encode(retirementFence))
          else if ref == archiveRef && present then
            core(PresentArchive.codec.encode(archive))
          else if ref == absenceRef && (!present || partlyAbsent) then
            core(SignedArchiveAbsence.codec.encode(signedAbsence))
          else if ref == neverRef && (!present || partlyAbsent) then
            core(SignedNeverEnabled.codec.encode(signedNever))
          else no
        def baseline(digest: Hash): Result[IO, EvidenceBaseline] =
          if digest == value(EvidenceBaseline.digest(Material.this.baseline))
          then EitherT.pure(Material.this.baseline)
          else no
        def sourceFence(digest: Hash): Result[IO, SignedFencePromise] =
          if digest == sourceFenceRef.digest then EitherT.pure(base.sourceFence)
          else if digest == retiredFenceRef.digest then
            EitherT.pure(retirementFence)
          else no
        def restoreAuthentication(
            evidence: RestoreEvidence,
        ): Result[IO, SignatureEnvelope] = no

    private def decodedReport: Result[IO, RetiredReport] = for
      raw     <- read(reportPath)
      payload <- EitherT.fromEither[IO](original(reportDomain, raw))
      parsed  <- core(RetiredReport.codec.decode(payload))
      _       <- check(
        Commitment.hash(
          reportDomain,
          raw,
        ) == reportDigest && parsed.domain == retiredContext.chainId &&
          parsed.sourceBinding == value(
            SourceBinding.digest(binding),
          ) && parsed.targetChain == context.chainId &&
          parsed.observedAt < 30L && parsed.nonAncestorCheckpoint == binding.checkpointId && parsed.originalRollback.asString.nonEmpty,
        "retired original report changed its source/target, pre-baseline observation or non-ancestry",
      )
    yield parsed
    private def actualFence(
        promise: SignedFencePromise,
        selected: TransitionIntent,
    ): Result[IO, EnforcedFenceAudit] =
      val selectedController =
        if promise.record.context == base.sourceContext then
          base.sourceController
        else controller
      for
        current <- selectedController.audit
        _       <- check(
          selected == intent && Set(base.sourceContext, retiredContext)
            .contains(promise.record.context) &&
            current.signedFences.contains(promise) && current.enforcements
              .exists(_.promise == promise.record),
          "retired/source fence is missing from its actual enforced key controller",
        )
        heights = current.possibleSigningIntents
          .filter(_.material.context == promise.record.context)
          .flatMap(_.material.height)
      yield EnforcedFenceAudit(
        promise,
        selectedController.publicKey,
        intent.sourceAuthorityScopeDigest,
        heights,
        current.inventory,
      )
    val audit: TransitionAuditAuthentication[IO] =
      new TransitionAuditAuthentication[IO]:
        def artifact(ref: EvidenceRef, raw: Bytes): Result[IO, Unit] =
          if ref == sourceRef then
            EitherT
              .fromEither[IO](original(sourceDomain, raw))
              .flatMap(_ =>
                check(
                  Commitment.hash(sourceDomain, raw) == ref.digest,
                  "original source digest changed",
                ),
              )
          else if Set(reportRef, rollbackRef, ancestryRef).contains(ref) then
            EitherT
              .fromEither[IO](original(reportDomain, raw))
              .flatMap(_ =>
                check(
                  Commitment.hash(reportDomain, raw) == ref.digest,
                  "original retired report digest changed",
                ),
              )
          else if Set(sourceFenceRef, retiredFenceRef).contains(ref) then
            core(SignedFencePromise.codec.decode(raw)).flatMap(p =>
              check(
                value(SignedFencePromise.digest(p)) == ref.digest,
                "fence digest changed",
              ),
            )
          else if ref == archiveRef then
            core(PresentArchive.codec.decode(raw)).flatMap(v =>
              check(
                value(PresentArchive.digest(v)) == ref.digest,
                "archive digest changed",
              ),
            )
          else if ref == neverRef then
            core(SignedNeverEnabled.codec.decode(raw)).flatMap(v =>
              check(
                value(SignedNeverEnabled.digest(v)) == ref.digest,
                "never-enabled original digest changed",
              ),
            )
          else if ref == absenceRef then
            core(SignedArchiveAbsence.codec.decode(raw)).flatMap(v =>
              check(
                value(SignedArchiveAbsence.digest(v)) == ref.digest,
                "absence original digest changed",
              ),
            )
          else no
        def source(
            selected: SourceBinding,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, SourceSnapshotClosure] = for
          raw <- evidence.read(
            EvidenceRef(EvidenceKind.Source, selected.provenanceDigest),
          )
          payload <- EitherT.fromEither[IO](original(sourceDomain, raw))
          parsed  <- core(SourceDump.codec.decode(payload))
          state     = ByteEncoder[Vector[Long]].encode(parsed.state)
          root      = hash(state)
          inventory = Vector(
            InventoryEntry(Utf8("neutral-state"), Utf8("cells"), root),
          )
          actual = SourceBinding(
            1L,
            parsed.domain,
            parsed.checkpoint,
            parsed.height,
            root,
            parsed.stateSchema,
            value(TransitionInventory.digest(inventory)),
            Commitment.hash(sourceDomain, raw),
            parsed.replayPolicy,
          )
          promise <- evidence.sourceFence(parsed.fenceDigest)
          _       <- actualFence(promise, intent)
          _       <- check(
            actual == selected && parsed.ancestry == SourceAncestry.RetiredNonAncestorDomains &&
              parsed.retiredDomains == Vector(retiredContext.chainId),
            "selected source reinterpreted retired signing ancestry",
          )
        yield SourceSnapshotClosure(
          actual,
          inventory,
          state,
          parsed.ancestry,
          parsed.retiredDomains,
          intent.sourceAuthorityScopeDigest,
          promise,
        )
        def fence(
            promise: SignedFencePromise,
            intent: TransitionIntent,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, EnforcedFenceAudit] = actualFence(promise, intent)
        def presentArchive(
            record: PresentArchive,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, PresentArchiveAudit] = for
          original <- decodedReport
          values   <- EitherT.fromOption[IO](
            original.presentState,
            V2RuntimeFailure.at(
              RuntimeFailureCode.EvidenceMissing,
              "present archive lost original state",
            ),
          )
          inventory = Vector(
            InventoryEntry(
              Utf8("retired-state"),
              Utf8("cells"),
              hash(ByteEncoder[Vector[Long]].encode(values)),
            ),
          )
          _ <- check(
            record == archive && value(
              TransitionInventory.digest(inventory),
            ) == record.inventoryDigest,
            "present archive root differs from original content",
          )
        yield PresentArchiveAudit(
          record,
          inventory,
          Vector(Utf8("retired-state")),
          retirementFence,
        )
        def neverEnabled(
            record: NeverEnabled,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, NeverEnabledAudit] = for
          original <- decodedReport
          history  <- controller.audit
          _        <- check(
            original.binary == bytes(
              "010000",
            ) && original.configuration == bytes("020000") &&
              record.deployments.forall(v =>
                v.binaryDigest == hash(
                  original.binary,
                ) && v.effectiveConfigDigest == hash(original.configuration),
              ) &&
              record.keyUses.forall(v =>
                v.signerKeyId == original.signerId && v.usePaths.forall(p =>
                  p.binaryDigest == hash(
                    original.binary,
                  ) && p.configurationDigest == hash(original.configuration),
                ),
              ) &&
              history.possibleSigningIntents.isEmpty && original.lifetimeStart == 1L && original.lifetimeEnd == 20L,
            "retired original binary/config/key roster does not establish unavailable application signing for its entire lifetime",
          )
        yield NeverEnabledAudit(
          original.domain,
          original.lifetimeStart,
          original.lifetimeEnd,
          reportDigest,
          Vector(ScopedInterval(original.signerId, 1L, 20L)),
          Vector(ScopedInterval(original.deploymentId, 1L, 20L)),
          Vector.empty,
        )
        def absence(
            record: ArchiveAbsence,
            fixed: EvidenceBaseline,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, HistoricalAbsenceAudit] = for
          original <- decodedReport
          _        <- check(
            original.missingScopes.nonEmpty && (original.presentState.isEmpty ||
              baseline.entries.contains(
                archiveRef,
              )) && original.missingScopes == record.missingScopes && record == Material.this.absence && fixed == baseline,
            "historical absence replaced known present bytes or changed the original fixed missing scope",
          )
        yield HistoricalAbsenceAudit(
          original.domain,
          original.sourceBinding,
          original.targetChain,
          original.missingScopes,
          value(EvidenceBaseline.digest(fixed)),
          original.missingScopes,
          Vector.empty,
        )
        def drain(
            record: DrainEvidence,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, DrainAudit] = no
        def continuation(
            record: HandoverEvidence,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, ContinuationAudit] = no
        def restoreFence(
            promise: SignedFencePromise,
            restore: RestoreEvidence,
            policy: TransitionPolicy,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, EnforcedFenceAudit] = no
        def restore(
            record: RestoreEvidence,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, RestoreAudit] = no
    val verifier: TransitionEvidenceVerifier[IO] & SourceSnapshotVerifier[IO] =
      TransitionEvidenceVerifier
        .authenticated(policy, repository, audit)
        .fold(e => throw new IllegalStateException(e.message), identity)
    def verified: Result[IO, VerifiedBootstrap] =
      verifier.verifyBootstrap(signedBundle)
    def node(
        index: Int,
        bootstrap: VerifiedBootstrap,
        ordinary: DurableJournal[IO] => IO[
          Option[HotStuffControllerAuthentication],
        ] = _ => IO.pure(None),
    ): Resource[IO, V2BootstrapFixture.Node] =
      val (id, key) = source.keys(index)
      val seed      = authorize(
        seedDomain,
        value(
          Seed.codec.encode(
            Seed(
              id,
              key.publicKey.toBytes,
              ControllerInitialHistory(
                Vector.empty,
                Vector.empty,
                Vector.empty,
                Vector.empty,
                Vector.empty,
              ),
            ),
          ),
        ),
      )
      for
        journal <- FileApplicationJournal.resource(
          root.resolve("validator-" + index.toString).resolve("application"),
        )
        initialInstaller <- Resource.eval(
          Ref.of[IO, Option[InitialStateInstaller[IO]]](None),
        )
        target <- FileFenceController.resource(
          root.resolve("validator-" + index.toString).resolve("controller"),
          key,
          id,
          seed,
          V2BootstrapFixture.authentication(
            id,
            key,
            intent,
            base.sourceContext,
            Some(bootstrap -> journal),
            ordinary(journal),
            initialInstaller.get,
          ),
          ControllerCanonicalWriter.unavailable,
        )
        installer <- Resource.eval(
          InitialStateInstaller
            .authenticated(bootstrap, verifier, repository, target, journal),
        )
        _ <- Resource.eval(initialInstaller.set(Some(installer)))
      yield V2BootstrapFixture.Node(journal, target, installer)

  def material(
      root: Path,
      present: Boolean,
      partlyAbsent: Boolean = false,
      overlapping: Boolean = false,
  ): Resource[IO, Material] = for
    base <- V2BootstrapFixture.material(root)
    old = DomainContext(
      1L,
      Utf8("neutral-retired-nonancestor"),
      uint(801L),
      1L,
      uint(802L),
    )
    seed = authorize(
      seedDomain,
      value(
        Seed.codec.encode(
          Seed(
            retiredId,
            retiredKey.publicKey.toBytes,
            ControllerInitialHistory(
              Vector(old),
              Vector.empty,
              Vector.empty,
              Vector.empty,
              Vector.empty,
            ),
          ),
        ),
      ),
    )
    controller <- FileFenceController.resource(
      root.resolve("retired-controller"),
      retiredKey,
      retiredId,
      seed,
      V2BootstrapFixture
        .authentication(retiredId, retiredKey, base.intent, old, None),
      ControllerCanonicalWriter.unavailable,
    )
    promise <- Resource.eval(
      accepted(
        controller.prepareFence(
          base.intent,
          old,
          FenceScope.SourceOrRetiredDomainWritesAndSigning,
          height(0L),
        ),
      ),
    )
    fence <- Resource.eval(accepted(controller.enforce(base.intent, promise)))
    result = new Material(
      base,
      controller,
      old,
      fence,
      present,
      partlyAbsent,
      overlapping,
    )
    _ <- Resource.eval(IO.blocking {
      Files.write(result.dumpPath, result.dumpRaw.toArray);
      Files.write(result.reportPath, result.reportRaw.toArray); ()
    })
  yield result
