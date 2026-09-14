package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import cats.Monad
import cats.effect.kernel.Async
import cats.data.EitherT
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.{
  ExecutionPlanRoot,
  NormalizedApplicationResult,
}
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.bytesEncoder
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.application.v2.{
  ApplicationArtifactStorage,
  InitialBootstrapParent,
  VerifiedInitialParent,
  JournalSafetyStore,
  Result,
  RuntimeFailureCode,
  V2RuntimeFailure,
}
import org.sigilaris.node.jvm.runtime.block.{
  BlockBody,
  BlockHeader,
  BlockHeaderVersion,
  BlockId,
  BlockRecord,
  StateRoot,
}

/** Historical provenance is explicit even when old header bytes coincide.
  * Selection comes from authenticated configuration/activation history, never
  * from a remotely supplied version flag.
  */
enum HotStuffHistoricalApplicationProfile:
  case LegacyM1(header: BlockHeaderVersion)
  case LegacyM2(header: BlockHeaderVersion)
  case ApplicationV2(manifest: ProtocolManifest)

trait HotStuffApplicationProfileSchedule[F[_]]:
  def at(
      window: HotStuffWindow,
      parent: Option[BlockId],
  ): Result[F, HotStuffHistoricalApplicationProfile]

/** Actual retained or backfilled parent proposal. Its signatures and the
  * request's actual parent QC are checked by the default assembler below.
  */
enum HotStuffProposalParent:
  case Ordinary(proposal: Proposal)
  case Initial(installation: VerifiedInitialParent)
  def header: BlockHeader = this match
    case Ordinary(proposal)    => proposal.block
    case Initial(installation) => installation.genesis

trait HotStuffProposalParentRepository[F[_]]:
  def parent(request: HotStuffProposalInputRequest): Result[F, Proposal]
  def resolve(request: HotStuffProposalInputRequest)(using
      Monad[F],
  ): Result[F, HotStuffProposalParent] =
    parent(request).map(HotStuffProposalParent.Ordinary.apply)

object HotStuffProposalParentRepository:
  def initial[F[_]](
      installation: VerifiedInitialParent,
      ordinary: HotStuffProposalParentRepository[F],
  ): HotStuffProposalParentRepository[F] =
    new HotStuffProposalParentRepository[F]:
      def parent(request: HotStuffProposalInputRequest): Result[F, Proposal] =
        ordinary.parent(request)
      override def resolve(
          request: HotStuffProposalInputRequest,
      )(using Monad[F]): Result[F, HotStuffProposalParent] =
        if request.parent.contains(installation.genesisBlockId) then
          EitherT
            .fromEither[F](
              InitialBootstrapParent.validate(
                installation,
                request.window,
                request.parent,
                request.justify,
              ),
            )
            .as(HotStuffProposalParent.Initial(installation))
        else ordinary.resolve(request)

/** Output of the configured deterministic application executor. This is
  * uncommitted working state; no field means canonical application/finality.
  * The full source/input/instrumentation closure is verified again before an
  * ordinary vote through ApplicationRequestVerifier and durable voting.
  */
final case class HotStuffExecutedApplicationCandidate(
    plan: ExecutionPlan,
    normalizedResults: Vector[Bytes],
    postStateRoots: Vector[Hash],
)

trait HotStuffApplicationCandidateExecution[F[_]]:
  def execute(
      request: HotStuffProposalInputRequest,
      manifest: ProtocolManifest,
      parent: Proposal,
  ): Result[F, Option[HotStuffExecutedApplicationCandidate]]
  @scala.annotation.nowarn("msg=unused explicit parameter")
  def executeInitial(
      request: HotStuffProposalInputRequest,
      manifest: ProtocolManifest,
      parent: VerifiedInitialParent,
  )(using Monad[F]): Result[F, Option[HotStuffExecutedApplicationCandidate]] =
    EitherT.leftT(
      V2RuntimeFailure.at(
        RuntimeFailureCode.ProofUnavailable,
        "the installed initial state requires its authenticated opening executor",
      ),
    )

