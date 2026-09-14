package org.sigilaris.conformance

import org.sigilaris.node.jvm.runtime.application.v2.*

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*
import org.sigilaris.core.application.protocol.InclusionHeight
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.crypto.{CryptoOps, KeyPair, Signature}
import org.sigilaris.core.datatype.{BigNat, UInt256, Utf8}

/** Real signatures authenticate an independently retained neutral inventory.
  * Unsupported source/consensus audit formats fail explicitly in this fixture;
  * no test claims this neutral inventory describes a production deployment.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object V2TransitionInventoryConformance:
  def cases: Vector[(String, () => IO[Unit])] = new Scenarios().cases
  def run(): IO[Unit]                         = cases.traverse_ { (name, run) =>
    IO.println("V2TransitionInventoryConformance: " + name) *> IO.defer(run())
  }
  private final class Scenarios:
    private val registered =
      scala.collection.mutable.ArrayBuffer.empty[(String, () => IO[Unit])]
    def cases: Vector[(String, () => IO[Unit])] = registered.toVector
    private def scenario(name: String)(run: => IO[Unit]): Unit =
      registered.addOne(name -> (() => run)): Unit
    private def scenarioSync(name: String)(run: => Unit): Unit =
      scenario(name)(IO(run))
    private def assertEquals[A, B](actual: A, expected: B): Unit =
      assert(
        actual == expected,
        "expected " + expected.toString + "; actual " + actual.toString,
      )
    private def fail(message: String): Nothing = throw new AssertionError(
      message,
    )
    private def hash(v: Int): Hash = UInt256.unsafeFromBigIntUnsigned(BigInt(v))
    private def height(v: Long): Height = InclusionHeight(
      BigNat.unsafeFromLong(v),
    )
    private def core[A](value: Either[CoreFailure, A]): A =
      value.fold(e => fail(e.message), identity)
    private def accepted[A](result: Result[IO, A]): IO[A] =
      result.value.flatMap(
        _.fold(e => IO.raiseError(new AssertionError(e.message)), IO.pure),
      )
    private def rejected[A](result: Result[IO, A]): IO[Unit] =
      result.value.flatMap(v => IO(assert(v.isLeft)))
    private def absent[A]: Result[IO, A] = EitherT.leftT[IO, A](
      V2RuntimeFailure.at(
        RuntimeFailureCode.ProofUnavailable,
        "this fixture has no authenticated proof for the requested format",
      ),
    )
    private val authority    = CryptoOps.fromPrivate(BigInt(101))
    private val other        = CryptoOps.fromPrivate(BigInt(102))
    private val id           = Utf8("inventory-authority")
    private val sourceDomain = Utf8("historical-neutral")
    private val family       = InputManifest(
      2L,
      Utf8("neutral-family"),
      1L,
      Vector(
        FieldManifest(
          Utf8("field"),
          FieldRole.ExactMutate,
          hash(20),
          Some(hash(21)),
        ),
      ),
      hash(22),
      hash(23),
      hash(24),
      Some(hash(25)),
    )
    private val manifest = ProtocolManifest(
      2L,
      2L,
      Utf8("fresh-neutral"),
      1L,
      hash(1),
      10L,
      SubstrateVersions.V2,
      ProtocolLimits.V2,
      Vector(family),
      Vector.empty,
    )
    private val target = core(ProtocolManifest.context(manifest))
    private val old    = DomainContext(1L, sourceDomain, hash(2), 1L, hash(3))
    private val intent = TransitionIntent(
      1L,
      TransitionKind.InitialBootstrap,
      sourceDomain,
      target.chainId,
      target.configurationDigest,
      target.validatorSetHash,
      None,
      hash(4),
      hash(5),
      hash(6),
    )
    private val baseline = EvidenceBaseline(
      1L,
      core(TransitionIntent.digest(intent)),
      sourceDomain,
      target.chainId,
      hash(7),
      hash(8),
      hash(6),
      Vector.empty,
    )
    private val policy = TransitionPolicy(
      intent,
      manifest,
      baseline,
      Vector(
        TrustedTransitionAuthority(
          id,
          authority.publicKey.toBytes,
          Set(TransitionAuthorityRole.NeverEnabled),
        ),
      ),
      Vector(sourceDomain),
      Vector(old),
    )
    private def sign(key: KeyPair, preimage: Bytes): SignatureEnvelope =
      val sig =
        CryptoOps.sign(key, CryptoOps.keccak256(preimage.toArray)).toOption.get
      SignatureEnvelope(
        id,
        1.toByte,
        key.publicKey.toBytes,
        ByteEncoder[Long].encode(sig.v.toLong) ++ sig.r.bytes ++ sig.s.bytes,
      )
    private val artifactDomain = Utf8(
      "neutral.transition.inventory-attestation.v1",
    )
    private final case class Roster(
        domain: Text,
        start: Long,
        end: Long,
        signerId: Text,
        deploymentId: Text,
        binaryDigest: Hash,
        configDigest: Hash,
        capability: Byte,
    )
    private object Roster:
      import V2Codecs.given
      given ByteEncoder[Roster] = ByteEncoder.derived
      given ByteDecoder[Roster] = ByteDecoder.derived
      val codec                 =
        CanonicalCodec.derived[Roster](_ => Right[CoreFailure, Unit](()))
    private val roster = Roster(
      sourceDomain,
      1L,
      21L,
      Utf8("historical-key"),
      Utf8("deployment"),
      hash(11),
      hash(12),
      1.toByte,
    )
    private val rosterBytes     = core(Roster.codec.encode(roster))
    private val rosterSignature =
      sign(authority, Commitment.preimage(artifactDomain, rosterBytes))
    private val rosterDigest    = Commitment.hash(artifactDomain, rosterBytes)
    private val reportReference = EvidenceRef(EvidenceKind.KeyUse, rosterDigest)
    private val deployment      = DeploymentInterval(
      roster.deploymentId,
      1L,
      21L,
      roster.binaryDigest,
      roster.configDigest,
      Vector(roster.signerId),
      IssuanceCapability.Unavailable,
      IssuanceCapability.Unavailable,
      hash(13),
    )
    private val path = SigningPath(
      Utf8("neutral-original-binary"),
      roster.binaryDigest,
      roster.configDigest,
      IssuanceCapability.Unavailable,
      IssuanceCapability.Unavailable,
    )
    private val record = NeverEnabled(
      1L,
      sourceDomain,
      1L,
      21L,
      rosterDigest,
      Vector(deployment),
      Vector(
        KeyUseInterval(roster.signerId, 1L, 21L, Vector(path), rosterDigest),
      ),
      Vector(reportReference),
      id,
    )
    private def signed(
        r: NeverEnabled,
        key: KeyPair = authority,
    ): SignedNeverEnabled =
      SignedNeverEnabled(r, sign(key, core(NeverEnabled.signingPreimage(r))))

    private def verifier(
        surviving: Vector[SurvivingApplicationInventory] = Vector.empty,
        missing: Boolean = false,
        scope: Roster = roster,
        auditDeployments: Option[Vector[ScopedInterval]] = None,
        auditSigners: Option[Vector[ScopedInterval]] = None,
    ): TransitionEvidenceVerifier[IO] =
      val repository = new TransitionEvidenceRepository[IO]:
        def read(ref: EvidenceRef): Result[IO, Bytes] =
          if !missing && ref == reportReference then EitherT.pure(rosterBytes)
          else absent
        def baseline(digest: Hash): Result[IO, EvidenceBaseline] =
          if digest == core(
              EvidenceBaseline.digest(
                Scenarios.this.baseline,
              ),
            )
          then EitherT.pure(Scenarios.this.baseline)
          else absent
        def sourceFence(digest: Hash): Result[IO, SignedFencePromise] = absent
        def restoreAuthentication(
            evidence: RestoreEvidence,
        ): Result[IO, SignatureEnvelope] = absent
      val auth = new TransitionAuditAuthentication[IO]:
        def artifact(ref: EvidenceRef, bytes: Bytes): Result[IO, Unit] =
          EitherT.cond[IO](
            ref == reportReference && Commitment
              .hash(artifactDomain, bytes) == ref.digest,
            (),
            V2RuntimeFailure.at(
              RuntimeFailureCode.CommitmentMismatch,
              "original signed roster bytes changed",
            ),
          )
        def neverEnabled(
            r: NeverEnabled,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, NeverEnabledAudit] =
          for
            bytes <- evidence.read(
              EvidenceRef(EvidenceKind.KeyUse, r.authorizedSignerInventory),
            )
            _       <- artifact(reportReference, bytes)
            decoded <- EitherT.fromEither[IO](
              Roster.codec.decode(bytes).left.map(V2RuntimeFailure.fromCore),
            )
            raw = rosterSignature.signature
            sig = Signature(
              BigInt(1, raw.take(8L).toArray).toInt,
              UInt256.unsafeFromBytesBE(raw.slice(8L, 40L)),
              UInt256.unsafeFromBytesBE(raw.drop(40L)),
            )
            key <- EitherT.fromEither[IO](
              CryptoOps
                .recover(
                  sig,
                  CryptoOps.keccak256(
                    Commitment.preimage(artifactDomain, bytes).toArray,
                  ),
                )
                .left
                .map(e =>
                  V2RuntimeFailure
                    .at(RuntimeFailureCode.InvalidSignature, e.msg),
                ),
            )
            _ <- EitherT.cond[IO](
              key.toBytes == authority.publicKey.toBytes && decoded == roster && r.deployments
                .forall(d =>
                  d.binaryDigest == decoded.binaryDigest && d.effectiveConfigDigest == decoded.configDigest,
                ) && r.keyUses.forall(
                _.usePaths.forall(p =>
                  p.binaryDigest == decoded.binaryDigest && p.configurationDigest == decoded.configDigest,
                ),
              ),
              (),
              V2RuntimeFailure.at(
                RuntimeFailureCode.EvidenceContradictory,
                "unaccounted binary/configuration/key integration",
              ),
            )
          yield NeverEnabledAudit(
            scope.domain,
            scope.start,
            scope.end,
            rosterDigest,
            auditSigners.getOrElse(
              Vector(ScopedInterval(scope.signerId, scope.start, scope.end)),
            ),
            auditDeployments.getOrElse(
              Vector(ScopedInterval(scope.deploymentId, scope.start, scope.end)),
            ),
            surviving,
          )
        def source(
            binding: SourceBinding,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, SourceSnapshotClosure] = absent
        def absence(
            record: ArchiveAbsence,
            baseline: EvidenceBaseline,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, HistoricalAbsenceAudit] = absent
        def presentArchive(
            record: PresentArchive,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, PresentArchiveAudit] = absent
        def fence(
            promise: SignedFencePromise,
            intent: TransitionIntent,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, EnforcedFenceAudit] = absent
        def drain(
            record: DrainEvidence,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, DrainAudit] = absent
        def continuation(
            record: HandoverEvidence,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, ContinuationAudit] = absent
        def restore(
            record: RestoreEvidence,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, RestoreAudit] = absent
        def restoreFence(
            promise: SignedFencePromise,
            restore: RestoreEvidence,
            policy: TransitionPolicy,
            evidence: TransitionEvidenceRepository[IO],
        ): Result[IO, EnforcedFenceAudit] = absent
      TransitionEvidenceVerifier
        .authenticated(policy, repository, auth)
        .fold(e => fail(e.message), identity)

    scenario(
      "real signed independently retained inventory establishes a no-store never-enabled route",
    ) {
      accepted(verifier().verifyNeverEnabled(signed(record))).map(v =>
        assertEquals(v.digest, core(SignedNeverEnabled.digest(signed(record)))),
      )
    }
    scenario(
      "valid foreign signature and tampered signed content cannot claim local audit authority",
    ) {
      val original = signed(record)
      rejected(
        verifier().verifyNeverEnabled(signed(record, other)),
      ) *> rejected(
        verifier().verifyNeverEnabled(
          original.copy(record = record.copy(lifetimeEnd = 22L)),
        ),
      )
    }
    scenario(
      "complete independently signed scope rejects omitted key-use and deployment intervals",
    ) {
      val missingKey =
        record.copy(keyUses = Vector(record.keyUses.head.copy(end = 20L)))
      val missingDeployment =
        record.copy(deployments = Vector(deployment.copy(start = 2L)))
      rejected(verifier().verifyNeverEnabled(signed(missingKey))) *> rejected(
        verifier().verifyNeverEnabled(signed(missingDeployment)),
      )
    }
    scenario(
      "key configuration changes can split a fully covered deployment lifetime",
    ) {
      val split = record.copy(keyUses =
        Vector(
          record.keyUses.head.copy(end = 11L),
          record.keyUses.head.copy(start = 11L),
        ),
      )
      accepted(verifier().verifyNeverEnabled(signed(split))).void
    }
    scenario(
      "authenticated adapter scopes must themselves cover both complete lifetimes",
    ) {
      // Even a trusted adapter's partial scope must not make coverage vacuous.
      val gaps = Vector(
        Vector.empty[(Long, Long)],
        Vector(2L -> 21L),
        Vector(1L -> 20L),
        Vector(1L -> 10L, 11L -> 21L),
      )
      gaps.traverse_ { intervals =>
        val deployments = intervals.map((start, end) =>
          deployment.copy(start = start, end = end),
        )
        val keys = intervals.map((start, end) =>
          record.keyUses.head.copy(start = start, end = end),
        )
        val deploymentCheck = verifier(
          auditDeployments = Some(
            deployments.map(d => ScopedInterval(d.deploymentId, d.start, d.end)),
          ),
        ).verifyNeverEnabled(signed(record.copy(deployments = deployments)))
        val signerCheck = verifier(
          auditSigners =
            Some(keys.map(k => ScopedInterval(k.signerKeyId, k.start, k.end))),
        ).verifyNeverEnabled(
          signed(
            record.copy(
              deployments =
                Vector(deployment.copy(signerKeyIds = Vector.empty)),
              keyUses = keys,
            ),
          ),
        )
        Vector(deploymentCheck, signerCheck).traverse_(
          _.value.flatMap(result =>
            IO {
              assert(
                result.left.exists(error =>
                  error.code == RuntimeFailureCode.EvidenceContradictory &&
                    error.detail == "authenticated audit scope does not cover the complete attested lifetime",
                ),
              )
            },
          ),
        )
      }
    }
    scenario(
      "lifetime coverage permits overlapping distinct deployments and keys",
    ) {
      val secondKey   = Utf8("historical-key-2")
      val deployments = Vector(
        deployment.copy(end = 14L),
        deployment.copy(
          deploymentId = Utf8("deployment-2"),
          start = 9L,
          signerKeyIds = Vector(secondKey),
        ),
      )
      val keys = Vector(
        record.keyUses.head.copy(end = 14L),
        record.keyUses.head.copy(signerKeyId = secondKey, start = 9L),
      )
      accepted(
        verifier(
          auditDeployments = Some(
            deployments.map(d => ScopedInterval(d.deploymentId, d.start, d.end)),
          ),
          auditSigners =
            Some(keys.map(k => ScopedInterval(k.signerKeyId, k.start, k.end))),
        ).verifyNeverEnabled(
          signed(record.copy(deployments = deployments, keyUses = keys)),
        ),
      ).void
    }
    scenario(
      "enabled unknown or unaccounted custom signing paths reject never-enabled eligibility",
    ) {
      val enabled = record.copy(deployments =
        Vector(deployment.copy(lockIssuance = IssuanceCapability.Enabled)),
      )
      val unknown = record.copy(keyUses =
        Vector(
          record.keyUses.head.copy(usePaths =
            Vector(path.copy(effectIssuance = IssuanceCapability.Unknown)),
          ),
        ),
      )
      val custom = record.copy(keyUses =
        Vector(
          record.keyUses.head
            .copy(usePaths = Vector(path.copy(binaryDigest = hash(99)))),
        ),
      )
      rejected(verifier().verifyNeverEnabled(signed(enabled))) *> rejected(
        verifier().verifyNeverEnabled(signed(unknown)),
      ) *> rejected(verifier().verifyNeverEnabled(signed(custom)))
    }
    scenario(
      "missing original inventory and contradictory surviving subjects fail closed",
    ) {
      val store = SurvivingApplicationInventory(
        old,
        hash(77),
        Vector(hash(78)),
        Vector.empty,
        Vector.empty,
        Vector.empty,
      )
      rejected(
        verifier(missing = true).verifyNeverEnabled(signed(record)),
      ) *> rejected(
        verifier(surviving = Vector(store)).verifyNeverEnabled(signed(record)),
      )
    }
    scenarioSync(
      "canonical auxiliary inventories reject same-key replacement and preserve fence list identity",
    ) {
      val entries = Vector(
        InventoryEntry(Utf8("a"), Utf8("key"), hash(1)),
        InventoryEntry(Utf8("aa"), Utf8("key"), hash(2)),
      )
      assertEquals(
        core(
          TransitionInventory.decode(core(TransitionInventory.encode(entries))),
        ),
        entries,
      )
      assert(TransitionInventory.encode(entries.reverse).isLeft)
      assert(
        TransitionInventory
          .encode(entries :+ entries.head.copy(digest = hash(3)))
          .isLeft,
      )
      val promise = FencePromise(
        1L,
        old,
        id,
        FenceScope.SourceOrRetiredDomainWritesAndSigning,
        height(9),
        None,
        hash(4),
        core(TransitionIntent.digest(intent)),
      )
      val signedPromise = SignedFencePromise(
        promise,
        sign(authority, core(FencePromise.signingPreimage(promise))),
      )
      assertEquals(
        core(
          TransitionFenceInventory.decode(
            core(TransitionFenceInventory.encode(Vector(signedPromise))),
          ),
        ),
        Vector(signedPromise),
      )
      assert(
        TransitionFenceInventory
          .encode(Vector(signedPromise, signedPromise))
          .isLeft,
      )
    }
