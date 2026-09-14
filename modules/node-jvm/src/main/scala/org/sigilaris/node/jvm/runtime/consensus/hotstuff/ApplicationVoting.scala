package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import cats.data.EitherT
import cats.effect.kernel.{Async, Sync}
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.ApplicationValidatorId
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.crypto.KeyPair
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.block.BlockHeaderVersion
import org.sigilaris.node.txpipeline.v2.ExactMode

/** The source adapter resolves actual retained exact candidates and producer
  * locations. The dispatcher still checks its returned proposal against the
  * proposal being voted on, and executes every registered pipeline separately.
  */
final case class HotStuffExactVotingContext[F[_]](
    store: JournalExactPlanStore[F],
    requests: ExactExecutionRequestVerifier[F],
)

final case class HotStuffApplicationVotingContext[F[_]](
    manifest: ProtocolManifest,
    safety: JournalSafetyStore[F],
    requests: ApplicationRequestVerifier[F],
    artifacts: ArtifactAuthentication,
    exact: Option[HotStuffExactVotingContext[F]],
)

trait HotStuffApplicationVotingContexts[F[_]]:
  def resolve(
      manifest: ProtocolManifest,
      localVoter: ValidatorId,
  ): Result[F, HotStuffApplicationVotingContext[F]]

/** Historical payload verification belongs to its authenticated artifact
  * profile. A V1 integer alone cannot select the M1 versus M2 semantics.
  */
trait HotStuffLegacyApplicationAuthentication[F[_]]:
  def verify(
      profile: HotStuffHistoricalApplicationProfile,
      proposal: Proposal,
  ): Result[F, Unit]

object HotStuffLegacyApplicationAuthentication:
  def unsupported[F[_]: Sync]: HotStuffLegacyApplicationAuthentication[F] =
    new HotStuffLegacyApplicationAuthentication[F]:
      def verify(
          profile: HotStuffHistoricalApplicationProfile,
          proposal: Proposal,
      ): Result[F, Unit] = EitherT.leftT(
        V2RuntimeFailure.at(
          RuntimeFailureCode.ProofUnavailable,
          "historical application authentication is not configured",
        ),
      )

/** Closed ordinary-runtime signing adapter. V2 never falls back to direct
  * signing when source, preimage, exact readiness or durable storage fails.
  */
