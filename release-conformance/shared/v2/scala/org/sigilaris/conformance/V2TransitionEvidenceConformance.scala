package org.sigilaris.conformance

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.InclusionHeight
import scodec.bits.ByteVector
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.{Utf8, UInt256, BigNat}

/** Public source-only codec/commitment contract. This fixture has real neutral
  * keys but makes no deployment, source-state, quorum or active-fence claim.
  * The public JVM transition runtime fixtures authenticate those original
  * facts.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Throw",
    "org.wartremover.warts.Any",
  ),
)
object V2TransitionEvidenceConformance:
  private def hash(value: Long): Hash =
    UInt256.unsafeFromBigIntUnsigned(BigInt(value))
  private def height(value: Long): Height = InclusionHeight(
    BigNat.unsafeFromLong(value),
  )
  private val keys = Vector(1, 2, 3, 4).map(index =>
    Utf8("validator-" + index.toString) -> CryptoOps.fromPrivate(BigInt(index)),
  )
  private val context =
    DomainContext(2L, Utf8("neutral-transition"), hash(19), 1L, hash(20))
  private def assertEquals[A](actual: A, expected: A): Unit = assert(
    actual == expected,
  )
  private def assertNotEquals[A](actual: A, expected: A): Unit = assert(
    actual != expected,
  )
  private def core[A](value: Either[CoreFailure, A]): A =
    value.fold(e => throw new AssertionError(e.message), identity)
  private val source = SourceBinding(
    1L,
    Utf8("old-source"),
    hash(101),
    height(100),
    hash(102),
    hash(103),
    hash(104),
    hash(105),
    hash(106),
  )
  private val intent = TransitionIntent(
    1L,
    TransitionKind.InitialBootstrap,
    source.domain,
    context.chainId,
    context.configurationDigest,
    context.validatorSetHash,
    None,
    hash(107),
    source.stateSchemaDigest,
    hash(108),
  )
  private val baseline = EvidenceBaseline(
    1L,
    core(TransitionIntent.digest(intent)),
    source.domain,
    context.chainId,
    source.checkpointId,
    source.stateRoot,
    intent.authorityPolicyDigest,
    Vector.empty,
  )
  private def signed(preimage: Bytes): SignatureEnvelope =
    val key = keys.headOption.get._2
    val sig =
      CryptoOps.sign(key, CryptoOps.keccak256(preimage.toArray)).toOption.get
    SignatureEnvelope(
      keys.headOption.get._1,
      1.toByte,
      key.publicKey.toBytes,
      ByteEncoder[Long].encode(sig.v.toLong) ++ sig.r.bytes ++ sig.s.bytes,
    )
  private val deployment = DeploymentInterval(
    Utf8("deployed"),
    1L,
    20L,
    hash(201),
    hash(202),
    Vector(Utf8("key")),
    IssuanceCapability.Unavailable,
    IssuanceCapability.ContinuouslyDisabled,
    hash(203),
  )
  private val path = SigningPath(
    Utf8("path"),
    hash(201),
    hash(202),
    IssuanceCapability.Unavailable,
    IssuanceCapability.Unavailable,
  )
  private val never = NeverEnabled(
    1L,
    source.domain,
    1L,
    20L,
    hash(204),
    Vector(deployment),
    Vector(KeyUseInterval(Utf8("key"), 1L, 20L, Vector(path), hash(205))),
    Vector.empty,
    keys.headOption.get._1,
  )
  private val signedNever =
    SignedNeverEnabled(never, signed(core(NeverEnabled.signingPreimage(never))))
  private val bundle = BootstrapBundle(
    1L,
    intent,
    source,
    context,
    keys.map { case (id, key) => InitialValidator(id, key.publicKey.toBytes) },
    context.configurationDigest,
    42L,
    hash(301),
    SourceAncestry.NoPriorDomain,
    Vector.empty,
    core(EvidenceBaseline.digest(baseline)),
    intent.authorityPolicyDigest,
  )

  def sourceAndIntent(): Unit =
    val bytes = core(SourceBinding.codec.encode(source))
    assertEquals(core(SourceBinding.codec.decode(bytes)), source)
    assert(SourceBinding.codec.decode(bytes ++ ByteVector(0)).isLeft)
    assertEquals(
      core(
        TransitionIntent.codec.decode(
          core(TransitionIntent.codec.encode(intent)),
        ),
      ),
      intent,
    )
    assert(
      TransitionIntent.codec
        .encode(intent.copy(futureBoundary = Some(height(9))))
        .isLeft,
    )
    assert(
      TransitionIntent.codec
        .encode(intent.copy(targetChain = source.domain))
        .isLeft,
    )
  def signedDomainSeparation(): Unit =
    val record  = core(NeverEnabled.codec.encode(never))
    val wrapper = core(SignedNeverEnabled.codec.encode(signedNever))
    assertEquals(
      core(NeverEnabled.signingPreimage(never)),
      Commitment.preimage(NeverEnabled.Domain, ByteVector(0) ++ record),
    )
    assertEquals(
      core(SignedNeverEnabled.digest(signedNever)),
      Commitment.hash(NeverEnabled.Domain, ByteVector(1) ++ wrapper),
    )
    assertNotEquals(
      Commitment.hash(NeverEnabled.Domain, record),
      core(SignedNeverEnabled.digest(signedNever)),
    )
    assertEquals(core(SignedNeverEnabled.codec.decode(wrapper)), signedNever)
  def baselineOrdering(): Unit =
    val ref    = EvidenceRef(EvidenceKind.PresentContent, hash(1))
    val other  = EvidenceRef(EvidenceKind.HistoricalAbsence, hash(1))
    val chosen = baseline.copy(entries = Vector(ref, other))
    assertEquals(
      core(
        EvidenceBaseline.codec.decode(
          core(EvidenceBaseline.codec.encode(chosen)),
        ),
      ),
      chosen,
    )
    assert(
      EvidenceBaseline.codec
        .encode(chosen.copy(entries = Vector(other, ref)))
        .isLeft,
    )
    assert(
      EvidenceBaseline.codec
        .encode(chosen.copy(entries = Vector(ref, ref)))
        .isLeft,
    )
  def bootstrapValidatorOrdering(): Unit =
    assert(BootstrapBundle.codec.encode(bundle).isRight)
    val reordered = bundle.copy(validators = bundle.validators.reverse)
    assert(BootstrapBundle.codec.encode(reordered).isRight)
    assertNotEquals(
      core(BootstrapBundle.digest(bundle)),
      core(BootstrapBundle.digest(reordered)),
    )
    assert(
      BootstrapBundle.codec
        .encode(
          bundle.copy(validators =
            bundle.validators :+ bundle.validators.headOption.get,
          ),
        )
        .isLeft,
    )
    assert(
      BootstrapBundle.codec
        .encode(bundle.copy(targetManifestDigest = hash(9)))
        .isLeft,
    )
    assert(
      BootstrapBundle.codec
        .encode(bundle.copy(genesisTimestampMillis = -1L))
        .isLeft,
    )
  def retiredAncestry(): Unit =
    val ref = EvidenceRef(EvidenceKind.HistoricalAbsence, hash(90))
    assert(
      BootstrapBundle.codec
        .encode(bundle.copy(retiredEvidence = Vector(ref)))
        .isLeft,
    )
    assert(
      BootstrapBundle.codec
        .encode(
          bundle.copy(ancestryKind = SourceAncestry.RetiredNonAncestorDomains),
        )
        .isLeft,
    )
    assert(
      BootstrapBundle.codec
        .encode(
          bundle.copy(
            ancestryKind = SourceAncestry.RetiredNonAncestorDomains,
            retiredEvidence = Vector(ref),
          ),
        )
        .isRight,
    )
  def intervalShapes(): Unit =
    assert(NeverEnabled.codec.encode(never.copy(lifetimeEnd = 1L)).isLeft)
    assert(
      NeverEnabled.codec
        .encode(never.copy(keyUses = never.keyUses ++ never.keyUses))
        .isLeft,
    )
    assert(
      NeverEnabled.codec
        .encode(
          never.copy(deployments =
            Vector(deployment.copy(lockIssuance = IssuanceCapability.Unknown)),
          ),
        )
        .isRight,
    )
    assert(
      DeploymentInterval.codec
        .encode(deployment.copy(signerKeyIds = Vector(Utf8("z"), Utf8("a"))))
        .isLeft,
    )
  def drainShapes(): Unit =
    val old = context.copy(chainId = source.domain, protocolVersion = 1L)
    val d   = DrainEvidence(
      1L,
      core(TransitionIntent.digest(intent)),
      old,
      DrainKind.NeverEnabled,
      hash(401),
      None,
      None,
      None,
      0L,
      core(SignedNeverEnabled.digest(signedNever)),
      None,
      None,
    )
    assert(DrainEvidence.codec.encode(d).isRight)
    assert(
      DrainEvidence.codec
        .encode(d.copy(baseUpperBound = Some(height(9))))
        .isLeft,
    )
    assert(
      DrainEvidence.codec
        .encode(d.copy(finalizedEvidenceDigest = Some(hash(3))))
        .isLeft,
    )
    assert(
      DrainEvidence.codec
        .encode(d.copy(kind = DrainKind.InferredHorizon))
        .isLeft,
    )
    assert(
      DrainEvidence.codec.encode(d.copy(kind = DrainKind.JournalCovered)).isLeft,
    )
  def handoverBoundary(): Unit =
    val old =
      context.copy(protocolVersion = 1L, configurationDigest = hash(500))
    val i = intent.copy(
      kind = TransitionKind.Handover,
      sourceDomain = context.chainId,
      futureBoundary = Some(height(12)),
    )
    val suffix = Vector(
      CertifiedSuffixEntry(
        height(11),
        hash(502),
        hash(501),
        hash(503),
        hash(504),
        hash(505),
        hash(506),
      ),
    )
    val h = HandoverEvidence(
      1L,
      i,
      old,
      context,
      hash(501),
      height(10),
      hash(507),
      hash(508),
      height(12),
      hash(502),
      hash(509),
      suffix,
      Vector.empty,
      Vector.empty,
      hash(510),
      context.configurationDigest,
      hash(511),
    )
    assert(HandoverEvidence.codec.encode(h).isRight)
    assert(
      HandoverEvidence.codec.encode(h.copy(boundaryHeight = height(11))).isLeft,
    )
    assert(
      HandoverEvidence.codec
        .encode(
          h.copy(retainedSuffix =
            Vector(suffix.headOption.get.copy(parentBlockId = hash(99))),
          ),
        )
        .isLeft,
    )
    assert(
      HandoverEvidence.codec
        .encode(h.copy(retainedSuffix = Vector.empty))
        .isLeft,
    )

  private val oldContext =
    context.copy(chainId = source.domain, protocolVersion = 1L)
  private val neverDrain = DrainEvidence(
    1L,
    core(TransitionIntent.digest(intent)),
    oldContext,
    DrainKind.NeverEnabled,
    hash(401),
    None,
    None,
    None,
    0L,
    core(SignedNeverEnabled.digest(signedNever)),
    None,
    None,
  )
  private val journalDrain = neverDrain.copy(
    kind = DrainKind.JournalCovered,
    reconciledInventoryDigest = Some(hash(402)),
    greatestRecordedDeadline = Some(height(8)),
    oldMaximumLifetime = 64L,
    coverageProofDigest = hash(403),
    finalizedEvidenceDigest = Some(hash(404)),
    zeroLiveEvidenceDigest = Some(hash(405)),
  )
  private val inferredDrain = journalDrain.copy(
    kind = DrainKind.InferredHorizon,
    baseUpperBound = Some(height(9)),
  )
  private val archive = PresentArchive(
    1L,
    Utf8("retired-domain"),
    hash(601),
    core(SourceBinding.digest(source)),
    context.chainId,
    hash(602),
  )
  private val absence = ArchiveAbsence(
    1L,
    Utf8("retired-domain"),
    core(SourceBinding.digest(source)),
    context.chainId,
    Vector(Utf8("applications"), Utf8("consensus")),
    Utf8("not retained at source retirement"),
    Some(1L),
    Some(20L),
    Vector(Utf8("initial-installation")),
    Vector(
      EvidenceRef(
        EvidenceKind.NeverEnabled,
        core(SignedNeverEnabled.digest(signedNever)),
      ),
    ),
    hash(603),
    hash(604),
    core(SignedNeverEnabled.digest(signedNever)),
    keys.headOption.get._1,
  )
  private val handoverIntent = intent.copy(
    kind = TransitionKind.Handover,
    sourceDomain = context.chainId,
    futureBoundary = Some(height(12)),
  )
  private val handover = HandoverEvidence(
    1L,
    handoverIntent,
    context.copy(protocolVersion = 1L, configurationDigest = hash(500)),
    context,
    hash(501),
    height(10),
    hash(507),
    hash(508),
    height(12),
    hash(502),
    hash(509),
    Vector(
      CertifiedSuffixEntry(
        height(11),
        hash(502),
        hash(501),
        hash(503),
        hash(504),
        hash(505),
        hash(506),
      ),
    ),
    Vector.empty,
    Vector.empty,
    hash(510),
    context.configurationDigest,
    hash(511),
  )
  private val fence = FencePromise(
    1L,
    oldContext,
    keys.headOption.get._1,
    FenceScope.ConsensusProfileAtOrAbove,
    height(12),
    Some(height(11)),
    hash(701),
    core(TransitionIntent.digest(handoverIntent)),
  )
  private val activation = ActivationPreparation(
    2L,
    core(HandoverEvidence.digest(handover)),
    core(EvidenceBaseline.digest(baseline)),
    hash(702),
    Vector(PreparedNamespace(Utf8("application"), 2L, hash(703), hash(704))),
    handover.continuationParentId,
    handover.boundaryHeight,
    context.configurationDigest,
    hash(705),
  )
  private val decision = ActivationDecision(
    2L,
    core(ActivationPreparation.digest(activation)),
    7L,
    activation.parentBlockId,
    activation.firstHeight,
    activation.targetManifestDigest,
    activation.retainedSafetyInventoryDigest,
  )
  private val restore = RestoreEvidence(
    1L,
    hash(702),
    hash(706),
    hash(707),
    hash(708),
    hash(709),
    keys.headOption.get._1,
  )
  private val startup = BootstrapStartupRecord(
    2L,
    core(BootstrapBundle.digest(bundle)),
    hash(710),
    hash(711),
    hash(712),
    bundle.evidenceBaselineDigest,
    BootstrapPhase.Bound,
    Vector.empty,
  )

  /** Public golden surface: hashes below are raw 32-byte digest values; the
    * `*-preimage` rows are the exact bytes signed or hashed, including framing.
    * Every record also rejects trailing input through the public strict codec.
    */
  def canonicalVectors(): Vector[(String, Bytes)] =
    def record[A](
        name: String,
        codec: CanonicalCodec[A],
        value: A,
    ): (String, Bytes) =
      val encoded = core(codec.encode(value))
      assertEquals(core(codec.decode(encoded)), value)
      assert(codec.decode(encoded ++ ByteVector(0)).isLeft)
      name -> encoded
    def framed(
        name: String,
        domain: Text,
        payload: Bytes,
        prefix: Option[Byte],
    ): Vector[(String, Bytes)] =
      val body = prefix.fold(payload)(tag => ByteVector(tag) ++ payload)
      Vector(
        (name + "-preimage") -> Commitment.preimage(domain, body),
        (name + "-hash")     -> Commitment.hash(domain, body).bytes,
      )
    val ordinary = Vector(
      (
        "source",
        SourceBinding.Domain,
        record("source", SourceBinding.codec, source),
      ),
      (
        "transition-intent",
        TransitionIntent.Domain,
        record("transition-intent", TransitionIntent.codec, intent),
      ),
      (
        "baseline",
        EvidenceBaseline.Domain,
        record("baseline", EvidenceBaseline.codec, baseline),
      ),
      (
        "archive-present",
        PresentArchive.Domain,
        record("archive-present", PresentArchive.codec, archive),
      ),
      (
        "archive-absence",
        ArchiveAbsence.Domain,
        record("archive-absence", ArchiveAbsence.codec, absence),
      ),
      (
        "never-enabled",
        NeverEnabled.Domain,
        record("never-enabled", NeverEnabled.codec, never),
      ),
      (
        "drain-never",
        DrainEvidence.Domain,
        record("drain-never", DrainEvidence.codec, neverDrain),
      ),
      (
        "drain-journal",
        DrainEvidence.Domain,
        record("drain-journal", DrainEvidence.codec, journalDrain),
      ),
      (
        "drain-inferred",
        DrainEvidence.Domain,
        record("drain-inferred", DrainEvidence.codec, inferredDrain),
      ),
      (
        "bootstrap",
        BootstrapBundle.Domain,
        record("bootstrap", BootstrapBundle.codec, bundle),
      ),
      (
        "bootstrap-subject",
        BootstrapSubject.Domain,
        record(
          "bootstrap-subject",
          BootstrapSubject.codec,
          BootstrapSubject(1L, core(BootstrapBundle.digest(bundle)), hash(710)),
        ),
      ),
      (
        "handover",
        HandoverEvidence.Domain,
        record("handover", HandoverEvidence.codec, handover),
      ),
      (
        "restore",
        RestoreEvidence.Domain,
        record("restore", RestoreEvidence.codec, restore),
      ),
    )
    val digests = Map(
      "source"            -> core(SourceBinding.digest(source)).bytes,
      "transition-intent" -> core(TransitionIntent.digest(intent)).bytes,
      "baseline"          -> core(EvidenceBaseline.digest(baseline)).bytes,
      "archive-present"   -> core(PresentArchive.digest(archive)).bytes,
      "archive-absence"   -> core(ArchiveAbsence.digest(absence)).bytes,
      "never-enabled"     -> core(NeverEnabled.digest(never)).bytes,
      "drain-never"       -> core(DrainEvidence.digest(neverDrain)).bytes,
      "drain-journal"     -> core(DrainEvidence.digest(journalDrain)).bytes,
      "drain-inferred"    -> core(DrainEvidence.digest(inferredDrain)).bytes,
      "bootstrap"         -> core(BootstrapBundle.digest(bundle)).bytes,
      "bootstrap-subject" -> core(
        BootstrapSubject.digest(
          BootstrapSubject(1L, core(BootstrapBundle.digest(bundle)), hash(710)),
        ),
      ).bytes,
      "handover" -> core(HandoverEvidence.digest(handover)).bytes,
      "restore"  -> core(RestoreEvidence.digest(restore)).bytes,
    )
    val tuples = ordinary.flatMap((name, domain, bytes) =>
      Vector(
        bytes,
        (name + "-preimage") -> Commitment.preimage(domain, bytes._2),
        (name + "-hash")     -> digests(name),
      ),
    )
    val unsigned = Vector(
      "never-enabled-signing-preimage" -> core(
        NeverEnabled.signingPreimage(never),
      ),
      "archive-absence-signing-preimage" -> core(
        ArchiveAbsence.signingPreimage(absence),
      ),
      "bootstrap-signing-preimage" -> core(
        BootstrapBundle.signingPreimage(bundle),
      ),
      "restore-signing-preimage" -> core(
        RestoreEvidence.signingPreimage(restore),
      ),
      "fence-signing-preimage" -> core(FencePromise.signingPreimage(fence)),
    )
    val activationBytes =
      record("activation-prepare", ActivationPreparation.codec, activation)
    val decisionBytes =
      record("activation-decision", ActivationDecision.codec, decision)
    assertEquals(
      core(SourceBinding.digest(source)).bytes,
      Commitment
        .hash(SourceBinding.Domain, core(SourceBinding.codec.encode(source)))
        .bytes,
    )
    assertEquals(
      core(ArchiveAbsence.digest(absence)).bytes,
      Commitment
        .hash(ArchiveAbsence.Domain, core(ArchiveAbsence.codec.encode(absence)))
        .bytes,
    )
    assertEquals(
      core(DrainEvidence.digest(journalDrain)).bytes,
      Commitment
        .hash(
          DrainEvidence.Domain,
          core(DrainEvidence.codec.encode(journalDrain)),
        )
        .bytes,
    )
    assertEquals(
      core(RestoreEvidence.digest(restore)).bytes,
      Commitment
        .hash(
          RestoreEvidence.Domain,
          core(RestoreEvidence.codec.encode(restore)),
        )
        .bytes,
    )
    assertEquals(
      core(ActivationPreparation.digest(activation)).bytes,
      framed(
        "prepare",
        ActivationPreparation.Domain,
        activationBytes._2,
        Some(1.toByte),
      ).lastOption.get._2,
    )
    assertEquals(
      core(ActivationDecision.digest(decision)).bytes,
      framed(
        "decision",
        ActivationPreparation.Domain,
        decisionBytes._2,
        Some(2.toByte),
      ).lastOption.get._2,
    )
    tuples ++ unsigned ++ Vector(
      record("fence", FencePromise.codec, fence),
      activationBytes,
      decisionBytes,
    ) ++
      framed(
        "activation-prepare",
        ActivationPreparation.Domain,
        activationBytes._2,
        Some(1.toByte),
      ) ++
      framed(
        "activation-decision",
        ActivationPreparation.Domain,
        decisionBytes._2,
        Some(2.toByte),
      ) ++
      Vector(
        record("bootstrap-bound", BootstrapStartupRecord.codec, startup),
        record(
          "bootstrap-installed",
          BootstrapStartupRecord.codec,
          startup.copy(phase = BootstrapPhase.Installed),
        ),
        record(
          "bootstrap-signing",
          BootstrapStartupRecord.codec,
          startup.copy(
            phase = BootstrapPhase.Signing,
            issuedVoteIntents = Vector(hash(713)),
          ),
        ),
        record(
          "bootstrap-opened",
          BootstrapStartupRecord.codec,
          startup.copy(
            phase = BootstrapPhase.Opened,
            issuedVoteIntents = Vector(hash(713)),
          ),
        ),
      )

  def goldenVectors(): Unit =
    V2TransitionGoldenVectors.validate(canonicalVectors())
    assert(
      FencePromise.codec
        .encode(
          fence.copy(greatestPreviouslySignedHeight = Some(fence.boundary)),
        )
        .isLeft,
    )
    assert(
      ActivationPreparation.codec
        .encode(
          activation.copy(prepared = activation.prepared ++ activation.prepared),
        )
        .isLeft,
    )
    assert(
      ActivationDecision.codec
        .encode(decision.copy(decisionSequence = 0L))
        .isLeft,
    )
    assert(
      BootstrapStartupRecord.codec
        .encode(startup.copy(issuedVoteIntents = Vector(hash(713))))
        .isLeft,
    )
    assert(
      ArchiveAbsence.codec
        .encode(absence.copy(missingScopes = Vector.empty))
        .isLeft,
    )
    assert(
      ArchiveAbsence.codec
        .encode(absence.copy(earliestKnownAbsence = Some(21L)))
        .isLeft,
    )
    assert(
      RestoreEvidence.codec.encode(restore.copy(authorityId = Utf8(""))).isLeft,
    )

  def run(): Unit =
    sourceAndIntent()
    signedDomainSeparation()
    baselineOrdering()
    bootstrapValidatorOrdering()
    retiredAncestry()
    intervalShapes()
    drainShapes()
    handoverBoundary()
    goldenVectors()
    println(
      "V2TransitionEvidenceConformance PASS: 8 public schema cases and 55 independently encoded literal byte/preimage/hash vectors; runtime authority and physical enforcement are separate JVM checks",
    )
