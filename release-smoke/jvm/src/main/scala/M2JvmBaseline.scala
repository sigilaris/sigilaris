import java.time.Instant

import cats.effect.IO
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.*
import org.sigilaris.core.datatype.{BigNat, UInt256, Utf8}
import org.sigilaris.node.gossip.ChainId
import org.sigilaris.node.jvm.runtime.application.*
import org.sigilaris.node.jvm.runtime.block.{BlockHeight, BlockId}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.runtime.txpipeline.*
import org.sigilaris.node.txpipeline.*

object M2JvmBaseline:
  def run: IO[Unit] =
    conflictingPreCertificateVotes() >>
      genericEffectVoteBoundary() >>
      completeReservationSuperset() >>
      ExactExecutionMode.all.traverse_(emptyLockExactStages) >>
      historicalFinalizedAncestor()

  private def requireRight[E, A](result: IO[Either[E, A]]): IO[A] =
    result.flatMap(value =>
      IO.fromEither(
        value.left.map(error => new IllegalStateException(error.toString)),
      ),
    )

  def conflictingPreCertificateVotes(): IO[Unit] =
    for
      store <- InMemoryApplicationSafetyStore.create[IO](manifest)
      runtime = ApplicationSafetyRuntime[IO](
        store,
        lockAuthenticator,
        effectAuthenticator,
      )
      first = lockSubject(
        executionIds.head,
        inputId("aa"),
        DependencyPlanDigest(uint256("44")),
      )
      second = first.copy(executionId = executionIds.last)
      before <- store.snapshot
      votes  <- (
        runtime.validateLockVote(first, inclusionHeight(10L)).value,
        runtime.validateLockVote(second, inclusionHeight(10L)).value,
      ).parTupled
      after   <- store.snapshot
      journal <- store.journal
      restarted = ApplicationSafetyRuntime[IO](
        store,
        lockAuthenticator,
        effectAuthenticator,
      )
      retry <- restarted.validateLockVote(second, inclusionHeight(10L)).value
      _     <- IO {
        assert(votes == (Right(()), Right(())))
        assert(retry == Right(()))
        assert(after == before)
        assert(after.drain.greatestAdmittedDeadline.isEmpty)
        assert(journal.isEmpty)
        println(
          "M2 conflictingPreCertificateVotes PASS both accepted; no lock, vote subject, deadline or journal mutation",
        )
      }
    yield ()

  def genericEffectVoteBoundary(): IO[Unit] =
    for
      store <- InMemoryApplicationSafetyStore.create[IO](manifest)
      runtime = ApplicationSafetyRuntime[IO](
        store,
        lockAuthenticator,
        effectAuthenticator,
      )
      digest = DependencyPlanDigest(uint256("44"))
      lock   = lockSubject(executionIds.head, inputId("aa"), digest)
      effect = effectSubject(
        executionIds.head,
        producerResult,
        producerRoot,
        digest,
      )
      missing <- runtime.validateEffectVote(effect, deadline).value
      _       <- requireRight(
        runtime
          .recordLockCertificate(lockCertificate(lock), inclusionHeight(10L))
          .value,
      )
      unreserved <- runtime.validateEffectVote(effect, deadline).value
      _          <- requireRight(runtime.reserveProposal(lock, deadline).value)
      before     <- store.snapshot
      journalBefore <- store.journal
      same          <- runtime.validateEffectVote(effect, deadline).value
      changedResult <- runtime
        .validateEffectVote(
          effect.copy(resultDigest = consumerResult.digest),
          deadline,
        )
        .value
      changedDeadline <- runtime
        .validateEffectVote(
          effect.copy(lastInclusionHeight = inclusionHeight(31L)),
          deadline,
        )
        .value
      after        <- store.snapshot
      journalAfter <- store.journal
      _            <- IO {
        assert(missing.left.map(_.reason) == Left("lockCertificateInvalid"))
        assert(unreserved.left.map(_.reason) == Left("reservationMissing"))
        assert(same == Right(()))
        assert(changedResult == Right(()))
        assert(changedDeadline.left.map(_.reason) == Left("deadlineMismatch"))
        assert(before.drain.greatestAdmittedDeadline.contains(deadline))
        assert(after == before)
        assert(journalAfter == journalBefore)
        println(
          "M2 genericEffectVoteBoundary PASS requires recorded same-deadline lock and reciprocal reservation; distinct result subjects accepted without new durable records",
        )
      }
    yield ()

  def completeReservationSuperset(): IO[Unit] =
    for
      store <- InMemoryApplicationSafetyStore.create[IO](manifest)
      runtime = ApplicationSafetyRuntime[IO](
        store,
        lockAuthenticator,
        effectAuthenticator,
      )
      subject = lockSubject(
        executionIds.head,
        inputId("aa"),
        DependencyPlanDigest(uint256("44")),
      )
      _ <- requireRight(
        runtime
          .recordLockCertificate(lockCertificate(subject), inclusionHeight(10L))
          .value,
      )
      superset <- runtime
        .reserveProposal(
          subject.copy(inputIds = Vector(inputId("aa"), inputId("bb"))),
          inclusionHeight(11L),
        )
        .value
      matching <- runtime.reserveProposal(subject, inclusionHeight(11L)).value
      _        <- IO {
        assert(superset.left.map(_.reason) == Left("reservationMissing"))
        assert(matching.isRight)
        println(
          "M2 completeReservationSuperset PASS superset rejected: reservationMissing; exact lock set accepted",
        )
      }
    yield ()

  def emptyLockExactStages(mode: ExactExecutionMode): IO[Unit] =
    for
      harness   <- Harness.create(mode, withLocks = false)
      snapshot  <- harness.safety.snapshot
      lockStage <- harness.safety.markExactLockCertified(pipelineId).value
      stages    <- harness.certificates.traverse(certificate =>
        harness.safety
          .validateExactEffectVote(pipelineId, certificate.subject, deadline)
          .value,
      )
      _ <- IO {
        assert(lockStage.left.map(_.reason) == Left("lockCertificateInvalid"))
        assert(
          stages.map(_.left.map(_.reason)) == Vector.fill(2)(
            Left("lockCertificateInvalid"),
          ),
        )
        assert(snapshot.locks.isEmpty)
        assert(snapshot.drain.greatestAdmittedDeadline.contains(deadline))
        println(
          s"M2 emptyLockExactStages ${mode.wire} PASS producer and consumer rejected: lockCertificateInvalid; registration records deadline only",
        )
      }
    yield ()

  def historicalFinalizedAncestor(): IO[Unit] =
    for
      harness <- Harness.create(ExactExecutionMode.CertifiedAncestor)
      producerBlock = block("b1")
      _ <- requireRight(
        harness.runtime
          .recordCertifiedAncestorProducer(
            pipelineId,
            ExactCertifiedApplication(
              harness.certificates.head,
              deadline,
              producerBlock,
            ),
          )
          .value,
      )
      tip = blockId("b2")
      // This is the public shape returned when the parent is the finalized tip.
      truncated = branchContext(Vector.empty, complete = true)
        .copy(parentBlockId = Some(tip), bestFinalizedBlockId = Some(tip))
      retained = branchContext(
        Vector(tip, BlockId(producerBlock)),
        complete = true,
      ).copy(bestFinalizedBlockId = Some(tip))
      missing = truncated.copy(complete = false)
      rejected <- harness.runtime
        .validateCertifiedAncestorConsumerVote(
          pipelineId,
          harness.certificates.last.subject,
          deadline,
          truncated,
        )
        .value
      accepted <- harness.runtime
        .validateCertifiedAncestorConsumerVote(
          pipelineId,
          harness.certificates.last.subject,
          deadline,
          retained,
        )
        .value
      unavailable <- harness.runtime
        .validateCertifiedAncestorConsumerVote(
          pipelineId,
          harness.certificates.last.subject,
          deadline,
          missing,
        )
        .value
      _ <- IO {
        assert(rejected.left.map(_.reason) == Left("producerNotAncestor"))
        assert(accepted == Right(()))
        assert(
          unavailable.left.map(_.reason) == Left("producerAncestorUnavailable"),
        )
        println(
          "M2 historicalFinalizedAncestor PASS truncated tip rejects producerNotAncestor; supplied retained ancestor accepted; incomplete proof rejects producerAncestorUnavailable",
        )
      }
    yield ()

  private val profile = DependencyProfileManifest(
    DependencyProfileId("neutral.bytes32"),
    DependencyProfileVersion(1),
    VerifierSlot("neutral-slot-a"),
    VerifierManifestDigest("11" * 32),
  )
  private val manifest = ApplicationProtocolManifestV1.unsafeWithComputedDigest(
    epoch = 9L,
    validatorSetHash = "33" * 32,
    maxLockLifetimeBlocks = 64L,
    substrate = ApplicationSubstrateVersions.M1,
    profiles = Vector(profile),
  )
  private val pipelineId     = TxPipelineId("txp_exact_execution")
  private val executionIds   = Vector(executionId("31"), executionId("32"))
  private val deadline       = inclusionHeight(30L)
  private val producerResult = NormalizedApplicationResult.fromBytes(
    ByteVector.encodeUtf8("producer-result").toOption.get,
  )
  private val consumerResult = NormalizedApplicationResult.fromBytes(
    ByteVector.encodeUtf8("consumer-result").toOption.get,
  )
  private val producerRoot = ApplicationStateRoot(uint256("91"))
  private val consumerRoot = ApplicationStateRoot(uint256("92"))
  private val validators   = Vector("v1", "v2", "v3", "v4").map(value =>
    ApplicationValidatorId(Utf8(value)),
  )
  private val validatorSet = HistoricalApplicationValidatorSet(
    ApplicationEpoch(manifest.epoch),
    ApplicationValidatorSetHash(uint256(manifest.validatorSetHash)),
    validators,
  )
  private val lockAuthenticator =
    ApplicationLockCertificateAuthenticator.historical(validatorSet)(
      (_, expected, signature) => expected == signature,
    )
  private val effectAuthenticator =
    ApplicationEffectCertificateAuthenticator.historical(validatorSet)(
      (_, expected, signature) => expected == signature,
    )
  private val acceptedAt = Instant.parse("2026-08-29T00:00:00Z")
  private val chainId    = ChainId.unsafe("exact-chain")
  private val window     = HotStuffWindow.unsafe(
    chainId,
    1L,
    0L,
    ValidatorSetHash(uint256("77")),
  )

  private final case class Harness(
      runtime: ExactPipelineExecutionRuntime[IO],
      safety: ApplicationSafetyRuntime[IO],
      applicationStore: InMemoryApplicationSafetyStore[IO],
      certificates: Vector[CertifiedEffectCertificate],
      dependencyPlanDigest: DependencyPlanDigest,
  )

  private object Harness:
    def create(
        mode: ExactExecutionMode,
        withLocks: Boolean = true,
    ): IO[Harness] =
      for
        exactStore <- InMemoryExactTxPipelineStore.create[IO]
        descriptor           = exactRecord(mode)
        dependencyPlanDigest = DependencyPlanDigest(
          uint256(
            ExactPipelineCanonical.verifiedPlanDigest(
              descriptor.verifiedPlan,
            ),
          ),
        )
        _ <- requireRight(exactStore.createOrReplay(descriptor).value)
        applicationStore <- InMemoryApplicationSafetyStore.create[IO](manifest)
        safety = ApplicationSafetyRuntime[IO](
          applicationStore,
          lockAuthenticator,
          effectAuthenticator,
        )
        runtime = ExactPipelineExecutionRuntime[IO](exactStore, safety)
        _ <- requireRight(runtime.register(pipelineId).value)
        locks = (if withLocks then executionIds else Vector.empty).zipWithIndex
          .map: (executionId, index) =>
            lockSubject(
              executionId,
              inputId(s"0${index + 1}"),
              dependencyPlanDigest,
            )
        _ <- locks.traverse_(subject =>
          requireRight(
            safety
              .recordLockCertificate(
                lockCertificate(subject),
                inclusionHeight(10L),
              )
              .value,
          ),
        )
        _ <- locks.traverse_(subject =>
          requireRight(safety.reserveProposal(subject, deadline).value),
        )
        certificates = Vector(
          effectCertificate(
            effectSubject(
              executionIds.head,
              producerResult,
              producerRoot,
              dependencyPlanDigest,
            ),
          ),
          effectCertificate(
            effectSubject(
              executionIds.last,
              consumerResult,
              consumerRoot,
              dependencyPlanDigest,
            ),
          ),
        )
        _ <- Option
          .when(withLocks)(())
          .traverse_(_ =>
            requireRight(safety.markExactLockCertified(pipelineId).value),
          )
        modeCertificates = mode match
          case ExactExecutionMode.OrderedAtomic     => certificates
          case ExactExecutionMode.CertifiedAncestor => certificates.take(1)
        _ <- Option
          .when(withLocks)(())
          .traverse_(_ =>
            requireRight(
              safety
                .markExactEffectCertified(pipelineId, modeCertificates)
                .value,
            ),
          )
      yield Harness(
        runtime,
        safety,
        applicationStore,
        certificates,
        dependencyPlanDigest,
      )

  private def exactRecord(mode: ExactExecutionMode): ExactTxPipelineRecord =
    val reference = OpaqueDependencyReference(ByteVector.fill(32)(0xaa.toByte))
    val plan      = VerifiedExactDependencyPlan(
      ApplicationPipelineId("application-execution-pipeline"),
      profile.profileId,
      profile.profileVersion,
      profile.verifierSlot,
      profile.verifierManifestDigest,
      executionIds,
      mode,
      0,
      1,
      reference,
      OpaqueReferenceCommitment.compute(reference),
      deadline,
      ExactPipelineDigest("44" * 32),
    )
    val normalized = TxPipelineRequestNormalizer
      .normalize(
        TxPipelineSubmitRequest(
          Vector(
            Vector(
              TxPipelineTransactionPayload("producer"),
              TxPipelineTransactionPayload("consumer"),
            ),
          ),
          TxPipelineWaitMode.Finalized,
        ),
        TxPipelineShapeLimits.default,
      )
      .toOption
      .get
    val generic = TxPipelineRecord
      .accepted(
        pipelineId,
        normalized,
        Vector(
          Vector(
            TxPipelineTxHash("producer-hash"),
            TxPipelineTxHash("consumer-hash"),
          ),
        ),
        None,
        TxPipelineCanonicalPayloadHash("exact-execution-payload"),
        acceptedAt,
      )
      .toOption
      .get
    val binding = ExactPipelineIdentityBinding
      .create(
        pipelineId,
        plan.applicationPipelineId,
        "exact-v2-sha256",
        ExactPipelineCanonical.verifiedPlanDigest(plan),
      )
      .toOption
      .get
    ExactTxPipelineRecord(
      ExactTxPipelineRecord.SchemaVersion,
      generic,
      plan,
      binding,
      ExactPipelineLifecycle.Accepted,
      None,
    )

  private def lockSubject(
      executionId: ExecutionId,
      inputId: ApplicationInputId,
      dependencyPlanDigest: DependencyPlanDigest,
  ): ApplicationLockVoteSubject =
    ApplicationLockVoteSubject(
      ProtocolVersion.M1,
      ApplicationConfigurationDigest(uint256(manifest.configurationDigest)),
      ApplicationEpoch(manifest.epoch),
      ApplicationValidatorSetHash(uint256(manifest.validatorSetHash)),
      executionId,
      dependencyPlanDigest,
      deadline,
      Vector(inputId),
    )

  private def effectSubject(
      executionId: ExecutionId,
      result: NormalizedApplicationResult,
      root: ApplicationStateRoot,
      dependencyPlanDigest: DependencyPlanDigest,
  ): CertifiedEffectVoteSubject =
    CertifiedEffectVoteSubject(
      ProtocolVersion.M1,
      ApplicationConfigurationDigest(uint256(manifest.configurationDigest)),
      ApplicationEpoch(manifest.epoch),
      ApplicationValidatorSetHash(uint256(manifest.validatorSetHash)),
      executionId,
      dependencyPlanDigest,
      deadline,
      result.digest,
      root,
    )

  private def lockCertificate(
      subject: ApplicationLockVoteSubject,
  ): ApplicationLockCertificate =
    val preimage = ApplicationLockVoteSubject.signingPreimage(subject)
    ApplicationLockCertificate(
      subject,
      validators
        .take(3)
        .map(signer => ApplicationLockVote(subject, signer, preimage)),
    )

  private def effectCertificate(
      subject: CertifiedEffectVoteSubject,
  ): CertifiedEffectCertificate =
    val preimage = CertifiedEffectVoteSubject.signingPreimage(subject)
    CertifiedEffectCertificate(
      subject,
      validators
        .take(3)
        .map(signer => CertifiedEffectVote(subject, signer, preimage)),
    )

  private def branchContext(
      ancestors: Vector[BlockId],
      complete: Boolean,
  ): HotStuffProposalInputBranchContext =
    HotStuffProposalInputBranchContext(
      parentBlockId = ancestors.headOption,
      bestFinalizedBlockId = None,
      ancestors = ancestors.zipWithIndex.map: (ancestorBlockId, index) =>
        val proposalId = ProposalId(uint256((100 + index).toHexString))
        HotStuffProposalInputBranchAncestor(
          chainId,
          proposalId,
          ancestorBlockId,
          BlockHeight.unsafeFromLong(index.toLong + 1L),
          None,
          window,
          window.validatorSetHash,
          QuorumCertificateSubject(window, proposalId, ancestorBlockId),
          ProposalTxSet.empty,
        )
      ,
      complete = complete,
      unavailableReason = Option.when(!complete)(
        HotStuffProposalInputDependencyReason.AncestorUnavailable,
      ),
      unavailableDetail = Option.when(!complete)("ancestry incomplete"),
    )

  private def inputId(value: String): ApplicationInputId =
    ApplicationInputId.fromBytes(ByteVector.fromValidHex(value)).toOption.get

  private def executionId(value: String): ExecutionId =
    ExecutionId(uint256(value))

  private def inclusionHeight(value: Long): InclusionHeight =
    InclusionHeight(BigNat.unsafeFromLong(value))

  private def block(value: String): UInt256   = uint256(value)
  private def blockId(value: String): BlockId = BlockId(uint256(value))

  private def uint256(value: String): UInt256 =
    UInt256.fromHex(value).toOption.get
