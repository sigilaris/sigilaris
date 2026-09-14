package org.sigilaris.node.jvm.runtime.application.v2

import cats.Monad
import cats.data.EitherT
import cats.effect.kernel.Async
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.{
  ExecutionId,
  NormalizedApplicationResult,
}
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  HotStuffPacemakerState,
  HotStuffValidator,
  HotStuffWindow,
  Proposal,
  QuorumCertificate,
  QuorumCertificateSubject,
  ValidatorSet,
  ValidatorSetLookup,
}

final case class AncestorRequest(
    context: DomainContext,
    candidateParent: Hash,
    producerBlock: Hash,
    producerExecution: ExecutionId,
    pipelineDigest: Hash,
    outputDigest: Hash,
    deadline: Height,
)

/** Actual untrusted evidence material, never a pre-approved ancestry result. */
final case class AncestorHistoryEntry(
    context: DomainContext,
    proposal: Proposal,
    plan: ExecutionPlan,
    certificate: QuorumCertificate,
)

trait AncestorHistoryRepository[F[_]]:
  def retained(
      context: DomainContext,
      blockId: Hash,
  ): Result[F, Option[AncestorHistoryEntry]]
  def backfill(
      context: DomainContext,
      blockId: Hash,
  ): Result[F, Option[AncestorHistoryEntry]]

/** This configured lookup is rooted in authenticated manifest history. Its
  * verifier must resolve and execute the selected historical proposal's actual
  * transaction, source, input, state and profile evidence.
  */
final case class AncestorApplicationProfile[F[_]](
    manifest: ProtocolManifest,
    requests: ApplicationRequestVerifier[F],
)
trait AncestorProfileRepository[F[_]]:
  def historical(
      context: DomainContext,
      window: HotStuffWindow,
  ): Result[F, AncestorApplicationProfile[F]]

/** Read the actual local consensus state after bootstrap/recovery. The caller
  * of lookup cannot supply an approved id or a Boolean. This initial policy
  * selects the current pacemaker high-QC parent; other parent policies require
  * their own authenticated consensus-state and branch evidence adapter.
  */
trait ApprovedAncestorParentSource[F[_]]:
  def current(context: DomainContext): Result[F, HotStuffPacemakerState]

/** A local lookup budget. Exhaustion is Unavailable, never protocol invalidity.
  */
final case class AncestorLookupCapacity(maximumBlocks: Long)

type VerifiedAncestorProof = CanonicalAncestorLookup.VerifiedAncestorProof
enum AncestorLookupResult:
  case Available(proof: VerifiedAncestorProof)
  case Unavailable(reason: V2RuntimeFailure)
  case Invalid(reason: V2RuntimeFailure)

