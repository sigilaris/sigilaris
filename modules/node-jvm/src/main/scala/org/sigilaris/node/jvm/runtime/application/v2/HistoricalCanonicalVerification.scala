package org.sigilaris.node.jvm.runtime.application.v2

import cats.Monad
import cats.data.EitherT
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.InclusionHeight
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  FinalizedAnchorSuggestion,
  FinalizedProof,
  Proposal,
  ProposalId,
  QuorumCertificate,
}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given

final case class HistoricalCertifiedMaterial(
    proposal: Proposal,
    certificate: QuorumCertificate,
)
object HistoricalCertifiedMaterial:
  given ByteEncoder[HistoricalCertifiedMaterial] = ByteEncoder.derived
  given ByteDecoder[HistoricalCertifiedMaterial] = ByteDecoder.derived
final case class HistoricalSafetyArchive(
    profile: HistoricalSafetyProfile,
    originalRecords: Vector[Bytes],
)
object HistoricalSafetyArchive:
  import V2Codecs.given
  given ByteEncoder[HistoricalSafetyArchive] = ByteEncoder.derived
  given ByteDecoder[HistoricalSafetyArchive] = ByteDecoder.derived

/** Pinned original continuation evidence. These are actual proposal/QC and
  * pre-sign journal products, never the caller's expected suffix facts.
  */
final case class HistoricalContinuationProof(
    format: Long,
    drain: FinalizedAnchorSuggestion,
    suffix: Vector[HistoricalCertifiedMaterial],
    safety: Vector[HistoricalSafetyArchive],
)
object HistoricalContinuationProof:
  import V2Codecs.given
  given ByteEncoder[HistoricalContinuationProof]         = ByteEncoder.derived
  given ByteDecoder[HistoricalContinuationProof]         = ByteDecoder.derived
  val codec: CanonicalCodec[HistoricalContinuationProof] =
    CanonicalCodec.derived(value =>
      V2Validation.format(
        value.format,
        1L,
        "historicalContinuationProof.format",
      ),
    )
  def digest(value: HistoricalContinuationProof): Either[CoreFailure, Hash] =
    codec
      .encode(value)
      .map(
        Commitment.hash(
          Utf8("sigilaris.application.historical-continuation.proof.v1"),
          _,
        ),
      )

trait HistoricalContinuationRepository[F[_]]:
  def read(proofDigest: Hash): Result[F, Bytes]

  /** Actual committed activation and selected target namespace readback. */
  def installedGroup(handoverDigest: Hash): Result[F, VerifiedActiveGroup]

type HandoverInstalledBase = HistoricalCanonicalVerifier.HandoverInstalledBase
type VerifiedHistoricalAdvance =
  HistoricalCanonicalVerifier.VerifiedHistoricalAdvance
final case class HistoricalCanonicalCapacity(maximumBlocks: Long)

