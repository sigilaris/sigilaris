package org.sigilaris.conformance

import java.nio.file.Files
import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*
import scodec.bits.ByteVector
import org.sigilaris.core.application.protocol.{
  ApplicationLockCertificate,
  ApplicationLockVote,
  ApplicationValidatorId,
  HistoricalApplicationValidatorSet,
  ApplicationEpoch,
  ApplicationValidatorSetHash,
  ExecutionId,
}
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.given
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.crypto.{CryptoOps, KeyPair, Signature}
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  given ByteEncoder[FinalizedAnchorSuggestion],
  given ByteDecoder[FinalizedAnchorSuggestion],
}

/** Real original M1 lock issuance for the explicitly supported historical
  * adapter. Birth policy is physically forced before any original controller or
  * safety resource opens. Every expiry proof includes actual three-QC finality
  * and every original command through that finalized checkpoint.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Throw",
  ),
)
object V2HistoricalIssuanceFixture:
  import V2HistoricalFixture.{core, check, accepted}
  import V2RequestConformance.{value, height, inputId, bytes}
  private val authority  = CryptoOps.fromPrivate(BigInt(1801))
  val BirthDomain        = Utf8("neutral.historical-issuance.birth.v1")
  val SourceDomain       = Utf8("neutral.historical-issuance.source.v1")
  val PlanDomain         = Utf8("neutral.historical-issuance.dependency.v1")
  val lockInput: InputId = inputId(bytes("0b"))
  final case class Birth(
      format: Long,
      context: DomainContext,
      signerId: Text,
      publicKey: Bytes,
      enabled: IssuanceCapability,
      maxLifetime: Long,
      baseUpperBound: Height,
      genesisBlockId: Hash,
      stateRoot: Hash,
  )
  object Birth:
    given ByteEncoder[Birth] = ByteEncoder.derived
    given ByteDecoder[Birth] = ByteDecoder.derived
    val codec                = CanonicalCodec.derived[Birth](v =>
      V2Validation.format(v.format, 1L, "issuanceBirth.format"),
    )
  final case class Authorized(payload: Bytes, signature: Bytes)
  object Authorized:
    given ByteEncoder[Authorized] = ByteEncoder.derived
    given ByteDecoder[Authorized] = ByteDecoder.derived
    val codec                     =
      CanonicalCodec.derived[Authorized](_ => Right[CoreFailure, Unit](()))
  final case class LockCommand(
      format: Long,
      input: InputId,
      expected: Long,
      nonce: Long,
      deadline: Height,
  )
  object LockCommand:
    given ByteEncoder[LockCommand] = ByteEncoder.derived
    given ByteDecoder[LockCommand] = ByteDecoder.derived
    val codec                      = CanonicalCodec.derived[LockCommand](v =>
      V2Validation.format(v.format, 1L, "originalLockCommand.format"),
    )
  final case class LockSource(
      signedCommand: Authorized,
      genesisBlockId: Hash,
      initialPayload: Bytes,
  )
  object LockSource:
    given ByteEncoder[LockSource] = ByteEncoder.derived
    given ByteDecoder[LockSource] = ByteDecoder.derived
    val codec                     =
      CanonicalCodec.derived[LockSource](_ => Right[CoreFailure, Unit](()))
  final case class Nonapplication(
      format: Long,
      context: DomainContext,
      finality: FinalizedAnchorSuggestion,
      original: Vector[V2HistoricalFixture.OldMaterial],
  )
  object Nonapplication:
    given ByteEncoder[Nonapplication] = ByteEncoder.derived
    given ByteDecoder[Nonapplication] = ByteDecoder.derived
    val codec = CanonicalCodec.derived[Nonapplication](v =>
      V2Validation.format(v.format, 1L, "historicalNonapplication.format"),
    )
  private def signed(key: KeyPair, domain: Text, payload: Bytes): Authorized =
    val signature = CryptoOps
      .sign(
        key,
        CryptoOps.keccak256(Commitment.preimage(domain, payload).toArray),
      )
      .toOption
      .get
    Authorized(
      payload,
      ByteEncoder[Long].encode(
        signature.v.toLong,
      ) ++ signature.r.bytes ++ signature.s.bytes,
    )
  private def verifies(value: Authorized, domain: Text, key: Bytes): Boolean =
    if value.signature.size != 72L then false
    else
      val signature = Signature(
        BigInt(1, value.signature.take(8L).toArray).toInt,
        UInt256.unsafeFromBytesBE(value.signature.slice(8L, 40L)),
        UInt256.unsafeFromBytesBE(value.signature.drop(40L)),
      )
      ValidatorSignature
        .validate(ValidatorSignature(Utf8("original"), value.signature))
        .isRight &&
      CryptoOps
        .recover(
          signature,
          CryptoOps.keccak256(
            Commitment.preimage(domain, value.payload).toArray,
          ),
        )
        .exists(_.toBytes == key)

  final class Installation private[V2HistoricalIssuanceFixture] (
      val material: V2HistoricalFixture.Material,
      val index: Int,
      val policy: HistoricalIssuancePolicy,
      val request: HistoricalIssuanceRequest,
      val authentication: HistoricalIssuanceAuthentication,
  ):
    def auth: HistoricalIssuanceAuthentication = authentication
    def lockRequest: HistoricalIssuanceRequest = request
    def rawRequest: Bytes                      = value(
      HistoricalIssuanceRequest.codec.encode(request),
    )
    def expiryProof(at: Long): Result[IO, Bytes] = for
      finality <- material.finalized(at)
      _        <- material.consensus.finalized(finality)
      rows     <- EitherT.liftF(material.retained.get)
      original <- EitherT.liftF(material.original.get)
      selected = rows.values.toVector
        .filter(p =>
          p.block.height.toBigNat.toBigInt > 0 && p.block.height.toBigNat.toBigInt <= at,
        )
        .sortBy(_.block.height.toBigNat.toBigInt)
      _ <- check(
        selected.size.toLong == at,
        "nonapplication requires every original block through finality",
      )
      materials <- selected.traverse(p =>
        EitherT.fromOption[IO](
          original.get(p.targetBlockId.toUInt256),
          V2RuntimeFailure.at(
            RuntimeFailureCode.EvidenceMissing,
            "original source command missing",
          ),
        ),
      )
      bytes <- core(
        Nonapplication.codec.encode(
          Nonapplication(1L, material.old, finality, materials),
        ),
      )
      _ <- authentication.finalizedNonapplication(policy, request, bytes)
    yield bytes

  def installation(
      material: V2HistoricalFixture.Material,
      index: Int,
      lockIssuance: IssuanceCapability,
  ): IO[Installation] =
    val (id, key) = material.source.keys(index)
    val birth     = Birth(
      1L,
      material.old,
      id,
      key.publicKey.toBytes,
      lockIssuance,
      2L,
      height(0),
      material.genesis.targetBlockId.toUInt256,
      material.genesis.block.stateRoot.toUInt256,
    )
    val originalBirth = value(
      Authorized.codec.encode(
        signed(authority, BirthDomain, value(Birth.codec.encode(birth))),
      ),
    )
    val expectedPolicy = HistoricalIssuancePolicy(
      1L,
      material.old,
      id,
      key.publicKey.toBytes,
      lockIssuance,
      IssuanceCapability.Unavailable,
      2L,
      height(0),
      originalBirth,
    )
    val birthPath   = material.root.resolve("issuance-policy-" + index.toString)
    val policyBytes = value(
      HistoricalIssuancePolicy.codec.encode(expectedPolicy),
    )
    val command       = LockCommand(1L, lockInput, 10L, 1802L, height(2))
    val commandBytes  = value(LockCommand.codec.encode(command))
    val signedCommand =
      signed(material.source.transactionKey, SourceDomain, commandBytes)
    val source = LockSource(
      signedCommand,
      material.genesis.targetBlockId.toUInt256,
      material.stateBytes(material.initialState),
    )
    val execution = ExecutionId(
      Commitment.hash(
        SourceDomain,
        value(Authorized.codec.encode(signedCommand)),
      ),
    )
    val request = HistoricalIssuanceRequest(
      HistoricalIssuanceRequest.Domain,
      1L,
      material.old,
      height(0),
      HistoricalLockFields(
        1L,
        material.old.configurationDigest,
        material.old.epoch,
        material.old.validatorSetHash,
        execution,
        Commitment.hash(PlanDomain, commandBytes),
        height(2),
        Vector(lockInput),
      ),
      value(LockSource.codec.encode(source)),
    )
    val auth = new HistoricalIssuanceAuthentication:
      def policy(actual: HistoricalIssuancePolicy): Result[IO, Unit] = for
        bytes <- EitherT.liftF(
          IO.blocking(ByteVector.view(Files.readAllBytes(birthPath))),
        )
        decoded  <- core(HistoricalIssuancePolicy.codec.decode(bytes))
        envelope <- core(
          Authorized.codec.decode(actual.originalDeploymentEvidence),
        )
        original <- core(Birth.codec.decode(envelope.payload))
        _        <- check(
          decoded == expectedPolicy && actual == expectedPolicy && original == birth &&
            verifies(
              envelope,
              BirthDomain,
              authority.publicKey.toBytes,
            ) && material.stateRoot(material.initialState) == birth.stateRoot &&
            birth.genesisBlockId == material.genesis.targetBlockId.toUInt256,
          "actual original enabled policy differs from its birth-forced roster/state/independent signature",
        )
      yield ()
      def lock(
          installed: HistoricalIssuancePolicy,
          actual: HistoricalIssuanceRequest,
      ): Result[IO, Unit] = for
        _        <- policy(installed)
        original <- core(LockSource.codec.decode(actual.sourceEvidence))
        decoded  <- core(
          LockCommand.codec.decode(original.signedCommand.payload),
        )
        state <- material.decodeState(original.initialPayload)
        _     <- check(
          actual == request && decoded == command && original == source &&
            verifies(
              original.signedCommand,
              SourceDomain,
              material.source.transactionKey.publicKey.toBytes,
            ) &&
            original.genesisBlockId == material.genesis.targetBlockId.toUInt256 && state == material.initialState &&
            material.stateRoot(
              state,
            ) == material.genesis.block.stateRoot.toUInt256 && state
              .get(decoded.input)
              .contains(decoded.expected),
          "original M1 lock source/signature/eligible existing input or full signed deadline changed",
        )
      yield ()
      def finalizedNonapplication(
          installed: HistoricalIssuancePolicy,
          actual: HistoricalIssuanceRequest,
          proof: Bytes,
      ): Result[IO, Height] = for
        _               <- lock(installed, actual)
        parsed          <- core(Nonapplication.codec.decode(proof))
        selectedContext <- EitherT.fromEither[IO](
          material.profiles.at(parsed.finality.proposal.window).map(_.context),
        )
        _ <- check(
          parsed.context == installed.context && parsed.context == actual.context && selectedContext == parsed.context,
          "new-domain finality cannot expire the original old-context claim",
        )
        _ <- material.consensus.finalized(parsed.finality)
        h = parsed.finality.proposal.block.height.toBigNat.toBigInt
        _ <- check(
          h.isValidLong && h > 0,
          "original finalized height out of range",
        )
        authoritative <- material.finalized(h.toLong)
        _             <- check(
          authoritative == parsed.finality,
          "original finality differs from complete retained historical branch",
        )
        rows     <- EitherT.liftF(material.retained.get)
        commands <- EitherT.liftF(material.original.get)
        selected = rows.values.toVector
          .filter(p =>
            p.block.height.toBigNat.toBigInt > 0 && p.block.height.toBigNat.toBigInt <= h,
          )
          .sortBy(_.block.height.toBigNat.toBigInt)
        _ <- check(
          BigInt(selected.size) == h,
          "original nonapplication omits a finalized prefix block",
        )
        actualSources <- selected.traverse(p =>
          for
            _        <- material.consensus.proposal(p)
            original <- EitherT.fromOption[IO](
              commands.get(p.targetBlockId.toUInt256),
              V2RuntimeFailure.at(
                RuntimeFailureCode.EvidenceMissing,
                "original finalized command absent",
              ),
            )
            _ <- check(
              !original.transaction.command.inputs.contains(lockInput),
              "original finalized branch applied the claimed input",
            )
          yield original,
        )
        _ <- check(
          actualSources == parsed.original,
          "nonapplication changed the original complete executed commands",
        )
      yield org.sigilaris.core.application.protocol
        .InclusionHeight(parsed.finality.proposal.block.height.toBigNat)
      def currentFinalizedHeight(
          installed: HistoricalIssuancePolicy,
      ): Result[IO, Height] = for
        _    <- policy(installed)
        rows <- EitherT.liftF(material.retained.get)
        maximum = rows.values
          .map(_.block.height.toBigNat.toBigInt)
          .maxOption
          .getOrElse(BigInt(0))
        candidate = (maximum - BigInt(2)).max(BigInt(0))
        _ <- check(
          candidate.isValidLong,
          "original current finality height overflow",
        )
        result <-
          if candidate == 0 then EitherT.pure[IO, V2RuntimeFailure](height(0))
          else
            material
              .finalized(candidate.toLong)
              .flatMap(material.consensus.finalized)
              .as(height(candidate.toLong))
      yield result
    for
      _ <- IO
        .blocking(Files.exists(birthPath))
        .flatMap(exists =>
          if exists then IO.unit
          else
            for
              oldExists <- IO.blocking(
                Files.exists(
                  material.root
                    .resolve("node-" + index.toString)
                    .resolve("controller"),
                ) ||
                  Files.exists(
                    material.root
                      .resolve("node-" + index.toString)
                      .resolve("original-safety"),
                  ) || Files.exists(
                    material.root
                      .resolve("node-" + index.toString)
                      .resolve("application-safety"),
                  ),
              )
              _ <- IO(
                assert(
                  !oldExists,
                  "cannot backfill an absent original policy after the source signer starts",
                ),
              )
              _ <- V2HistoricalFixture.durable(birthPath, policyBytes)
            yield (),
        )
      _ <- accepted(auth.policy(expectedPolicy))
    yield new Installation(material, index, expectedPolicy, request, auth)

  def vote(
      installation: Installation,
      signature: ControllerSignature,
  ): ApplicationLockVote =
    ApplicationLockVote(
      installation.request.subject.subject,
      ApplicationValidatorId(installation.policy.signerId),
      signature.signature,
    )

  /** Actual M1 quorum authentication remains true after expiry; inclusion is a
    * separate strict signed-deadline boundary. This does not fabricate a new
    * application result or mutate the old/new canonical application stores.
    */
  def verifyCertificate(
      material: V2HistoricalFixture.Material,
      certificate: ApplicationLockCertificate,
  ): Result[IO, Unit] =
    EitherT.fromEither[IO](
      ApplicationLockCertificate
        .verify(
          certificate,
          HistoricalApplicationValidatorSet(
            ApplicationEpoch(material.old.epoch),
            ApplicationValidatorSetHash(material.old.validatorSetHash),
            material.source.keys.map((id, _) => ApplicationValidatorId(id)),
          ),
        )((id, preimage, signature) =>
          material.source.keys
            .find(_._1 == id.value)
            .exists((_, key) =>
              if signature.size != 72L then false
              else
                CryptoOps
                  .recover(
                    Signature(
                      BigInt(1, signature.take(8L).toArray).toInt,
                      UInt256.unsafeFromBytesBE(signature.slice(8L, 40L)),
                      UInt256.unsafeFromBytesBE(signature.drop(40L)),
                    ),
                    CryptoOps.keccak256(preimage.toArray),
                  )
                  .exists(_.toBytes == key.publicKey.toBytes),
            ),
        )
        .leftMap(_ =>
          V2RuntimeFailure.at(
            RuntimeFailureCode.ProofInvalid,
            "original M1 quorum authentication failed",
          ),
        ),
    )