trait CanonicalAncestorLookup[F[_]]:
  def lookup(request: AncestorRequest): F[AncestorLookupResult]

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object CanonicalAncestorLookup:
  /** The path runs from candidateParent to producer, inclusive. Every block has
    * a genuine certificate and independently verified application plan. The
    * execution boundary must recheck current approval before consumption;
    * holding this proof does not freeze the pacemaker or acquire a reservation.
    */
  final class VerifiedAncestorProof private[CanonicalAncestorLookup] (
      val request: AncestorRequest,
      val approvedWindow: HotStuffWindow,
      val certifiedPath: Vector[AncestorHistoryEntry],
      val producer: VerifiedConsensusProposal,
      val producerEntry: PlanEntry,
      val normalizedOutput: NormalizedApplicationResult,
  ):
    def producerHeight: Height = producer.candidateHeight

  private final case class CheckedBlock(
      material: AncestorHistoryEntry,
      execution: VerifiedConsensusProposal,
  )
  private final case class Walk(
      next: Hash,
      child: Option[CheckedBlock],
      path: Vector[AncestorHistoryEntry],
      visited: Set[Hash],
  )

  def authenticated[F[_]: Async](
      installedContext: DomainContext,
      history: AncestorHistoryRepository[F],
      profiles: AncestorProfileRepository[F],
      validators: ValidatorSetLookup[F],
      approvedParents: ApprovedAncestorParentSource[F],
      capacity: AncestorLookupCapacity,
  ): CanonicalAncestorLookup[F] = new CanonicalAncestorLookup[F]:
    private def core[A](value: Either[CoreFailure, A]): Result[F, A] =
      EitherT.fromEither[F](value.leftMap(V2RuntimeFailure.fromCore))
    private def check(
        condition: Boolean,
        code: RuntimeFailureCode,
        detail: String,
    ): Result[F, Unit] =
      EitherT.fromEither[F](RuntimeCheck.require(condition, code, detail))
    private def validatorSet(window: HotStuffWindow): Result[F, ValidatorSet] =
      EitherT(
        validators
          .validatorSetFor(window)
          .map(
            _.leftMap(error =>
              V2RuntimeFailure.at(
                RuntimeFailureCode.ProofUnavailable,
                "historical validator set unavailable: " + error.reason,
              ),
            ),
          ),
      )
    private def quorum(value: QuorumCertificate): Result[F, Unit] =
      for
        selected <- validatorSet(value.subject.window)
        _        <- EitherT.fromEither[F](
          HotStuffValidator
            .validateQuorumCertificate(value, selected)
            .leftMap(error =>
              V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, error.reason),
            ),
        )
      yield ()

    private def approval(
        request: AncestorRequest,
    ): Result[F, HotStuffPacemakerState] =
      for
        state <- approvedParents.current(request.context)
        _     <- check(
          state.bootstrapHoldReason.isEmpty,
          RuntimeFailureCode.RecoveryRequired,
          "candidate-parent approval is held by bootstrap or recovery",
        )
        _ <- check(
          state.activeWindow.chainId.value == request.context.chainId.asString && state.activeWindow.validatorSetHash.toUInt256 == request.context.validatorSetHash && state.highestKnownQc.subject.window.chainId == state.activeWindow.chainId,
          RuntimeFailureCode.DomainMismatch,
          "approved parent crosses the installed consensus domain",
        )
        _ <- quorum(state.highestKnownQc)
        _ <- check(
          state.highestKnownQc.subject.blockId.toUInt256 == request.candidateParent,
          RuntimeFailureCode.ProofInvalid,
          "candidate parent is not the current approved high-QC parent",
        )
        _ <- check(
          state.activeWindow.height.toBigNat.toBigInt > state.highestKnownQc.subject.window.height.toBigNat.toBigInt,
          RuntimeFailureCode.ProofInvalid,
          "approved candidate window does not advance its certified parent",
        )
        _ <- check(
          state.highestKnownQc.subject.window.height.toBigNat.toBigInt < request.deadline.toBigNat.toBigInt && state.activeWindow.height.toBigNat.toBigInt <= request.deadline.toBigNat.toBigInt,
          RuntimeFailureCode.DeadlineMismatch,
          "no consumer inclusion remains at the shared deadline",
        )
      yield state

    private def material(
        context: DomainContext,
        block: Hash,
    ): Result[F, AncestorHistoryEntry] =
      for
        retained <- history.retained(context, block)
        resolved <- retained.fold(history.backfill(context, block))(value =>
          EitherT.rightT[F, V2RuntimeFailure](Some(value)),
        )
        result <- EitherT.fromOption[F](
          resolved,
          V2RuntimeFailure.at(
            RuntimeFailureCode.ProofUnavailable,
            "complete retained or backfilled ancestor evidence is unavailable",
          ),
        )
        _ <- check(
          result.context == context && result.proposal.targetBlockId.toUInt256 == block,
          RuntimeFailureCode.ProofInvalid,
          "historical lookup returned another block or context",
        )
      yield result

    private def verifyBlock(
        request: AncestorRequest,
        block: Hash,
    ): Result[F, CheckedBlock] =
      for
        value   <- material(request.context, block)
        profile <- profiles.historical(request.context, value.proposal.window)
        context <- core(ProtocolManifest.context(profile.manifest))
        _       <- check(
          context == request.context && value.proposal.window.chainId.value == context.chainId.asString && value.proposal.window.validatorSetHash.toUInt256 == context.validatorSetHash,
          RuntimeFailureCode.DomainMismatch,
          "historical application profile differs from the exact domain",
        )
        selected <- validatorSet(value.proposal.window)
        justify  <- validatorSet(value.proposal.justify.subject.window)
        _        <- EitherT.fromEither[F](
          HotStuffValidator
            .validateProposal(value.proposal, selected, Some(justify))
            .leftMap(error =>
              V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, error.reason),
            ),
        )
        _ <- quorum(value.certificate)
        _ <- check(
          value.certificate.subject == QuorumCertificateSubject(
            value.proposal.window,
            value.proposal.proposalId,
            value.proposal.targetBlockId,
          ),
          RuntimeFailureCode.ProofInvalid,
          "historical certificate does not certify the complete proposal",
        )
        verified <- profile.requests.verifyProposal(value.proposal, value.plan)
        _        <- check(
          verified.context == request.context && verified.proposal == value.proposal && verified.plan == value.plan && verified.candidateHeight.toBigNat == value.proposal.block.height.toBigNat && verified.validatedStateRoot == value.proposal.block.stateRoot.toUInt256 && verified.bodyRoot == value.proposal.block.bodyRoot.toUInt256,
          RuntimeFailureCode.ProofInvalid,
          "historical verifier returned a different candidate or application state",
        )
      yield CheckedBlock(value, verified)

    private def producer(
        request: AncestorRequest,
        block: CheckedBlock,
    ): Result[F, (PlanEntry, NormalizedApplicationResult)] =
      val entries  = block.material.plan.waves.flatMap(_.entries)
      val matching = entries.zipWithIndex.filter(
        _._1.executionId == request.producerExecution,
      )
      for
        _ <- check(
          matching.sizeCompare(1) == 0,
          RuntimeFailureCode.ProofInvalid,
          "producer execution is absent or repeated in the certified block",
        )
        selected <- EitherT.fromOption[F](
          matching.headOption,
          V2RuntimeFailure.at(
            RuntimeFailureCode.ProofInvalid,
            "producer entry unavailable",
          ),
        )
        bytes <- EitherT.fromOption[F](
          block.execution.normalizedResults.lift(selected._2),
          V2RuntimeFailure.at(
            RuntimeFailureCode.ProofInvalid,
            "producer normalized output is absent",
          ),
        )
        result = NormalizedApplicationResult.fromBytes(bytes)
        _ <- check(
          selected._1.dependencyPlanDigest == request.pipelineDigest,
          RuntimeFailureCode.CommitmentMismatch,
          "producer belongs to another signed pipeline",
        )
        _ <- check(
          selected._1.lastInclusionHeight == request.deadline && block.execution.candidateHeight.toBigNat.toBigInt <= request.deadline.toBigNat.toBigInt,
          RuntimeFailureCode.DeadlineMismatch,
          "producer pipeline binding or common signed deadline differs",
        )
        _ <- check(
          result.digest.toUInt256 == request.outputDigest,
          RuntimeFailureCode.CommitmentMismatch,
          "producer normalized output differs from the registered output digest",
        )
      yield (selected._1, result)

    private def link(
        child: CheckedBlock,
        parent: CheckedBlock,
    ): Result[F, Unit] =
      for
        _ <- check(
          child.material.proposal.block.parent.contains(
            parent.material.proposal.targetBlockId,
          ) && child.material.proposal.justify.subject == parent.material.certificate.subject,
          RuntimeFailureCode.ProofInvalid,
          "historical parent or justify subject breaks the certified branch",
        )
        _ <- check(
          child.execution.candidateHeight.toBigNat.toBigInt == parent.execution.candidateHeight.toBigNat.toBigInt + 1 && child.execution.parentStateRoot == parent.execution.validatedStateRoot,
          RuntimeFailureCode.ProofInvalid,
          "historical height or application-state continuity is incomplete",
        )
      yield ()

    private def verify(
        request: AncestorRequest,
    ): Result[F, VerifiedAncestorProof] =
      for
        _ <- core(DomainContext.validateActive(request.context))
        _ <- check(
          request.context == installedContext,
          RuntimeFailureCode.DomainMismatch,
          "ancestor request differs from the installed domain",
        )
        _ <- check(
          request.deadline.toBigNat.toBigInt > 0,
          RuntimeFailureCode.DeadlineMismatch,
          "ancestor request has no positive signed deadline",
        )
        _ <- check(
          capacity.maximumBlocks > 0L,
          RuntimeFailureCode.CapacityUnavailable,
          "local ancestor lookup budget is exhausted",
        )
        initial <- approval(request)
        origin  <- verifyBlock(request, request.producerBlock)
        output  <- producer(request, origin)
        result  <- Monad[[A] =>> Result[F, A]].tailRecM(
          Walk(request.candidateParent, None, Vector.empty, Set.empty),
        ) { walk =>
          for
            _ <- check(
              !walk.visited.contains(walk.next),
              RuntimeFailureCode.ProofInvalid,
              "cycle in retained ancestor evidence",
            )
            _ <- check(
              walk.path.size.toLong + 1L + (if walk.next == request.producerBlock
                                            then 0L
                                            else 1L) <= capacity.maximumBlocks,
              RuntimeFailureCode.CapacityUnavailable,
              "local ancestor lookup budget is exhausted",
            )
            current <-
              if walk.next == request.producerBlock then
                EitherT.rightT[F, V2RuntimeFailure](origin)
              else verifyBlock(request, walk.next)
            _ <- walk.child.traverse_(link(_, current))
            _ <- check(
              walk.path.nonEmpty || current.material.certificate.subject == initial.highestKnownQc.subject,
              RuntimeFailureCode.ProofInvalid,
              "candidate-parent proposal differs from the approved QC subject",
            )
            _ <- check(
              current.execution.candidateHeight.toBigNat.toBigInt >= origin.execution.candidateHeight.toBigNat.toBigInt,
              RuntimeFailureCode.ProofInvalid,
              "producer is not an ancestor of the approved parent",
            )
            path = walk.path :+ current.material
            next <-
              if walk.next == request.producerBlock then
                for
                  state <- approval(request)
                  _     <- check(
                    state.highestKnownQc.subject == initial.highestKnownQc.subject,
                    RuntimeFailureCode.ProofUnavailable,
                    "approved parent proposal changed during historical verification",
                  )
                yield Right[Walk, VerifiedAncestorProof](
                  new VerifiedAncestorProof(
                    request,
                    state.activeWindow,
                    path,
                    origin.execution,
                    output._1,
                    output._2,
                  ),
                )
              else
                for
                  _ <- check(
                    current.execution.candidateHeight.toBigNat.toBigInt > origin.execution.candidateHeight.toBigNat.toBigInt,
                    RuntimeFailureCode.ProofInvalid,
                    "producer lies on another branch at the same height",
                  )
                  parent <- EitherT.fromOption[F](
                    current.material.proposal.block.parent.map(_.toUInt256),
                    V2RuntimeFailure.at(
                      RuntimeFailureCode.ProofInvalid,
                      "approved history ended before the producer",
                    ),
                  )
                yield Left[Walk, VerifiedAncestorProof](
                  Walk(parent, Some(current), path, walk.visited + walk.next),
                )
          yield next
        }
      yield result

    private def unavailable(error: V2RuntimeFailure): Boolean = error.code match
      case RuntimeFailureCode.ProofUnavailable |
          RuntimeFailureCode.CapacityUnavailable |
          RuntimeFailureCode.StorageUnknown |
          RuntimeFailureCode.RecoveryRequired |
          RuntimeFailureCode.EvidenceMissing =>
        true
      case _ => false

    def lookup(request: AncestorRequest): F[AncestorLookupResult] =
      verify(request).value.attempt.map:
        case Right(Right(proof)) => AncestorLookupResult.Available(proof)
        case Right(Left(error)) if unavailable(error) =>
          AncestorLookupResult.Unavailable(error)
        case Right(Left(error)) => AncestorLookupResult.Invalid(error)
        case Left(_)            =>
          AncestorLookupResult.Unavailable(
            V2RuntimeFailure.at(
              RuntimeFailureCode.ProofUnavailable,
              "ancestor evidence repository failed during lookup",
            ),
          )