trait HistoricalCanonicalVerifier[F[_]]:
  def verifyInstallation(
      handover: VerifiedHandover,
  ): Result[F, HandoverInstalledBase]
  def verify(
      base: HandoverInstalledBase,
      previousCanonical: ApplicationAnchor,
      finalized: FinalizedAnchorSuggestion,
  ): Result[F, VerifiedHistoricalAdvance]
  def catchUp(
      base: HandoverInstalledBase,
      previousCanonical: ApplicationAnchor,
      actualFinalized: FinalizedAnchorSuggestion,
  ): Result[F, Vector[VerifiedHistoricalAdvance]]

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object HistoricalCanonicalVerifier:
  final class HandoverInstalledBase private[HistoricalCanonicalVerifier] (
      val handoverDigest: Hash,
      val sourceContext: DomainContext,
      val targetContext: DomainContext,
      val canonicalStart: ApplicationAnchor,
      val canonicalStartPayload: Bytes,
      val workingParent: ApplicationAnchor,
      val executionAnchor: ApplicationAnchor,
      val workingStatePayload: Bytes,
      val retainedSuffix: Vector[
        HistoricalConsensusAuthentication.VerifiedCertifiedProposal,
      ],
      val handover: VerifiedHandover,
      private[jvm] val activation: VerifiedActiveGroup,
  )
  final class VerifiedHistoricalAdvance private[HistoricalCanonicalVerifier] (
      val advance: HistoricalCanonicalAdvance,
      val digest: Hash,
  )
  private final case class Walk(
      current: Proposal,
      child: Proposal,
      grandchild: Proposal,
      descending: Vector[HistoricalConsensusAuthentication.VerifiedFinality],
      seen: Set[ProposalId],
  )

  def authenticated[F[_]: Monad](
      consensus: HistoricalConsensusAuthentication[F],
      original: HistoricalContinuationRepository[F],
      capacity: HistoricalCanonicalCapacity,
  ): HistoricalCanonicalVerifier[F] = new HistoricalCanonicalVerifier[F]:
    def check(condition: Boolean, detail: String): Result[F, Unit] =
      EitherT.fromEither[F](
        RuntimeCheck.require(condition, RuntimeFailureCode.ProofInvalid, detail),
      )
    def core[A](value: Either[CoreFailure, A]): Result[F, A] =
      EitherT.fromEither[F](value.leftMap(V2RuntimeFailure.fromCore))
    def selected(
        value: HistoricalConsensusAuthentication.VerifiedCertifiedProposal,
    ): Result[F, CertifiedSuffixEntry] = for profile <- core(
        HistoricalProfileRange.digest(value.execution.selected),
      )
    yield CertifiedSuffixEntry(
      InclusionHeight(value.execution.proposal.block.height.toBigNat),
      value.execution.proposal.targetBlockId.toUInt256,
      value.execution.parent.targetBlockId.toUInt256,
      profile,
      value.proposalDigest,
      value.quorumDigest,
      value.execution.replay.resultInventoryDigest,
    )

    def verifyInstallation(
        handover: VerifiedHandover,
    ): Result[F, HandoverInstalledBase] = for
      bytes  <- original.read(handover.evidence.continuationProofDigest)
      proof  <- core(HistoricalContinuationProof.codec.decode(bytes))
      digest <- core(HistoricalContinuationProof.digest(proof))
      _      <- check(
        digest == handover.evidence.continuationProofDigest,
        "original continuation evidence differs from its fixed digest",
      )
      drain      <- consensus.finalized(proof.drain)
      drainBlock <- EitherT.fromOption[F](
        drain.ordered.headOption,
        V2RuntimeFailure.at(
          RuntimeFailureCode.ProofInvalid,
          "drain finality lacks its source block",
        ),
      )
      _ <- check(
        drainBlock.selected.context == handover.evidence.source && proof.drain.anchorBlockId.toUInt256 == handover.evidence.drainCheckpointId &&
          proof.drain.anchorHeight.toBigNat == handover.evidence.drainCheckpointHeight.toBigNat && drainBlock.replay.nextStateRoot == handover.evidence.drainStateRoot,
        "actual drain finality differs from original handover F",
      )
      suffix <- proof.suffix.traverse(value =>
        consensus.certified(value.proposal, value.certificate),
      )
      entries <- suffix.traverse(selected)
      _       <- check(
        entries == handover.evidence.retainedSuffix,
        "actual retained suffix changed after handover authentication",
      )
      _ <- (drainBlock +: suffix.map(_.execution))
        .zip(suffix.map(_.execution))
        .traverse_ { (parent, child) =>
          check(
            child.parent.targetBlockId == parent.proposal.targetBlockId && child.proposal.block.height.toBigNat.toBigInt == parent.proposal.block.height.toBigNat.toBigInt + 1 && child.replay.priorStateRoot == parent.replay.nextStateRoot && child.selected.context == handover.evidence.source,
            "retained old suffix does not preserve parent/height/state/context continuity",
          )
        }
      last = suffix.lastOption.fold(drainBlock)(_.execution)
      _ <- check(
        last.proposal.targetBlockId.toUInt256 == handover.evidence.continuationParentId && last.replay.nextStateRoot == handover.evidence.continuationParentRoot && last.proposal.block.height.toBigNat.toBigInt + 1 == handover.evidence.boundaryHeight.toBigNat.toBigInt,
        "actual old continuation parent or boundary changed",
      )
      active         <- original.installedGroup(handover.digest)
      preparedDigest <- core(ActivationPreparation.digest(active.preparation))
      _              <- check(
        active.handover.digest == handover.digest && active.decision.preparationDigest == preparedDigest &&
          active.preparation.transitionDigest == handover.digest && active.decision.parentBlockId == handover.evidence.continuationParentId &&
          active.decision.firstHeight == handover.evidence.boundaryHeight && active.decision.targetManifestDigest == handover.evidence.targetManifestDigest &&
          active.preparation.parentBlockId == active.decision.parentBlockId && active.preparation.firstHeight == active.decision.firstHeight &&
          active.preparation.targetManifestDigest == active.decision.targetManifestDigest &&
          active.preparation.retainedSafetyInventoryDigest == active.decision.retainedSafetyInventoryDigest,
        "historical installed base lacks the exact atomic activation decision",
      )
      actualPayload = active.workingStatePayload
      _ <- check(
        actualPayload == last.replay.material.canonicalStatePayload,
        "active working namespace differs from complete replayed P state data",
      )
      canonical = ApplicationAnchor(
        handover.evidence.source,
        handover.evidence.drainCheckpointId,
        handover.evidence.drainCheckpointHeight,
        handover.evidence.drainStateRoot,
      )
      working = ApplicationAnchor(
        handover.evidence.source,
        handover.evidence.continuationParentId,
        InclusionHeight(last.proposal.block.height.toBigNat),
        handover.evidence.continuationParentRoot,
      )
    yield new HandoverInstalledBase(
      handover.digest,
      handover.evidence.source,
      handover.evidence.target,
      canonical,
      drainBlock.replay.material.canonicalStatePayload,
      working,
      working.copy(context = handover.evidence.target),
      actualPayload,
      suffix,
      handover,
      active,
    )

    def advance(
        base: HandoverInstalledBase,
        previous: ApplicationAnchor,
        verified: HistoricalConsensusAuthentication.VerifiedFinality,
    ): Result[F, VerifiedHistoricalAdvance] = for
      source <- EitherT.fromOption[F](
        verified.ordered.headOption,
        V2RuntimeFailure.at(
          RuntimeFailureCode.ProofInvalid,
          "historical finality source missing",
        ),
      )
      _ <- check(
        previous.context == base.sourceContext && previous.height.toBigNat.toBigInt >= base.canonicalStart.height.toBigNat.toBigInt && previous.height.toBigNat.toBigInt < base.workingParent.height.toBigNat.toBigInt,
        "historical canonical frontier is outside installed F-to-P range",
      )
      _ <- check(
        source.selected.context == base.sourceContext && source.parent.targetBlockId.toUInt256 == previous.blockId && source.replay.priorStateRoot == previous.stateRoot && source.proposal.block.height.toBigNat.toBigInt == previous.height.toBigNat.toBigInt + 1,
        "historical advance does not extend the actual original canonical frontier",
      )
      installed <- EitherT.fromOption[F](
        base.retainedSuffix.find(
          _.execution.proposal.targetBlockId == source.proposal.targetBlockId,
        ),
        V2RuntimeFailure.at(
          RuntimeFailureCode.ProofInvalid,
          "actual old finality is on another continuation branch",
        ),
      )
      old = installed.execution.replay
      now = source.replay
      _ <- check(
        old.priorStateRoot == now.priorStateRoot && old.nextStateRoot == now.nextStateRoot && old.bodyRoot == now.bodyRoot &&
          old.executionPlanRoot == now.executionPlanRoot && old.resultInventoryDigest == now.resultInventoryDigest &&
          old.material.planBytes == now.material.planBytes && old.material.canonicalStatePayload == now.material.canonicalStatePayload && old.material.normalizedResults == now.material.normalizedResults &&
          installed.execution.selected == source.selected,
        "actual finalized old replay differs from the retained installed suffix",
      )
      profile <- core(HistoricalProfileRange.digest(source.selected))
      record = HistoricalCanonicalAdvance(
        1L,
        base.handoverDigest,
        source.selected.context,
        previous.blockId,
        source.proposal.targetBlockId.toUInt256,
        InclusionHeight(source.proposal.block.height.toBigNat),
        previous.stateRoot,
        source.replay.nextStateRoot,
        profile,
        source.proposal,
        verified.finalized.finalizedProof.child.justify,
        verified.finalized,
        source.replay.material,
      )
      digest <- core(HistoricalCanonicalAdvance.digest(record))
    yield new VerifiedHistoricalAdvance(record, digest)

    def verify(
        base: HandoverInstalledBase,
        previousCanonical: ApplicationAnchor,
        finalized: FinalizedAnchorSuggestion,
    ): Result[F, VerifiedHistoricalAdvance] =
      consensus
        .finalized(finalized)
        .flatMap(advance(base, previousCanonical, _))

    def catchUp(
        base: HandoverInstalledBase,
        previousCanonical: ApplicationAnchor,
        actualFinalized: FinalizedAnchorSuggestion,
    ): Result[F, Vector[VerifiedHistoricalAdvance]] =
      if actualFinalized.anchorHeight.toBigNat.toBigInt < base.canonicalStart.height.toBigNat.toBigInt
      then
        for
          _ <- check(
            previousCanonical.context == base.sourceContext && previousCanonical.height.toBigNat.toBigInt >= base.canonicalStart.height.toBigNat.toBigInt && previousCanonical.height.toBigNat.toBigInt <= base.workingParent.height.toBigNat.toBigInt,
            "historical catch-up frontier is outside installed F-to-P range",
          )
          bytes <- original.read(base.handover.evidence.continuationProofDigest)
          proof <- core(HistoricalContinuationProof.codec.decode(bytes))
          digest <- core(HistoricalContinuationProof.digest(proof))
          _      <- check(
            digest == base.handover.evidence.continuationProofDigest &&
              proof.drain.anchorBlockId.toUInt256 == base.canonicalStart.blockId &&
              proof.drain.proposal.block.stateRoot.toUInt256 == base.canonicalStart.stateRoot,
            "past finality changed original installed canonical F evidence",
          )
          _ <- consensus.verifyPastFinality(
            proof.drain.proposal,
            actualFinalized,
            capacity.maximumBlocks,
          )
        yield Vector.empty
      else catchUpForward(base, previousCanonical, actualFinalized)

    private def catchUpForward(
        base: HandoverInstalledBase,
        previousCanonical: ApplicationAnchor,
        actualFinalized: FinalizedAnchorSuggestion,
    ): Result[F, Vector[VerifiedHistoricalAdvance]] = for
      _ <- check(
        previousCanonical.context == base.sourceContext && previousCanonical.height.toBigNat.toBigInt >= base.canonicalStart.height.toBigNat.toBigInt && previousCanonical.height.toBigNat.toBigInt <= base.workingParent.height.toBigNat.toBigInt,
        "historical catch-up frontier is outside installed F-to-P range",
      )
      _ <- consensus.finalized(actualFinalized)
      _ <- EitherT.fromEither[F](
        RuntimeCheck.require(
          capacity.maximumBlocks > 0L,
          RuntimeFailureCode.CapacityUnavailable,
          "historical catch-up local capacity is exhausted",
        ),
      )
      descending <- Monad[[A] =>> Result[F, A]].tailRecM(
        Walk(
          actualFinalized.proposal,
          actualFinalized.finalizedProof.child,
          actualFinalized.finalizedProof.grandchild,
          Vector.empty,
          Set.empty,
        ),
      ) { walk =>
        if walk.current.block.height.toBigNat.toBigInt <= previousCanonical.height.toBigNat.toBigInt
        then
          val earlier =
            if walk.seen.isEmpty && walk.current.block.height.toBigNat.toBigInt < previousCanonical.height.toBigNat.toBigInt
            then
              if walk.current.block.height.toBigNat == base.canonicalStart.height.toBigNat
              then Some(base.canonicalStart)
              else
                base.retainedSuffix
                  .find(
                    _.execution.proposal.block.height == walk.current.block.height,
                  )
                  .map(value =>
                    ApplicationAnchor(
                      value.execution.selected.context,
                      value.execution.proposal.targetBlockId.toUInt256,
                      InclusionHeight(
                        value.execution.proposal.block.height.toBigNat,
                      ),
                      value.execution.replay.nextStateRoot,
                    ),
                  )
            else Some(previousCanonical)
          check(
            earlier.exists(anchor =>
              walk.current.targetBlockId.toUInt256 == anchor.blockId && walk.current.block.height.toBigNat == anchor.height.toBigNat && walk.current.block.stateRoot.toUInt256 == anchor.stateRoot,
            ),
            "actual finalized ancestry does not descend to the historical canonical frontier",
          ).as(
            Right[Walk, Vector[
              HistoricalConsensusAuthentication.VerifiedFinality,
            ]](walk.descending),
          )
        else
          for
            _ <- check(
              !walk.seen.contains(walk.current.proposalId),
              "historical catch-up proof cycles",
            )
            _ <- EitherT.fromEither[F](
              RuntimeCheck.require(
                walk.seen.size.toLong < capacity.maximumBlocks,
                RuntimeFailureCode.CapacityUnavailable,
                "historical catch-up local capacity is exhausted",
              ),
            )
            value <- consensus.finalized(
              FinalizedAnchorSuggestion(
                walk.current,
                FinalizedProof(walk.child, walk.grandchild),
              ),
            )
            source <- EitherT.fromOption[F](
              value.ordered.headOption,
              V2RuntimeFailure.at(
                RuntimeFailureCode.ProofInvalid,
                "historical catch-up source is missing",
              ),
            )
            entries =
              if walk.current.block.height.toBigNat.toBigInt <= base.workingParent.height.toBigNat.toBigInt
              then walk.descending :+ value
              else walk.descending
          yield Left[Walk, Vector[
            HistoricalConsensusAuthentication.VerifiedFinality,
          ]](
            Walk(
              source.parent,
              walk.current,
              walk.child,
              entries,
              walk.seen + walk.current.proposalId,
            ),
          )
      }
      result <- descending.reverse.foldLeftM(
        (previousCanonical, Vector.empty[VerifiedHistoricalAdvance]),
      ) { (state, value) =>
        advance(base, state._1, value).map(next =>
          (
            ApplicationAnchor(
              next.advance.context,
              next.advance.blockId,
              next.advance.height,
              next.advance.nextStateRoot,
            ),
            state._2 :+ next,
          ),
        )
      }
    yield result._2