final class HotStuffApplicationVoting[F[_]] private (
    run: (
        Option[FinalizedApplicationRuntime[F]],
        ValidatorId,
        Proposal,
        Either[KeyPair, HotStuffControlledSigning[F]],
    ) => Result[F, Vote],
):
  def vote(
      application: FinalizedApplicationRuntime[F],
      localVoter: ValidatorId,
      proposal: Proposal,
      localKey: KeyPair,
  ): Result[F, Vote] =
    run(
      Some(application),
      localVoter,
      proposal,
      Left[KeyPair, HotStuffControlledSigning[F]](localKey),
    )

  private[hotstuff] def voteControlled(
      application: FinalizedApplicationRuntime[F],
      localVoter: ValidatorId,
      proposal: Proposal,
      signer: HotStuffControlledSigning[F],
  ): Result[F, Vote] =
    run(
      Some(application),
      localVoter,
      proposal,
      Right[KeyPair, HotStuffControlledSigning[F]](signer),
    )

  private[hotstuff] def voteWithoutMaterialization(
      localVoter: ValidatorId,
      proposal: Proposal,
      localKey: KeyPair,
  ): Result[F, Vote] = run(
    None,
    localVoter,
    proposal,
    Left[KeyPair, HotStuffControlledSigning[F]](localKey),
  )

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object HotStuffApplicationVoting:
  def journaled[F[_]: Async](
      schedule: HotStuffApplicationProfileSchedule[F],
      preimages: HotStuffExecutionPlanPreimages[F],
      contexts: HotStuffApplicationVotingContexts[F],
      legacy: HotStuffLegacyApplicationAuthentication[F],
  ): HotStuffApplicationVoting[F] =
    def core[A](value: Either[CoreFailure, A]): Result[F, A] =
      EitherT.fromEither[F](value.leftMap(V2RuntimeFailure.fromCore))
    def check(condition: Boolean, detail: String): Result[F, Unit] =
      EitherT.fromEither[F](
        Either.cond(
          condition,
          (),
          V2RuntimeFailure.at(RuntimeFailureCode.InvalidRequest, detail),
        ),
      )

    def exactReady(
        runtime: HotStuffApplicationVotingContext[F],
        voter: ValidatorId,
        proposal: VerifiedConsensusProposal,
    ): Result[F, Unit] =
      runtime.exact match
        case None =>
          check(
            proposal.reservations.forall(_.exactBinding.isEmpty),
            "exact proposal requires the registered exact execution runtime",
          )
        case Some(exact) =>
          for
            snapshot <- exact.store.snapshot
            _        <- check(
              snapshot.context == proposal.context,
              "exact snapshot differs from the active application context",
            )
            entries  = proposal.plan.waves.flatMap(_.entries)
            selected = snapshot.records.filter(record =>
              entries.exists(entry =>
                record.binding.executionIds.contains(entry.executionId),
              ),
            )
            _ <- selected.traverse_(record =>
              val stageEntries = entries.filter(entry =>
                record.binding.executionIds.contains(entry.executionId),
              )
              val evidence = ExactCandidateEvidence(
                proposal.context,
                proposal.parentBlockId,
                proposal.candidateHeight,
                stageEntries,
                Vector.empty,
              )
              val execution = ExactConsensusExecutionRuntime.journaled(
                exact.store,
                exact.requests,
                Utf8(voter.value),
              )
              val prepared = record.request.signedPlan.intent.mode match
                case ExactMode.OrderedAtomic =>
                  exact.requests
                    .ordered(record.binding.nodePipelineId, evidence)
                    .flatMap(execution.executeOrdered)
                case ExactMode.CertifiedAncestor =>
                  if record.binding.executionIds.headOption.exists(id =>
                      stageEntries.exists(_.executionId == id),
                    )
                  then
                    exact.requests
                      .producer(record.binding.nodePipelineId, evidence)
                      .flatMap(execution.executeProducer)
                  else
                    exact.requests
                      .consumer(record.binding.nodePipelineId, evidence)
                      .flatMap(execution.executeConsumer)
              prepared.flatMap(value =>
                check(
                  value.verifiedProposal.proposal == proposal.proposal && value.verifiedProposal.plan == proposal.plan && value.verifiedProposal.context == proposal.context,
                  "exact candidate repository returned another proposal or execution plan",
                ),
              ),
            )
          yield ()

    def v2(
        application: Option[FinalizedApplicationRuntime[F]],
        voter: ValidatorId,
        proposal: Proposal,
        key: Either[KeyPair, HotStuffControlledSigning[F]],
        manifest: ProtocolManifest,
    ): Result[F, Vote] =
      for
        context <- core(ProtocolManifest.context(manifest))
        _       <- check(
          proposal.block.version == BlockHeaderVersion.V2 && proposal.window.chainId.value == context.chainId.asString && proposal.window.validatorSetHash.toUInt256 == context.validatorSetHash,
          "proposal header or signing domain differs from the selected V2 manifest",
        )
        root <- EitherT.fromOption[F](
          proposal.block.executionPlanRoot,
          V2RuntimeFailure.at(
            RuntimeFailureCode.EvidenceMissing,
            "activated proposal is missing its execution plan root",
          ),
        )
        plan       <- preimages.fetch(root)
        actualRoot <- core(ExecutionPlan.computeRoot(plan))
        _          <- check(
          actualRoot == root,
          "proposal preimage resolver returned a different execution plan root",
        )
        runtime <- contexts.resolve(manifest, voter)
        _       <- check(
          runtime.manifest == manifest && runtime.safety.context == context,
          "resolved voting runtime differs from the selected manifest and journal context",
        )
        signing =
          for
            verified <- runtime.requests.verifyProposal(proposal, plan)
            _        <- check(
              verified.proposal == proposal && verified.plan == plan && verified.context == context && verified.unsignedVote.voter == voter,
              "application verifier returned another source, plan, context or local voter",
            )
            _ <- verified.lockCertificates.traverse_(runtime.safety.importLock)
            _ <- verified.effectCertificates.traverse_(
              runtime.safety.importEffect,
            )
            _      <- exactReady(runtime, voter, verified)
            signer <- key match
              case Right(controlled) =>
                controlled.verifyStore(runtime.safety) *> controlled
                  .applicationSigner(voter)
              case Left(raw) =>
                runtime.safety.snapshot.flatMap(state =>
                  val transitioned = state.committed.exists(record =>
                    record.operation match
                      case JournalOperation.BootstrapBind |
                          JournalOperation.ActivationCommit =>
                        true
                      case _ => false,
                  )
                  check(
                    !transitioned,
                    "installed bootstrap/activation journal requires its closed controller signer",
                  )
                    .as(
                      ApplicationVoteSigner.secp256k1[F](
                        ApplicationValidatorId(Utf8(voter.value)),
                        raw,
                      ),
                    ),
                )
            voting = DurableApplicationVoting.fromStore(
              runtime.safety,
              signer,
              runtime.artifacts,
            )
            prepared <- voting.prepareConsensusVote(verified)
            vote     <- voting.signConsensusVote(prepared)
          yield vote
        vote <- application.fold(signing)(
          _.withSigningPermission(
            runtime.safety,
            context,
            Vote.signBytes(
              UnsignedVote(proposal.window, voter, proposal.proposalId),
            ),
          )(signing),
        )
      yield vote

    def signLegacy(
        voter: ValidatorId,
        proposal: Proposal,
        key: Either[KeyPair, HotStuffControlledSigning[F]],
        profile: HotStuffHistoricalApplicationProfile,
        header: BlockHeaderVersion,
    ): Result[F, Vote] =
      for
        _ <- check(
          proposal.block.version == header,
          "historical proposal header differs from its authenticated profile",
        )
        _    <- legacy.verify(profile, proposal)
        vote <- key match
          case Right(controlled) => controlled.vote(voter, proposal)
          case Left(raw)         =>
            EitherT.fromEither[F](
              Vote
                .sign(
                  UnsignedVote(proposal.window, voter, proposal.proposalId),
                  raw,
                )
                .leftMap(error =>
                  V2RuntimeFailure
                    .at(RuntimeFailureCode.SignerFailure, error.reason),
                ),
            )
      yield vote

    new HotStuffApplicationVoting((application, voter, proposal, key) =>
      schedule.at(proposal.window, proposal.block.parent).flatMap {
        case HotStuffHistoricalApplicationProfile.ApplicationV2(manifest) =>
          v2(application, voter, proposal, key, manifest)
        case profile @ HotStuffHistoricalApplicationProfile.LegacyM1(header) =>
          signLegacy(voter, proposal, key, profile, header)
        case profile @ HotStuffHistoricalApplicationProfile.LegacyM2(header) =>
          signLegacy(voter, proposal, key, profile, header)
      },
    )

