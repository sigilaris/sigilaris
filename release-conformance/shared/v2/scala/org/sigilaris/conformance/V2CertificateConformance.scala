package org.sigilaris.conformance

import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.{
  ApplicationInputId,
  ApplicationResultDigest,
  ApplicationStateRoot,
  InclusionHeight,
}
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.crypto.{CryptoOps, Signature}
import org.sigilaris.core.datatype.{BigNat, UInt256, Utf8}

/** Real neutral signatures; the fixture supplies trusted historical membership
  * and finality facts.
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
object V2CertificateConformance:
  private def value[A](result: Either[CoreFailure, A]): A =
    result.fold(
      error => throw new AssertionError(error.message),
      resolved => resolved,
    )
  private def uint(hex: String): UInt256 = UInt256.fromHex(hex).toOption.get
  private def height(number: Long): InclusionHeight = InclusionHeight(
    BigNat.unsafeFromLong(number),
  )
  private val keys = Vector.tabulate(4)(index =>
    Utf8(s"v${index + 1}") -> CryptoOps.fromPrivate(BigInt(index + 1)),
  )
  private val transaction = ByteVector.fromValidHex("010203")
  private val identity    =
    ApplicationInputId.fromBytes(ByteVector.fromValidHex("aa")).toOption.get
  private val schema = uint("11")
  private val family = InputManifest(
    2L,
    Utf8("neutral.certificates"),
    1L,
    Vector(
      FieldManifest(
        Utf8("mutable"),
        FieldRole.ExactMutate,
        schema,
        Some(uint("22")),
      ),
    ),
    uint("33"),
    uint("44"),
    uint("55"),
    None,
  )
  private val manifest = ProtocolManifest(
    2L,
    2L,
    Utf8("neutral-certificates"),
    9L,
    uint("66"),
    64L,
    SubstrateVersions.V2,
    ProtocolLimits.V2,
    Vector(family),
    Vector.empty,
  )
  private val context = value(ProtocolManifest.context(manifest))
  private val base = AdmissionBase.Finalized(uint("77"), height(5L), uint("88"))
  private val footprint = value(
    Footprint.canonical(Vector.empty, Vector(identity)),
  )
  private val declaration =
    Declaration.Exact(value(Footprint.declaredCommitment(footprint)))

  private def input(authority: Authority): InputDescriptor = value(
    InputDerivation.derive(
      family,
      Vector(
        ResolvedField(
          Utf8("mutable"),
          FieldRole.ExactMutate,
          ByteVector.fromValidHex("01"),
          Some(identity),
          Some(ExactPrecondition(schema, ByteVector.fromValidHex("01"))),
          Some(authority),
        ),
      ),
    ),
  )

  private def entry(descriptor: InputDescriptor): PlanEntry =
    val declarationDigest = value(Declaration.digest(declaration))
    val execution         = value(
      ExecutionIdentity.compute(
        ExecutionIdentityInput(
          context,
          descriptor.manifestDigest,
          uint("99"),
          transaction,
          descriptor.fullInputCommitment,
          descriptor.lockSubsetCommitment,
          declarationDigest,
          uint("aa"),
          height(10L),
        ),
      ),
    )
    PlanEntry(
      execution,
      PlanSource.ConsensusTransaction(uint("99"), transaction, None),
      descriptor.manifestDigest,
      declaration,
      declarationDigest,
      descriptor.fullInputCommitment,
      descriptor.lockSubsetCommitment,
      value(Footprint.actualCommitment(footprint)),
      uint("aa"),
      height(10L),
      uint("88"),
      None,
    )

  private def signatureBytes(signature: Signature): Bytes =
    signature.v.toLong.toBytes ++ signature.r.toBytes ++ signature.s.toBytes

  private def decodeSignature(bytes: Bytes): Option[Signature] =
    if bytes.size != 72L then None
    else
      val recovery = BigInt(1, bytes.take(8L).toArray)
      Option.when(recovery >= 27 && recovery <= 30)(
        Signature(
          recovery.toInt,
          UInt256.unsafeFromBytesBE(bytes.slice(8L, 40L)),
          UInt256.unsafeFromBytesBE(bytes.drop(40L)),
        ),
      )

  private def signingHash(preimage: Bytes): Array[Byte] =
    CryptoOps.keccak256(preimage.toArray)
  private def votes(preimage: Bytes): Vector[ValidatorSignature] = keys
    .take(3)
    .map((id, key) =>
      ValidatorSignature(
        id,
        signatureBytes(CryptoOps.sign(key, signingHash(preimage)).toOption.get),
      ),
    )

  private def authentication(
      expectedContext: DomainContext,
  ): ArtifactAuthentication = new ArtifactAuthentication:
    def historicalValidators(
        actual: DomainContext,
    ): Either[CoreFailure, Vector[Text]] =
      Either.cond(
        actual == expectedContext,
        keys.map(_._1),
        CoreFailure.at(FailureCode.ProofInvalid, "historicalContext"),
      )
    def verifyFinalizedBase(
        actual: DomainContext,
        supplied: AdmissionBase,
    ): Either[CoreFailure, Unit] =
      Either.cond(
        actual == expectedContext && supplied == base,
        (),
        CoreFailure.at(FailureCode.ProofInvalid, "neutralFinalizedBase"),
      )
    def verifySignature(
        actual: DomainContext,
        signer: Text,
        preimage: Bytes,
        bytes: Bytes,
    ): Either[CoreFailure, Unit] =
      val recovered = for
        signature <- decodeSignature(bytes)
        key <- CryptoOps.recover(signature, signingHash(preimage)).toOption
      yield key
      Either.cond(
        actual == expectedContext && keys
          .find(_._1 == signer)
          .exists(pair => recovered.contains(pair._2.publicKey)),
        (),
        CoreFailure.at(
          FailureCode.InvalidSignature,
          "neutralValidatorSignature",
        ),
      )

  /** Actual public-codec output, exported without logging large preimages. */
  def canonicalVectors(): Vector[(String, Bytes)] =
    val descriptor    = input(Authority.LockEligible)
    val unsignedEntry = entry(descriptor)
    val lockSubject   = LockSubject(
      context,
      unsignedEntry.source.txId,
      unsignedEntry.executionId,
      descriptor.manifestDigest,
      base,
      descriptor.fullInputCommitment,
      descriptor.lockSubsetCommitment,
      unsignedEntry.dependencyPlanDigest,
      unsignedEntry.lastInclusionHeight,
      Vector(identity),
    )
    val lock = LockCertificate(
      lockSubject,
      votes(value(LockSubject.signingPreimage(lockSubject))),
    )
    val lockId        = value(LockCertificate.id(lock))
    val effectSubject = EffectSubject(
      context,
      unsignedEntry.source.txId,
      unsignedEntry.executionId,
      unsignedEntry.manifestDigest,
      lockId,
      unsignedEntry.dependencyPlanDigest,
      unsignedEntry.lastInclusionHeight,
      ApplicationResultDigest(uint("ab")),
      ApplicationStateRoot(uint("bc")),
      unsignedEntry.actualFootprintCommitment,
    )
    val effect = EffectCertificate(
      effectSubject,
      votes(value(EffectSubject.signingPreimage(effectSubject))),
    )
    Vector(
      "protocol-manifest"     -> value(ProtocolManifest.codec.encode(manifest)),
      "lock-subject"          -> value(LockSubject.codec.encode(lockSubject)),
      "lock-signing-preimage" -> value(
        LockSubject.signingPreimage(lockSubject),
      ),
      "lock-certificate" -> value(LockCertificate.codec.encode(lock)),
      "effect-subject"   -> value(EffectSubject.codec.encode(effectSubject)),
      "effect-signing-preimage" -> value(
        EffectSubject.signingPreimage(effectSubject),
      ),
      "effect-certificate" -> value(EffectCertificate.codec.encode(effect)),
    )

  def run(): Unit =
    val authenticated = authentication(context)
    val descriptor    = input(Authority.LockEligible)
    val unsignedEntry = entry(descriptor)
    val lockSubject   = LockSubject(
      context,
      unsignedEntry.source.txId,
      unsignedEntry.executionId,
      descriptor.manifestDigest,
      base,
      descriptor.fullInputCommitment,
      descriptor.lockSubsetCommitment,
      unsignedEntry.dependencyPlanDigest,
      unsignedEntry.lastInclusionHeight,
      Vector(identity),
    )
    val lockPreimage = value(LockSubject.signingPreimage(lockSubject))
    val lock         = LockCertificate(lockSubject, votes(lockPreimage))
    val lockId       = value(LockCertificate.id(lock))
    val lockedEntry  = unsignedEntry.copy(source =
      PlanSource.ConsensusTransaction(
        unsignedEntry.source.txId,
        transaction,
        Some(lockId),
      ),
    )
    assert(LockCertificate.verify(lock, manifest, authenticated) == Right(()))
    assert(
      LockCertificate.codec.decode(
        value(LockCertificate.codec.encode(lock)),
      ) == Right(lock),
    )
    assert(
      AdmissionValidation.validateSource(
        lockedEntry,
        descriptor,
        Some(lock),
        None,
        manifest,
        authenticated,
      ) == Right(()),
    )
    assert(
      AdmissionValidation
        .validateSource(
          unsignedEntry,
          descriptor,
          None,
          None,
          manifest,
          authenticated,
        )
        .left
        .exists(_.code == FailureCode.MissingCertificate),
    )
    assert(
      AdmissionValidation
        .validateSource(
          lockedEntry.copy(source =
            PlanSource.ConsensusTransaction(
              unsignedEntry.source.txId,
              transaction,
              Some(uint("ff")),
            ),
          ),
          descriptor,
          Some(lock),
          None,
          manifest,
          authenticated,
        )
        .isLeft,
    )

    val unlockedInput = input(Authority.ConsensusOnly)
    val unlockedEntry = entry(unlockedInput)
    assert(
      AdmissionValidation.validateSource(
        unlockedEntry,
        unlockedInput,
        None,
        None,
        manifest,
        authenticated,
      ) == Right(()),
    )
    assert(
      AdmissionValidation
        .validateSource(
          unlockedEntry.copy(source =
            PlanSource.ConsensusTransaction(
              unlockedEntry.source.txId,
              transaction,
              Some(lockId),
            ),
          ),
          unlockedInput,
          None,
          None,
          manifest,
          authenticated,
        )
        .left
        .exists(_.code == FailureCode.UnexpectedCertificate),
    )
    assert(
      AdmissionValidation
        .validateSource(
          unlockedEntry,
          unlockedInput,
          Some(lock),
          None,
          manifest,
          authenticated,
        )
        .isLeft,
    )
    assert(
      LockSubject.codec.encode(lockSubject.copy(inputs = Vector.empty)).isLeft,
    )
    assert(
      LockSubject.codec
        .encode(lockSubject.copy(inputs = Vector(identity, identity)))
        .isLeft,
    )

    val effectSubject = EffectSubject(
      context,
      lockedEntry.source.txId,
      lockedEntry.executionId,
      lockedEntry.manifestDigest,
      lockId,
      lockedEntry.dependencyPlanDigest,
      lockedEntry.lastInclusionHeight,
      ApplicationResultDigest(uint("ab")),
      ApplicationStateRoot(uint("bc")),
      lockedEntry.actualFootprintCommitment,
    )
    val effectPreimage = value(EffectSubject.signingPreimage(effectSubject))
    val effect         = EffectCertificate(effectSubject, votes(effectPreimage))
    val effectId       = value(EffectCertificate.id(effect))
    val fast           = lockedEntry.copy(source =
      PlanSource.CertifiedFastExecution(
        lockedEntry.source.txId,
        transaction,
        lockId,
        effectId,
      ),
    )
    assert(
      EffectCertificate.verify(effect, lock, manifest, authenticated) == Right(
        (),
      ),
    )
    assert(
      AdmissionValidation.validateSource(
        fast,
        descriptor,
        Some(lock),
        Some(effect),
        manifest,
        authenticated,
      ) == Right(()),
    )
    assert(
      AdmissionValidation
        .validateSource(
          fast,
          descriptor,
          Some(lock),
          None,
          manifest,
          authenticated,
        )
        .left
        .exists(_.code == FailureCode.MissingCertificate),
    )
    assert(
      AdmissionValidation
        .validateSource(
          lockedEntry,
          descriptor,
          Some(lock),
          Some(effect),
          manifest,
          authenticated,
        )
        .left
        .exists(_.code == FailureCode.UnexpectedCertificate),
    )
    val emptyFast = unlockedEntry.copy(source =
      PlanSource.CertifiedFastExecution(
        unlockedEntry.source.txId,
        transaction,
        lockId,
        effectId,
      ),
    )
    assert(
      AdmissionValidation
        .validateSource(
          emptyFast,
          unlockedInput,
          None,
          Some(effect),
          manifest,
          authenticated,
        )
        .left
        .exists(error =>
          error.code == FailureCode.InvalidLength && error.field
            .contains(Utf8("entry.source")),
        ),
    )
    assert(
      EffectCertificate
        .verify(
          effect.copy(subject =
            effectSubject.copy(lastInclusionHeight = height(11L)),
          ),
          lock,
          manifest,
          authenticated,
        )
        .isLeft,
    )
    assert(
      EffectCertificate
        .verify(
          effect.copy(subject =
            effectSubject.copy(inputLockCertificateId = uint("ff")),
          ),
          lock,
          manifest,
          authenticated,
        )
        .isLeft,
    )
    assert(
      AdmissionValidation
        .validateSource(
          fast.copy(actualFootprintCommitment = uint("ff")),
          descriptor,
          Some(lock),
          Some(effect),
          manifest,
          authenticated,
        )
        .isLeft,
    )
    assert(
      LockCertificate
        .verify(lock.copy(votes = lock.votes.take(2)), manifest, authenticated)
        .left
        .exists(_.code == FailureCode.QuorumNotReached),
    )
    assert(
      LockCertificate.codec.encode(lock.copy(votes = lock.votes.reverse)).isLeft,
    )
    assert(
      LockCertificate.codec
        .encode(
          lock.copy(votes =
            Vector(lock.votes.head, lock.votes.head, lock.votes.last),
          ),
        )
        .isLeft,
    )
    assert(
      LockCertificate
        .verify(
          lock.copy(votes =
            lock.votes
              .updated(0, lock.votes.head.copy(validatorId = Utf8("unknown"))),
          ),
          manifest,
          authenticated,
        )
        .isLeft,
    )

    val firstSignature = decodeSignature(lock.votes.head.signature).get
    val curveOrder     = BigInt(
      "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141",
      16,
    )
    val highS = firstSignature.copy(
      v = 27 + ((firstSignature.v - 27) ^ 1),
      s = UInt256.unsafeFromBigIntUnsigned(
        curveOrder - firstSignature.s.toBigIntUnsigned,
      ),
    )
    val highVote = lock.votes.head.copy(signature = signatureBytes(highS))
    assert(ValidatorSignature.codec.encode(highVote).isLeft)
    assert(
      LockCertificate
        .verify(
          lock.copy(votes = lock.votes.updated(0, highVote)),
          manifest,
          authenticated,
        )
        .isLeft,
    )
    assert(
      ValidatorSignature.codec
        .decode(
          value(ValidatorSignature.codec.encode(lock.votes.head)) ++ ByteVector(
            0.toByte,
          ),
        )
        .left
        .exists(_.code == FailureCode.TrailingBytes),
    )
    val otherManifest = manifest.copy(chainId = Utf8("other-certificates"))
    val otherContext  = value(ProtocolManifest.context(otherManifest))
    val replayed = lock.copy(subject = lockSubject.copy(context = otherContext))
    assert(
      LockCertificate
        .verify(replayed, otherManifest, authentication(otherContext))
        .left
        .exists(_.code == FailureCode.InvalidSignature),
    )
    assert(
      authenticated
        .verifySignature(
          context,
          lock.votes.head.validatorId,
          effectPreimage,
          lock.votes.head.signature,
        )
        .left
        .exists(_.code == FailureCode.InvalidSignature),
    )
    assert(
      authenticated
        .verifySignature(
          context,
          lock.votes.head.validatorId,
          lockPreimage,
          lock.votes(1).signature,
        )
        .left
        .exists(_.code == FailureCode.InvalidSignature),
    )
    assert(
      lockId.toBytes.toHex == "d6ae6b09e630dc0b6ad03b8ab24fbea63ea726118211686f7d1584f29340e86b",
    )
    assert(
      effectId.toBytes.toHex == "ed2d5e63131dd0f43ab54dc4f86c8f5374b3070766e2a755e17afd15acda8070",
    )
    assert(
      lock.votes.head.signature.toHex == "000000000000001cb98767f9b2d66b57f8539ec997198eadc74254a258a0cf53b81eda87d2c21f4525ff45d11ec1020afad72ca17e54483557a5c04ab3c4f2e1bb3ef4c27212c7bc",
    )
    println(
      s"v2-certificates lockId=${lockId.toBytes.toHex} effectId=${effectId.toBytes.toHex} firstSignature=${lock.votes.head.signature.toHex}",
    )
    println(
      "V2CertificateConformance PASS: real 3-of-4 signatures, canonical low-S, chain and lock/effect domains, optional consensus locks and required fast effects",
    )
