package org.sigilaris.conformance

import java.nio.file.{Files, Path}
import cats.data.EitherT
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.given
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.crypto.{CryptoOps, KeyPair}
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.storage.file.FileApplicationJournal

/** Neutral original source format with a real authority-signed state dump and
  * actual durable source fence. It describes only these maintained fixture
  * files; it does not claim to audit an arbitrary production deployment.
  */
object V2BootstrapFixture:
  import V2RequestConformance.*
  import V2VotingConformance.accepted
  val authority: KeyPair = CryptoOps.fromPrivate(BigInt(901))
  val authorityId: Text  = Utf8("bootstrap-source-authority")
  val sourceId: Text     = Utf8("bootstrap-source-controller")
  val sourceKey: KeyPair = CryptoOps.fromPrivate(BigInt(902))
  val seedDomain: Text   = Utf8("neutral.bootstrap.controller-seed.v1")
  val sourceDomain: Text = Utf8("neutral.bootstrap.source-dump.v1")

  final case class Authorized(payload: Bytes, authentication: SignatureEnvelope)
  object Authorized:
    given ByteEncoder[Authorized] = ByteEncoder.derived
    given ByteDecoder[Authorized] = ByteDecoder.derived
    val codec                     = CanonicalCodec.derived[Authorized](v =>
      SignatureEnvelope.validate(v.authentication),
    )
  final case class Seed(
      signer: Text,
      publicKey: Bytes,
      history: ControllerInitialHistory,
  )
  object Seed:
    given ByteEncoder[Seed] = ByteEncoder.derived
    given ByteDecoder[Seed] = ByteDecoder.derived
    val codec               = CanonicalCodec.derived[Seed](v =>
      ControllerInitialHistory.codec.encode(v.history).void,
    )
  // Independently installed neutral policy: only a new-domain/G-bound SignedOpening
  // is a target transaction. Original source payload/signature bytes are import evidence.
  val ReplayPolicyDigest: Hash = uint(708L)
  final case class SourceDump(
      domain: Text,
      checkpoint: Hash,
      height: Height,
      stateSchema: Hash,
      state: Vector[Long],
      replayPolicy: Hash,
      fenceDigest: Hash,
      ancestry: SourceAncestry,
      retiredDomains: Vector[Text],
  )
  object SourceDump:
    given ByteEncoder[SourceDump] = ByteEncoder.derived
    given ByteDecoder[SourceDump] = ByteDecoder.derived
    val codec                     =
      CanonicalCodec.derived[SourceDump](_ => Right[CoreFailure, Unit](()))

  def envelope(preimage: Bytes): SignatureEnvelope = SignatureEnvelope(
    authorityId,
    1.toByte,
    authority.publicKey.toBytes,
    signed(authority, preimage),
  )
  def authorize(domain: Text, payload: Bytes): Bytes = value(
    Authorized.codec.encode(
      Authorized(payload, envelope(Commitment.preimage(domain, payload))),
    ),
  )
  def original(domain: Text, raw: Bytes): Either[V2RuntimeFailure, Bytes] = for
    wrapped <- Authorized.codec.decode(raw).leftMap(V2RuntimeFailure.fromCore)
    _       <- Either.cond(
      wrapped.authentication.authorityId == authorityId &&
        wrapped.authentication.publicKey == authority.publicKey.toBytes &&
        recovered(
          wrapped.authentication.signature,
          Commitment.preimage(domain, wrapped.payload),
          authority,
        ),
      (),
      V2RuntimeFailure.at(
        RuntimeFailureCode.InvalidSignature,
        "neutral original source/seed signature failed",
      ),
    )
  yield wrapped.payload
  private def check(condition: Boolean, detail: String): Result[IO, Unit] =
    EitherT.cond[IO](
      condition,
      (),
      V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, detail),
    )
  private def lift[A](result: Either[V2RuntimeFailure, A]): Result[IO, A] =
    EitherT.fromEither[IO](result)
  private def core[A](result: Either[CoreFailure, A]): Result[IO, A] = lift(
    result.leftMap(V2RuntimeFailure.fromCore),
  )
  private def no[A]: Result[IO, A] = unavailable(
    "the neutral bootstrap fixture has no proof for this evidence class",
  )
  private def fileRead(path: Path): Result[IO, Bytes] = EitherT(
    IO.blocking(ByteVector.view(Files.readAllBytes(path)))
      .attempt
      .map(
        _.leftMap(_ =>
          V2RuntimeFailure.at(
            RuntimeFailureCode.ProofUnavailable,
            "original source file is unavailable",
          ),
        ),
      ),
  )

  def authentication(
      id: Text,
      key: KeyPair,
      intent: TransitionIntent,
      allowedSource: DomainContext,
      bootstrap: Option[(VerifiedBootstrap, DurableJournal[IO])],
      ordinary: IO[Option[HotStuffControllerAuthentication]] = IO.pure(None),
      installer: IO[Option[InitialStateInstaller[IO]]] = IO.pure(None),
  ): ControllerOperationAuthentication =
    new ControllerOperationAuthentication:
      def signing(
          request: Bytes,
          signer: Text,
          publicKey: Bytes,
      ): Result[IO, ControllerSigningMaterial] =
        if InitialBootstrapSigningRequest.codec.decode(request).isRight then
          bootstrap match
            case Some((verified, journal)) =>
              InitialBootstrapSigningAuthentication.signing(
                verified,
                journal,
                request,
                signer,
                publicKey,
              )
            case None => no
        else
          EitherT.liftF(ordinary).flatMap {
            case Some(actual) => actual.signing(request, signer, publicKey)
            case None         => no
          }
      def authorizeSigning(
          request: Bytes,
          signer: Text,
          publicKey: Bytes,
      ): Result[IO, Unit] =
        if InitialBootstrapSigningRequest.codec.decode(request).isRight then
          EitherT
            .fromOptionF(
              installer,
              V2RuntimeFailure.at(
                RuntimeFailureCode.RecoveryRequired,
                "fresh initial key use requires its bound actual installer",
              ),
            )
            .flatMap(actual =>
              InitialBootstrapSigningAuthentication
                .authorize(actual, request, signer, publicKey),
            )
        else
          EitherT.liftF(ordinary).flatMap {
            case Some(actual) =>
              actual.authorizeSigning(request, signer, publicKey)
            case None => no
          }
      def canonicalWrite(request: Bytes): Result[IO, ControllerWriteMaterial] =
        no
      def initialHistory(
          raw: Bytes,
          signer: Text,
          publicKey: Bytes,
      ): Result[IO, ControllerInitialHistory] = for
        payload <- lift(original(seedDomain, raw))
        seed    <- core(Seed.codec.decode(payload))
        _       <- check(
          seed.signer == id && signer == id && seed.publicKey == key.publicKey.toBytes && publicKey == seed.publicKey,
          "original complete seed roster changed the actual controller key",
        )
      yield seed.history
      def enforce(
          selected: TransitionIntent,
          promise: FencePromise,
          signer: Text,
          publicKey: Bytes,
      ): Result[IO, Unit] =
        check(
          selected == intent && signer == id && publicKey == key.publicKey.toBytes && promise.context == allowedSource &&
            promise.scope == FenceScope.SourceOrRetiredDomainWritesAndSigning && promise.boundary == height(
              0L,
            ),
          "source fencing request is outside the signed installed migration policy",
        )
      def closeWrites(
          closure: ControllerWriteClosure,
          signer: Text,
          publicKey: Bytes,
      ): Result[IO, Unit] = no

  def bootstrapManifest(
      source: V2RequestConformance.Fixture,
  ): ProtocolManifest =
    source.manifest.copy(families =
      Vector(source.family.copy(maintenanceVerifierDigest = Some(uint(991L)))),
    )

  final class Material(
      val root: Path,
      val sourceController: FenceController,
      val source: V2RequestConformance.Fixture,
      val sourceContext: DomainContext,
      val intent: TransitionIntent,
      val sourceFence: SignedFencePromise,
      val dump: SourceDump,
      val dumpRaw: Bytes,
  ):
    val manifest            = bootstrapManifest(source)
    val context             = value(ProtocolManifest.context(manifest))
    val dumpPath: Path      = root.resolve("original-source.bin")
    val statePayload: Bytes = ByteEncoder[Vector[Long]].encode(dump.state)
    val stateRoot: Hash     = hash(statePayload)
    val sourceInventory: Vector[InventoryEntry] = Vector(
      InventoryEntry(Utf8("neutral-state"), Utf8("cells"), stateRoot),
    )
    val sourceBinding: SourceBinding = SourceBinding(
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
    val sourceRef =
      EvidenceRef(EvidenceKind.Source, sourceBinding.provenanceDigest)
    val fenceRef = EvidenceRef(
      EvidenceKind.Fence,
      value(SignedFencePromise.digest(sourceFence)),
    )
    val baseline: EvidenceBaseline = EvidenceBaseline(
      1L,
      value(TransitionIntent.digest(intent)),
      dump.domain,
      context.chainId,
      dump.checkpoint,
      stateRoot,
      intent.authorityPolicyDigest,
      Vector(sourceRef, fenceRef).sortBy(r =>
        (r.kind.tag, r.digest.bytes.toHex),
      ),
    )
    val policy: TransitionPolicy = TransitionPolicy(
      intent,
      manifest,
      baseline,
      Vector(
        TrustedTransitionAuthority(
          authorityId,
          authority.publicKey.toBytes,
          Set(TransitionAuthorityRole.Bootstrap),
        ),
      ),
      Vector(sourceContext.chainId),
      Vector.empty,
    )
    val bundle: BootstrapBundle = BootstrapBundle(
      1L,
      intent,
      sourceBinding,
      context,
      source.keys.map((id, key) => InitialValidator(id, key.publicKey.toBytes)),
      context.configurationDigest,
      123456L,
      fenceRef.digest,
      SourceAncestry.NoPriorDomain,
      Vector.empty,
      value(EvidenceBaseline.digest(baseline)),
      intent.authorityPolicyDigest,
    )
    val signedBundle: SignedBootstrapBundle = SignedBootstrapBundle(
      bundle,
      envelope(value(BootstrapBundle.signingPreimage(bundle))),
    )
    val repository: TransitionEvidenceRepository[IO] =
      new TransitionEvidenceRepository[IO]:
        def read(ref: EvidenceRef): Result[IO, Bytes] =
          if ref == sourceRef then fileRead(dumpPath)
          else if ref == fenceRef then
            core(SignedFencePromise.codec.encode(Material.this.sourceFence))
          else no
        def baseline(digest: Hash): Result[IO, EvidenceBaseline] =
          if digest == value(EvidenceBaseline.digest(Material.this.baseline))
          then EitherT.pure(Material.this.baseline)
          else no
        def sourceFence(digest: Hash): Result[IO, SignedFencePromise] =
          if digest == fenceRef.digest then
            EitherT.pure(Material.this.sourceFence)
          else no
        def restoreAuthentication(
            evidence: RestoreEvidence,
        ): Result[IO, SignatureEnvelope] = no

    val audit: TransitionAuditAuthentication[IO] =
      new TransitionAuditAuthentication[IO]:
        def artifact(ref: EvidenceRef, raw: Bytes): Result[IO, Unit] =
          if ref == sourceRef then
            lift(original(sourceDomain, raw)).flatMap(_ =>
              check(
                Commitment.hash(sourceDomain, raw) == ref.digest,
                "source original content changed",
              ),
            )
          else if ref == fenceRef then
            core(SignedFencePromise.codec.decode(raw)).flatMap(p =>
              check(
                value(SignedFencePromise.digest(p)) == ref.digest,
                "source fence content changed",
              ),
            )
          else no
        def source(
            binding: SourceBinding,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, SourceSnapshotClosure] = for
          raw <- evidence.read(
            EvidenceRef(EvidenceKind.Source, binding.provenanceDigest),
          )
          payload <- lift(original(sourceDomain, raw))
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
          current <- sourceController.audit
          _       <- check(
            actual == binding && parsed.ancestry == SourceAncestry.NoPriorDomain && parsed.retiredDomains.isEmpty &&
              current.signedFences.contains(
                promise,
              ) && parsed.fenceDigest == value(
                SignedFencePromise.digest(promise),
              ),
            "source dump root/schema/provenance or active pre-snapshot fence changed",
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
            selected: TransitionIntent,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, EnforcedFenceAudit] = for
          current <- sourceController.audit
          _       <- check(
            selected == intent && current.signedFences.contains(
              promise,
            ) && current.enforcements.exists(_.promise == promise.record) &&
              promise.record.context == sourceContext && promise.record.signerId == sourceId,
            "claimed source fence is not present in the actual exclusive live key controller",
          )
          heights = current.possibleSigningIntents
            .filter(_.material.context == sourceContext)
            .flatMap(_.material.height)
        yield EnforcedFenceAudit(
          promise,
          sourceController.publicKey,
          intent.sourceAuthorityScopeDigest,
          heights,
          current.inventory,
        )
        def neverEnabled(
            record: NeverEnabled,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, NeverEnabledAudit] = no
        def absence(
            record: ArchiveAbsence,
            baseline: EvidenceBaseline,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, HistoricalAbsenceAudit] = no
        def presentArchive(
            record: PresentArchive,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, PresentArchiveAudit] = no
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
        faults: JournalFaultInjector[IO] = JournalFaultInjector.none[IO],
        ordinary: DurableJournal[IO] => IO[
          Option[HotStuffControllerAuthentication],
        ] = _ => IO.pure(None),
    ): Resource[IO, Node] =
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
        journal <- FileApplicationJournal.resourceWithFaults(
          root.resolve("validator-" + index.toString).resolve("application"),
          faults,
        )
        initialInstaller <- Resource.eval(
          Ref.of[IO, Option[InitialStateInstaller[IO]]](None),
        )
        controller <- FileFenceController.resource(
          root.resolve("validator-" + index.toString).resolve("controller"),
          key,
          id,
          seed,
          authentication(
            id,
            key,
            intent,
            sourceContext,
            Some(bootstrap -> journal),
            ordinary(journal),
            initialInstaller.get,
          ),
          ControllerCanonicalWriter.unavailable,
        )
        installer <- Resource.eval(
          InitialStateInstaller
            .authenticated(bootstrap, verifier, repository, controller, journal),
        )
        _ <- Resource.eval(initialInstaller.set(Some(installer)))
      yield Node(journal, controller, installer)

  final case class Node(
      journal: DurableJournal[IO],
      controller: FenceController,
      installer: InitialStateInstaller[IO],
  )

  def material(root: Path): Resource[IO, Material] =
    val source = new V2RequestConformance.Fixture(1L, Authority.LockEligible)
    val old    = DomainContext(
      1L,
      Utf8("neutral-source-before-chain"),
      uint(701L),
      1L,
      uint(702L),
    )
    val target = value(ProtocolManifest.context(bootstrapManifest(source)))
    val intent = TransitionIntent(
      1L,
      TransitionKind.InitialBootstrap,
      old.chainId,
      target.chainId,
      target.configurationDigest,
      target.validatorSetHash,
      None,
      uint(703L),
      uint(704L),
      uint(705L),
    )
    val seed = authorize(
      seedDomain,
      value(
        Seed.codec.encode(
          Seed(
            sourceId,
            sourceKey.publicKey.toBytes,
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
    for
      controller <- FileFenceController.resource(
        root.resolve("source-controller"),
        sourceKey,
        sourceId,
        seed,
        authentication(sourceId, sourceKey, intent, old, None),
        ControllerCanonicalWriter.unavailable,
      )
      promise <- Resource.eval(
        accepted(
          controller.prepareFence(
            intent,
            old,
            FenceScope.SourceOrRetiredDomainWritesAndSigning,
            height(0L),
          ),
        ),
      )
      fence <- Resource.eval(accepted(controller.enforce(intent, promise)))
      dump = SourceDump(
        old.chainId,
        uint(706L),
        height(17L),
        intent.sourceSchemaDigest,
        Vector(41L, 7L),
        ReplayPolicyDigest,
        value(SignedFencePromise.digest(fence)),
        SourceAncestry.NoPriorDomain,
        Vector.empty,
      )
      raw    = authorize(sourceDomain, value(SourceDump.codec.encode(dump)))
      result = new Material(
        root,
        controller,
        source,
        old,
        intent,
        fence,
        dump,
        raw,
      )
      _ <- Resource.eval(IO.blocking {
        Files.write(result.dumpPath, raw.toArray); ()
      })
    yield result