/** Shared by explicit emission and the automatic pacemaker. */
private[hotstuff] object HotStuffApplicationVoteEmission:
  def signControlled[F[_]: Sync](
      config: HotStuffProposalValidationRuntimeConfig[F],
      voter: ValidatorId,
      proposal: Proposal,
      signer: HotStuffControlledSigning[F],
  ): F[Either[HotStuffPolicyViolation, Vote]] =
    val signed =
      (config.applicationVoting, config.applicationFinalization) match
        case (Some(voting), Some(application)) =>
          voting.voteControlled(application, voter, proposal, signer)
        case _ if (proposal.block.version match
              case BlockHeaderVersion.V1 => true
              case _                     => false
            ) =>
          signer.vote(voter, proposal)
        case _ =>
          EitherT.leftT[F, Vote](
            V2RuntimeFailure.at(
              RuntimeFailureCode.RecoveryRequired,
              "controlled V2 voting requires authenticated application voting and finalization",
            ),
          )
    signed.value.attempt.map {
      case Left(error) =>
        Left[HotStuffPolicyViolation, Vote](
          HotStuffPolicyViolation(
            "controlledVoteFailed",
            Some(error.getClass.getName),
          ),
        )
      case Right(result) =>
        result.leftMap(error =>
          HotStuffPolicyViolation("controlledVoteRejected", Some(error.message)),
        )
    }

  def sign[F[_]: Sync](
      config: HotStuffProposalValidationRuntimeConfig[F],
      voter: ValidatorId,
      proposal: Proposal,
      key: KeyPair,
  ): F[Either[HotStuffPolicyViolation, Vote]] =
    (config.applicationVoting, proposal.block.version) match
      case (None, BlockHeaderVersion.V2) =>
        Sync[F].pure(
          Left[HotStuffPolicyViolation, Vote](
            HotStuffPolicyViolation(
              "durableApplicationVotingRequired",
              Some(
                "V2 proposals require authenticated application voting and materialization",
              ),
            ),
          ),
        )
      case (None, _) =>
        Sync[F].delay(
          Vote
            .sign(
              UnsignedVote(proposal.window, voter, proposal.proposalId),
              key,
            )
            .leftMap(error =>
              HotStuffPolicyViolation("voteSigningFailed", Some(error.reason)),
            ),
        )
      case (Some(voting), _) =>
        val signed: Result[F, Vote] = config.applicationFinalization match
          case None =>
            EitherT.leftT(
              V2RuntimeFailure.at(
                RuntimeFailureCode.RecoveryRequired,
                "ordinary application voting requires its finalized application runtime",
              ),
            )
          case Some(application) =>
            voting.vote(application, voter, proposal, key)
        signed.value.attempt.map {
          case Left(error) =>
            Left[HotStuffPolicyViolation, Vote](
              HotStuffPolicyViolation(
                "applicationVoteFailed",
                Some(error.getClass.getName),
              ),
            )
          case Right(result) =>
            result.leftMap(error =>
              HotStuffPolicyViolation(
                "applicationVoteRejected",
                Some(error.message),
              ),
            )
        }
