package org.sigilaris.node.jvm.runtime.application.v2

import cats.Monad
import cats.data.EitherT
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.bytesEncoder
import org.sigilaris.node.jvm.runtime.block.{BlockBody, BlockHeaderVersion}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*

/** Actual historical proposal lookup. These rows grant no ancestry capability.
  */
trait InitialAnchorHistory[F[_]]:
  def retained(
      context: DomainContext,
      blockId: Hash,
  ): Result[F, Option[Proposal]]
  def backfill(
      context: DomainContext,
      blockId: Hash,
  ): Result[F, Option[Proposal]]

type VerifiedInitialAnchorAncestor =
  InitialAnchorAncestry.VerifiedInitialAnchorAncestor

/** P4 supports the concrete historical empty-block profile. A nonempty
  * historical application requires a separately authenticated profile/range
  * implementation; its state transition cannot be inferred from block height.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object InitialAnchorAncestry:
  final class VerifiedInitialAnchorAncestor private[InitialAnchorAncestry] (
      val anchor: ApplicationAnchor,
      val finalized: FinalizedAnchorSuggestion,
      val descending: Vector[Proposal],
  )

  private final case class Walk(
      next: Hash,
      child: Option[Proposal],
      descending: Vector[Proposal],
      visited: Set[Hash],
  )

  def legacyEmpty[F[_]: Monad](
      anchor: ApplicationAnchor,
      finalized: FinalizedAnchorSuggestion,
      validators: ValidatorSetLookup[F],
      history: InitialAnchorHistory[F],
      maximumBlocks: Long,
  ): Result[F, VerifiedInitialAnchorAncestor] =
    def check(condition: Boolean, detail: String): Result[F, Unit] =
      EitherT.fromEither[F](
        RuntimeCheck.require(condition, RuntimeFailureCode.ProofInvalid, detail),
      )
    def lookup(id: Hash): Result[F, Proposal] = for
      local <- history.retained(anchor.context, id)
      found <- local.fold(history.backfill(anchor.context, id))(proposal =>
        EitherT.pure[F, V2RuntimeFailure](Some(proposal)),
      )
      proposal <- EitherT.fromOption[F](
        found,
        V2RuntimeFailure.at(
          RuntimeFailureCode.ProofUnavailable,
          "initial anchor ancestry proposal unavailable",
        ),
      )
      _ <- check(
        proposal.targetBlockId.toUInt256 == id,
        "initial anchor ancestry lookup returned another block",
      )
    yield proposal
    def authenticate(proposal: Proposal): Result[F, Unit] = for
      current <- EitherT(validators.validatorSetFor(proposal.window)).leftMap(
        error =>
          V2RuntimeFailure.at(RuntimeFailureCode.ProofUnavailable, error.reason),
      )
      parent <- EitherT(
        validators.validatorSetFor(proposal.justify.subject.window),
      ).leftMap(error =>
        V2RuntimeFailure.at(RuntimeFailureCode.ProofUnavailable, error.reason),
      )
      _ <- EitherT.fromEither[F](
        HotStuffValidator
          .validateProposal(proposal, current, Some(parent))
          .leftMap(error =>
            V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, error.reason),
          ),
      )
      _ <- check(
        proposal.window.chainId.value == anchor.context.chainId.asString && proposal.justify.subject.window.chainId.value == anchor.context.chainId.asString,
        "initial anchor ancestry crosses chains",
      )
      _ <- check(
        proposal.block.version == BlockHeaderVersion.V1 && proposal.block.executionPlanRoot.isEmpty && proposal.txSet.txIds.isEmpty && BlockBody
          .computeBodyRoot(BlockBody[Hash, Hash, Bytes](Set.empty))
          .contains(proposal.block.bodyRoot),
        "initial anchor ancestry is outside the authenticated legacy empty profile",
      )
    yield ()
    val distance =
      anchor.height.toBigNat.toBigInt - finalized.anchorHeight.toBigNat.toBigInt
    for
      _ <- EitherT.fromEither[F](
        DomainContext
          .validateActive(anchor.context)
          .leftMap(V2RuntimeFailure.fromCore),
      )
      _ <- check(
        distance > 0,
        "initial anchor ancestry target must precede the installed anchor",
      )
      _ <- EitherT.fromEither[F](
        RuntimeCheck.require(
          maximumBlocks > 0L && distance + 1 <= BigInt(maximumBlocks),
          RuntimeFailureCode.CapacityUnavailable,
          "initial anchor ancestry local capacity exhausted",
        ),
      )
      _ <- EitherT(
        HotStuffFinalizedAnchorVerifier.verify(finalized, validators),
      ).leftMap(error =>
        V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, error.reason),
      )
      proof = Vector(
        finalized.proposal,
        finalized.finalizedProof.child,
        finalized.finalizedProof.grandchild,
      )
      _ <- check(
        proof.forall(
          _.window.chainId.value == anchor.context.chainId.asString,
        ) && proof
          .zip(proof.drop(1))
          .forall((parent, child) =>
            child.block.parent.contains(
              parent.targetBlockId,
            ) && child.block.height.toBigNat.toBigInt == parent.block.height.toBigNat.toBigInt + 1,
          ),
        "historical finality proof is not the same contiguous chain",
      )
      rows <- Monad[[A] =>> Result[F, A]]
        .tailRecM(Walk(anchor.blockId, None, Vector.empty, Set.empty)) { walk =>
          for
            _ <- check(
              !walk.visited.contains(walk.next),
              "initial anchor ancestry cycles",
            )
            _ <- EitherT.fromEither[F](
              RuntimeCheck.require(
                walk.descending.size.toLong < maximumBlocks,
                RuntimeFailureCode.CapacityUnavailable,
                "initial anchor ancestry local capacity exhausted",
              ),
            )
            proposal <- lookup(walk.next)
            _        <- authenticate(proposal)
            _        <- walk.child.fold(
              check(
                proposal.block.height.toBigNat == anchor.height.toBigNat && proposal.block.stateRoot.toUInt256 == anchor.stateRoot,
                "historical proof does not reproduce the installed anchor identity and state",
              ),
            )(child =>
              check(
                child.block.parent.contains(
                  proposal.targetBlockId,
                ) && child.block.height.toBigNat.toBigInt == proposal.block.height.toBigNat.toBigInt + 1 && child.block.stateRoot == proposal.block.stateRoot,
                "legacy empty ancestry breaks parent, height, or state continuity",
              ),
            )
            rows = walk.descending :+ proposal
            next <-
              if proposal.targetBlockId == finalized.anchorBlockId then
                check(
                  proposal.block == finalized.proposal.block && proposal.txSet == finalized.proposal.txSet,
                  "historical target differs from the actual finalized block",
                ).as(Right[Walk, Vector[Proposal]](rows))
              else
                for
                  _ <- check(
                    proposal.block.height.toBigNat.toBigInt > finalized.anchorHeight.toBigNat.toBigInt,
                    "historical target is not an ancestor of the installed anchor",
                  )
                  parent <- EitherT.fromOption[F](
                    proposal.block.parent,
                    V2RuntimeFailure.at(
                      RuntimeFailureCode.ProofInvalid,
                      "historical ancestry ended before the selected target",
                    ),
                  )
                yield Left[Walk, Vector[Proposal]](
                  Walk(
                    parent.toUInt256,
                    Some(proposal),
                    rows,
                    walk.visited + walk.next,
                  ),
                )
          yield next
        }
    yield new VerifiedInitialAnchorAncestor(anchor, finalized, rows)