trait HotStuffExecutionPlanPreimages[F[_]]:
  def retain(plan: ExecutionPlan): Result[F, ExecutionPlanRoot]
  def fetch(root: ExecutionPlanRoot): Result[F, ExecutionPlan]

/** Complete canonical plan bytes are immutable and forced before publication.
  * Backfill bytes have no authority until the selected V2 codec and root match.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object HotStuffExecutionPlanPreimages:
  private val namespace: Text = Utf8("application-plan-v2")

  def journaled[F[_]: Async](
      safety: JournalSafetyStore[F],
      backfill: ExecutionPlanRoot => Result[F, Option[Bytes]],
  ): HotStuffExecutionPlanPreimages[F] =
    authenticated(ApplicationArtifactStorage.journaled(safety), backfill)

  def authenticated[F[_]: Monad](
      storage: ApplicationArtifactStorage[F],
      backfill: ExecutionPlanRoot => Result[F, Option[Bytes]],
  ): HotStuffExecutionPlanPreimages[F] = new HotStuffExecutionPlanPreimages[F]:
    private def core[A](value: Either[CoreFailure, A]): Result[F, A] =
      EitherT.fromEither[F](value.leftMap(V2RuntimeFailure.fromCore))
    private def decoded(
        root: ExecutionPlanRoot,
        bytes: Bytes,
    ): Result[F, ExecutionPlan] =
      for
        plan   <- core(ExecutionPlan.codec.decode(bytes))
        actual <- core(ExecutionPlan.computeRoot(plan))
        _      <- EitherT.fromEither[F](
          Either.cond(
            actual == root,
            (),
            V2RuntimeFailure.at(
              RuntimeFailureCode.CommitmentMismatch,
              "execution plan preimage differs from the selected header root",
            ),
          ),
        )
      yield plan

    def retain(plan: ExecutionPlan): Result[F, ExecutionPlanRoot] =
      for
        bytes    <- core(ExecutionPlan.codec.encode(plan))
        root     <- core(ExecutionPlan.computeRoot(plan))
        _        <- storage.retain(namespace, root.toUInt256, bytes)
        retained <- storage.read(namespace, root.toUInt256)
        _        <- decoded(root, retained)
      yield root

    def fetch(root: ExecutionPlanRoot): Result[F, ExecutionPlan] =
      EitherT(storage.read(namespace, root.toUInt256).value.flatMap {
        case Right(bytes) => decoded(root, bytes).value
        case Left(error)
            if error.code == RuntimeFailureCode.EvidenceMissing || error.code == RuntimeFailureCode.ProofUnavailable =>
          (for
            available <- backfill(root)
            bytes     <- EitherT.fromOption[F](
              available,
              V2RuntimeFailure.at(
                RuntimeFailureCode.ProofUnavailable,
                "execution plan preimage is unavailable locally and through backfill",
              ),
            )
            plan <- decoded(root, bytes)

          yield plan).value
        case Left(error) => Left[V2RuntimeFailure, ExecutionPlan](error).pure[F]
      })

trait HotStuffProposalApplicationAssembly[F[_]]:
  def provider(
      legacy: HotStuffProposalInputProvider[F],
  ): HotStuffProposalInputProvider[F]

/** The ordinary runtime owns header/plan/body construction. Applications supply
  * their deterministic executor and authenticated historical lookup adapters.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object HotStuffProposalApplicationAssembly:
  def default[F[_]: Monad](
      schedule: HotStuffApplicationProfileSchedule[F],
      parents: HotStuffProposalParentRepository[F],
      validators: ValidatorSetLookup[F],
      execution: HotStuffApplicationCandidateExecution[F],
      preimages: HotStuffExecutionPlanPreimages[F],
      maintenance: HotStuffMaintenanceProgress[F],
  ): HotStuffProposalApplicationAssembly[F] =
    new HotStuffProposalApplicationAssembly[F]:
      def provider(
          legacy: HotStuffProposalInputProvider[F],
      ): HotStuffProposalInputProvider[F] =
        new HotStuffProposalInputProvider[F]:
          private def core[A](value: Either[CoreFailure, A]): Result[F, A] =
            EitherT.fromEither[F](value.leftMap(V2RuntimeFailure.fromCore))
          private def check(
              condition: Boolean,
              detail: String,
          ): Result[F, Unit] =
            EitherT.fromEither[F](
              Either.cond(
                condition,
                (),
                V2RuntimeFailure.at(RuntimeFailureCode.InvalidRequest, detail),
              ),
            )
          private def set(window: HotStuffWindow): Result[F, ValidatorSet] =
            EitherT(
              validators
                .validatorSetFor(window)
                .map(
                  _.leftMap(error =>
                    V2RuntimeFailure
                      .at(RuntimeFailureCode.ProofUnavailable, error.reason),
                  ),
                ),
            )
          private def valid[A](
              value: Either[HotStuffValidationFailure, A],
          ): Result[F, A] =
            EitherT.fromEither[F](
              value.leftMap(error =>
                V2RuntimeFailure.at(
                  RuntimeFailureCode.ProofInvalid,
                  error.reason,
                ),
              ),
            )

          private def authenticatedParent(
              request: HotStuffProposalInputRequest,
          ): Result[F, HotStuffProposalParent] =
            parents
              .resolve(request)
              .flatMap:
                case HotStuffProposalParent.Initial(initial) =>
                  EitherT
                    .fromEither[F](
                      InitialBootstrapParent.validate(
                        initial,
                        request.window,
                        request.parent,
                        request.justify,
                      ),
                    )
                    .as(HotStuffProposalParent.Initial(initial))
                case HotStuffProposalParent.Ordinary(parent) =>
                  authenticatedOrdinary(request, parent).map(
                    HotStuffProposalParent.Ordinary.apply,
                  )

          private def authenticatedOrdinary(
              request: HotStuffProposalInputRequest,
              parent: Proposal,
          ): Result[F, Proposal] =
            for
              _ <- EitherT.fromEither[F](
                BlockHeader
                  .validateVersionedCommitment(parent.block)
                  .leftMap(error =>
                    V2RuntimeFailure
                      .at(RuntimeFailureCode.ProofInvalid, error.reason),
                  ),
              )
              historical <- schedule.at(parent.window, parent.block.parent)
              _          <- historical match
                case HotStuffHistoricalApplicationProfile.LegacyM1(header) =>
                  check(
                    parent.block.version == header,
                    "parent header differs from its historical M1 profile",
                  )
                case HotStuffHistoricalApplicationProfile.LegacyM2(header) =>
                  check(
                    parent.block.version == header,
                    "parent header differs from its historical M2 profile",
                  )
                case HotStuffHistoricalApplicationProfile.ApplicationV2(
                      manifest,
                    ) =>
                  core(ProtocolManifest.context(manifest)).flatMap(context =>
                    check(
                      parent.block.version == BlockHeaderVersion.V2 &&
                        parent.window.chainId.value == context.chainId.asString &&
                        parent.window.validatorSetHash.toUInt256 == context.validatorSetHash,
                      "parent header or signing context differs from its historical V2 profile",
                    ),
                  )
              parentValidators  <- set(parent.window)
              justifyValidators <- set(parent.justify.subject.window)
              _                 <- valid(
                HotStuffValidator.validateProposal(
                  parent,
                  parentValidators,
                  Some(justifyValidators),
                ),
              )
              _ <- valid(
                HotStuffValidator.validateQuorumCertificate(
                  request.justify,
                  parentValidators,
                ),
              )
              _ <- check(
                request.parent.contains(parent.targetBlockId) &&
                  request.justify.subject.blockId == parent.targetBlockId &&
                  request.justify.subject.proposalId == parent.proposalId &&
                  request.justify.subject.window == parent.window &&
                  request.window.chainId == parent.window.chainId &&
                  request.height.toBigNat.toBigInt == parent.block.height.toBigNat.toBigInt + 1 &&
                  request.window.height.toBigNat == request.height.toBigNat,
                "automatic candidate does not immediately extend its actual certified parent",
              )
            yield parent

          private def assembled(
              request: HotStuffProposalInputRequest,
              parent: HotStuffProposalParent,
              candidate: HotStuffExecutedApplicationCandidate,
          ): Result[F, HotStuffProposalInputProviderResult] =
            val entries = candidate.plan.waves.flatMap(_.entries)
            val roots   =
              parent.header.stateRoot.toUInt256 +: candidate.postStateRoots
            for
              _ <- core(ExecutionPlan.validateShape(candidate.plan))
              _ <- check(
                entries.sizeCompare(
                  candidate.normalizedResults.size,
                ) == 0 && entries.sizeCompare(
                  candidate.postStateRoots.size,
                ) == 0 &&
                  entries.map(_.entryPreStateRoot) == roots.dropRight(1),
                "automatic plan is not backed by complete sequential working results",
              )
              finalRoot <- EitherT.fromOption[F](
                roots.lastOption,
                V2RuntimeFailure.at(
                  RuntimeFailureCode.ProofUnavailable,
                  "parent state is unavailable",
                ),
              )
              body = BlockBody[Hash, Hash, Bytes](
                entries
                  .zip(candidate.normalizedResults)
                  .map((entry, result) =>
                    BlockRecord(
                      entry.source.txId,
                      Some(
                        NormalizedApplicationResult
                          .fromBytes(result)
                          .digest
                          .toUInt256,
                      ),
                      Vector.empty[Bytes],
                    ),
                  )
                  .toSet,
              )
              bodyRoot <- EitherT.fromEither[F](
                BlockBody
                  .computeBodyRoot(body)
                  .leftMap(error =>
                    V2RuntimeFailure
                      .at(RuntimeFailureCode.InvalidRequest, error.reason),
                  ),
              )
              txIds <- entries.traverse(entry =>
                EitherT.fromEither[F](
                  org.sigilaris.node.gossip.StableArtifactId
                    .fromBytes(entry.source.txId.bytes)
                    .leftMap(error =>
                      V2RuntimeFailure
                        .at(RuntimeFailureCode.InvalidRequest, error),
                    ),
                ),
              )
              expectedRoot <- core(ExecutionPlan.computeRoot(candidate.plan))
              root         <- preimages.retain(candidate.plan)
              _            <- check(
                root == expectedRoot,
                "retained plan root differs from the canonical assembled plan",
              )
              retainedPlan <- preimages.fetch(root)
              _            <- check(
                retainedPlan == candidate.plan,
                "retained preimage differs from the complete assembled plan",
              )
              input = HotStuffProposalInput(
                request.parent,
                request.height,
                StateRoot(finalRoot),
                bodyRoot,
                request.timestamp,
                ProposalTxSet(txIds.sortBy(_.toHexLower)),
                BlockHeaderVersion.V2,
                Some(root),
              )
              _ <- valid(
                HotStuffProposalInputValidator.validate(request, input),
              )
            yield HotStuffProposalInputProviderResult.Supplied(input)

          private def v2(
              request: HotStuffProposalInputRequest,
              manifest: ProtocolManifest,
          ): Result[F, HotStuffProposalInputProviderResult] =
            for
              context <- core(ProtocolManifest.context(manifest))
              _       <- check(
                request.window.chainId.value == context.chainId.asString &&
                  request.window.validatorSetHash.toUInt256 == context.validatorSetHash,
                "selected application profile differs from the candidate signing context",
              )
              parent    <- authenticatedParent(request)
              candidate <- parent match
                case HotStuffProposalParent.Ordinary(proposal) =>
                  execution.execute(request, manifest, proposal)
                case HotStuffProposalParent.Initial(initial) =>
                  for
                    _ <- check(
                      context == initial.context,
                      "initial executor changed the installed full application context",
                    )
                    result <- execution.executeInitial(
                      request,
                      manifest,
                      initial,
                    )
                    opening <- EitherT.fromOption[F](
                      result,
                      V2RuntimeFailure.at(
                        RuntimeFailureCode.ProofUnavailable,
                        "the first ordinary block requires its signed opening, not an idle maintenance block",
                      ),
                    )
                    _ <- EitherT.fromEither[F](
                      InitialBootstrapParent.opening(opening.plan),
                    )
                  yield Some(opening)
              families <- manifest.families.traverse(value =>
                core(InputManifest.digest(value)),
              )
              _ <- candidate.traverse_(value =>
                check(
                  value.plan.waves
                    .flatMap(_.entries)
                    .forall(entry =>
                      families.contains(
                        entry.manifestDigest,
                      ) && request.height.toBigNat.toBigInt <= entry.lastInclusionHeight.toBigNat.toBigInt,
                    ),
                  "automatic plan contains an unknown family or an expired signed entry",
                ),
              )
              result <- candidate match
                case Some(value) if value.plan.waves.nonEmpty =>
                  assembled(request, parent, value)
                case empty =>
                  empty.traverse_(value =>
                    check(
                      value.plan == ExecutionPlan.empty && value.normalizedResults.isEmpty && value.postStateRoots.isEmpty,
                      "empty execution result is not the canonical identity transition",
                    ),
                  ) *> EitherT
                    .liftF(maintenance.next(request, context))
                    .flatMap {
                      case HotStuffMaintenanceDecision.Requested(_) =>
                        assembled(
                          request,
                          parent,
                          HotStuffExecutedApplicationCandidate(
                            ExecutionPlan.empty,
                            Vector.empty,
                            Vector.empty,
                          ),
                        )
                      case HotStuffMaintenanceDecision.Stalled(reason) =>
                        EitherT.pure[F, V2RuntimeFailure](
                          HotStuffProposalInputProviderResult
                            .NoWork("maintenanceFinalityStalled", Some(reason)),
                        )
                      case HotStuffMaintenanceDecision.Idle |
                          HotStuffMaintenanceDecision.TargetReached(_) =>
                        if request.finalityDrive.nonEmpty then
                          assembled(
                            request,
                            parent,
                            HotStuffExecutedApplicationCandidate(
                              ExecutionPlan.empty,
                              Vector.empty,
                              Vector.empty,
                            ),
                          )
                        else
                          EitherT.pure[F, V2RuntimeFailure](
                            HotStuffProposalInputProviderResult
                              .NoWork("applicationIdle", None),
                          )
                      case HotStuffMaintenanceDecision.Unavailable(reason) =>
                        EitherT.pure[F, V2RuntimeFailure](
                          HotStuffProposalInputProviderResult.Failed(
                            "maintenanceFinalityUnavailable",
                            Some(reason),
                          ),
                        )
                    }
            yield result

          private def historical(
              request: HotStuffProposalInputRequest,
              header: BlockHeaderVersion,
          ): Result[F, HotStuffProposalInputProviderResult] =
            EitherT.liftF(legacy.nextProposalInput(request)).flatMap {
              case HotStuffProposalInputProviderResult.Supplied(input) =>
                check(
                  input.headerVersion == header,
                  "legacy proposal differs from its selected historical header profile",
                )
                  .as(HotStuffProposalInputProviderResult.Supplied(input))
              case result => EitherT.pure[F, V2RuntimeFailure](result)
            }

          def nextProposalInput(
              request: HotStuffProposalInputRequest,
          ): F[HotStuffProposalInputProviderResult] =
            schedule
              .at(request.window, request.parent)
              .flatMap {
                case HotStuffHistoricalApplicationProfile.LegacyM1(header) =>
                  historical(request, header)
                case HotStuffHistoricalApplicationProfile.LegacyM2(header) =>
                  historical(request, header)
                case HotStuffHistoricalApplicationProfile.ApplicationV2(
                      manifest,
                    ) =>
                  v2(request, manifest)
              }
              .value
              .map {
                case Right(result) => result
                case Left(error)   =>
                  HotStuffProposalInputProviderResult.Failed(
                    "applicationProposalUnavailable",
                    Some(error.message),
                  )
              }
