package org.sigilaris.node.jvm.runtime.application.v2

import cats.Monad
import cats.data.EitherT
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.Proposal
import org.sigilaris.node.txpipeline.v2.{
  ExactPipelineRecord,
  ExactIdentityBinding,
  ExactMode,
}

final case class ReferencedArtifact(digest: Hash, canonicalBytes: Bytes)
final case class ExactCandidateEvidence(
    context: DomainContext,
    parentBlockId: Hash,
    candidateHeight: Height,
    stageEntries: Vector[PlanEntry],
    availableArtifacts: Vector[ReferencedArtifact],
)

/** A location hint, authenticated only by the actual canonical ancestry lookup.
  */
final case class ExactProducerLocation(
    blockId: Hash,
    normalizedResultDigest: Hash,
)
final case class ResolvedExactCandidate(
    proposal: Proposal,
    plan: ExecutionPlan,
    producer: Option[ExactProducerLocation],
)
trait ExactCandidateRepository[F[_]]:
  def resolve(
      evidence: ExactCandidateEvidence,
  ): Result[F, ResolvedExactCandidate]
  def lockCertificate(id: Hash): Result[F, LockCertificate]
  def effectCertificate(id: Hash): Result[F, EffectCertificate]

type VerifiedOrderedExecution =
  ExactExecutionRequestVerifier.VerifiedOrderedExecution
type VerifiedProducerExecution =
  ExactExecutionRequestVerifier.VerifiedProducerExecution
type VerifiedConsumerExecution =
  ExactExecutionRequestVerifier.VerifiedConsumerExecution
trait ExactExecutionRequestVerifier[F[_]]:
  /** Fresh key-use validation against the supplied immutable authoritative
    * projection. This never reacquires the safety or exact store gate.
    */
  private[v2] def revalidateSigning(
      state: SafetyState,
      journal: DurableJournal[F],
      proposal: VerifiedConsensusProposal,
  ): Result[F, Unit]
  def ordered(
      nodePipelineId: Text,
      candidate: ExactCandidateEvidence,
  ): Result[F, VerifiedOrderedExecution]
  def producer(
      nodePipelineId: Text,
      candidate: ExactCandidateEvidence,
  ): Result[F, VerifiedProducerExecution]
  def consumer(
      nodePipelineId: Text,
      candidate: ExactCandidateEvidence,
  ): Result[F, VerifiedConsumerExecution]

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object ExactExecutionRequestVerifier:
  final class VerifiedOrderedExecution private[ExactExecutionRequestVerifier] (
      val record: ExactPipelineRecord,
      val candidate: ExactCandidateEvidence,
      val verifiedProposal: VerifiedConsensusProposal,
      val producerOutput: Bytes,
      val lockCertificates: Vector[VerifiedLockCertificate],
      val effectCertificates: Vector[VerifiedEffectCertificate],
      val outcome: Either[V2RuntimeFailure, Unit],
  )
  final class VerifiedProducerExecution private[ExactExecutionRequestVerifier] (
      val record: ExactPipelineRecord,
      val candidate: ExactCandidateEvidence,
      val verifiedProposal: VerifiedConsensusProposal,
      val producerOutput: Bytes,
      val lockCertificates: Vector[VerifiedLockCertificate],
      val effectCertificates: Vector[VerifiedEffectCertificate],
      val outcome: Either[V2RuntimeFailure, Unit],
  )
  final class VerifiedConsumerExecution private[ExactExecutionRequestVerifier] (
      val record: ExactPipelineRecord,
      val candidate: ExactCandidateEvidence,
      val verifiedProposal: VerifiedConsensusProposal,
      val ancestor: VerifiedAncestorProof,
      val producerOutput: Bytes,
      val lockCertificates: Vector[VerifiedLockCertificate],
      val effectCertificates: Vector[VerifiedEffectCertificate],
      val outcome: Either[V2RuntimeFailure, Unit],
  )

  private final case class BoundCandidate(
      record: ExactPipelineRecord,
      authenticated: VerifiedExactPlan,
      resolved: ResolvedExactCandidate,
      proposal: VerifiedConsensusProposal,
      entries: Vector[PlanEntry],
      entryPositions: Vector[Int],
      locks: Vector[VerifiedLockCertificate],
      effects: Vector[VerifiedEffectCertificate],
  )

  // revalidateSigning creates one read-only verifier and invokes ordered,
  // producer or consumer; it never recursively invokes revalidateSigning.
  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def authenticated[F[_]: Monad](
      store: ExactPlanStore[F],
      plans: ExactPlanAuthentication,
      manifest: ProtocolManifest,
      requests: ApplicationRequestVerifier[F],
      candidates: ExactCandidateRepository[F],
      ancestry: CanonicalAncestorLookup[F],
  ): ExactExecutionRequestVerifier[F] = new ExactExecutionRequestVerifier[F]:
    private[v2] def revalidateSigning(
        state: SafetyState,
        journal: DurableJournal[F],
        proposal: VerifiedConsensusProposal,
    ): Result[F, Unit] =
      import org.sigilaris.node.txpipeline.v2.{
        ExactPipelineSnapshot,
        ExactStageLifecycle,
      }
      for
        captured <- ExactJournalView.snapshot(proposal.context, state, journal)
        _        <- EitherT.fromEither[F](
          ExactOwnershipBinding.consensus(state, proposal),
        )
        entries  = proposal.plan.waves.flatMap(_.entries)
        selected = captured.records.filter(record =>
          entries.exists(entry =>
            record.binding.executionIds.contains(entry.executionId),
          ),
        )
        _ <- selected.traverse_(record =>
          check(
            record.stages
              .filter(stage =>
                entries.exists(_.executionId == stage.executionId),
              )
              .forall(_.lifecycle == ExactStageLifecycle.Reserved),
            "new exact key use requires every selected stage to remain successfully Reserved",
          ),
        )
        readonly = new ExactPlanStore[F]:
          def get(id: Text): Result[F, ExactPipelineRecord] = present(
            captured.records.find(_.binding.nodePipelineId == id),
            "original exact registration missing",
          )
          def snapshot: Result[F, ExactPipelineSnapshot] =
            EitherT.pure[F, V2RuntimeFailure](captured)
        replay = ExactExecutionRequestVerifier.authenticated(
          readonly,
          plans,
          manifest,
          requests,
          candidates,
          ancestry,
        )
        _ <- selected.traverse_ { record =>
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
          val checked = record.request.signedPlan.intent.mode match
            case ExactMode.OrderedAtomic =>
              replay
                .ordered(record.binding.nodePipelineId, evidence)
                .map(v => v.verifiedProposal -> v.outcome)
            case ExactMode.CertifiedAncestor =>
              if record.binding.executionIds.headOption.exists(id =>
                  stageEntries.exists(_.executionId == id),
                )
              then
                replay
                  .producer(record.binding.nodePipelineId, evidence)
                  .map(v => v.verifiedProposal -> v.outcome)
              else
                replay
                  .consumer(record.binding.nodePipelineId, evidence)
                  .map(v => v.verifiedProposal -> v.outcome)
          checked.flatMap { (actual, outcome) =>
            check(
              actual.proposal == proposal.proposal && actual.plan == proposal.plan && actual.context == proposal.context &&
                actual.normalizedResults == proposal.normalizedResults && actual.reservations
                  .map(v => (v.owner, v.witness, v.lastInclusionHeight)) ==
                proposal.reservations.map(v =>
                  (v.owner, v.witness, v.lastInclusionHeight),
                ),
              "exact signing replay changed the complete candidate, working results or owners",
            ) *> EitherT.fromEither[F](outcome)
          }
        }
      yield ()

    private def core[A](value: Either[CoreFailure, A]): Result[F, A] =
      EitherT.fromEither[F](value.leftMap(V2RuntimeFailure.fromCore))
    private def check(value: Boolean, detail: String): Result[F, Unit] =
      EitherT.fromEither[F](
        RuntimeCheck.require(value, RuntimeFailureCode.InvalidRequest, detail),
      )
    private def present[A](value: Option[A], detail: String): Result[F, A] =
      EitherT.fromOption[F](
        value,
        V2RuntimeFailure.at(RuntimeFailureCode.ProofUnavailable, detail),
      )
    private def lockReference(entry: PlanEntry): Option[Hash] =
      entry.source match
        case PlanSource.ConsensusTransaction(_, _, id)      => id
        case PlanSource.CertifiedFastExecution(_, _, id, _) => Some(id)
    private def effectReference(entry: PlanEntry): Option[Hash] =
      entry.source match
        case PlanSource.ConsensusTransaction(_, _, _)       => None
        case PlanSource.CertifiedFastExecution(_, _, _, id) => Some(id)

    private def stageBinding(
        record: ExactPipelineRecord,
        entry: PlanEntry,
        stageIndex: Int,
    ): Result[F, Unit] =
      for
        signed <- present(
          record.request.signedPlan.intent.stages.lift(stageIndex),
          "signed exact stage missing",
        )
        execution <- present(
          record.binding.executionIds.lift(stageIndex),
          "bound exact execution missing",
        )
        declaration <- core(Declaration.digest(signed.declaration))
        _           <- check(
          entry.executionId == execution && entry.source.tag == signed.sourceKind &&
            entry.source.txId == signed.txId && entry.source.signedTransaction == signed.signedTransaction &&
            entry.manifestDigest == signed.manifestDigest && entry.declaration == signed.declaration &&
            entry.declarationDigest == declaration && entry.fullInputCommitment == signed.fullInputCommitment &&
            entry.lockSubsetCommitment == signed.lockSubsetCommitment &&
            entry.dependencyPlanDigest == record.binding.signedPlanDigest &&
            entry.lastInclusionHeight == record.request.signedPlan.intent.lastInclusionHeight,
          "candidate stage differs from original signed exact admission",
        )
      yield ()

    private def bound(
        nodePipelineId: Text,
        evidence: ExactCandidateEvidence,
        mode: ExactMode,
        stageIndexes: Vector[Int],
    ): Result[F, BoundCandidate] =
      for
        record        <- store.get(nodePipelineId)
        _             <- core(ExactPipelineRecord.validate(record))
        authenticated <- core(plans.verify(record.request.signedPlan, manifest))
        _             <- check(
          authenticated.signedPlan == record.request.signedPlan &&
            authenticated.signedPlanDigest == record.binding.signedPlanDigest,
          "exact authentication returned a capability for different signed work",
        )
        _ <- core(
          ExactIdentityBinding.verify(record.binding, authenticated.signedPlan),
        )
        _ <- check(
          record.binding.nodePipelineId == nodePipelineId && record.admissionJournalSequence.nonEmpty &&
            record.request.signedPlan.intent.mode == mode && evidence.context == record.binding.context,
          "candidate differs from registered exact mode or domain",
        )
        _ <- core(
          V2Validation.sortedUnique(
            evidence.availableArtifacts.map(_.digest.bytes.toHex),
            "exact.availableArtifacts",
          ),
        )
        resolved <- candidates.resolve(evidence)
        verified <- requests.verifyProposal(resolved.proposal, resolved.plan)
        _        <- check(
          verified.context == evidence.context && verified.parentBlockId == evidence.parentBlockId &&
            verified.candidateHeight == evidence.candidateHeight &&
            evidence.candidateHeight.toBigNat.toBigInt <= record.request.signedPlan.intent.lastInclusionHeight.toBigNat.toBigInt,
          "actual verified candidate parent, height or deadline differs",
        )
        all = verified.plan.waves.flatMap(_.entries)
        selected <- stageIndexes.traverse(index =>
          for
            id <- present(
              record.binding.executionIds.lift(index),
              "registered stage identity missing",
            )
            position <- present(
              all.zipWithIndex.find(_._1.executionId == id),
              "candidate omits registered exact stage",
            )
            _ <- stageBinding(record, position._1, index)
          yield position,
        )
        entries = selected.map(_._1)
        _ <- check(
          evidence.stageEntries == entries,
          "candidate's claimed stage entries differ from actual full plan",
        )
        locks <- entries
          .flatMap(lockReference)
          .distinct
          .sortBy(_.bytes.toHex)
          .traverse(id =>
            for
              certificate <- candidates.lockCertificate(id)
              actualId    <- core(LockCertificate.id(certificate))
              _           <- check(
                actualId == id,
                "resolved exact input-lock certificate id differs",
              )
              verified <- requests.verifyLockCertificate(certificate)
            yield verified,
          )
        effects <- entries
          .flatMap(effectReference)
          .distinct
          .sortBy(_.bytes.toHex)
          .traverse(id =>
            for
              certificate <- candidates.effectCertificate(id)
              actualId    <- core(EffectCertificate.id(certificate))
              _           <- check(
                actualId == id,
                "resolved exact effect certificate id differs",
              )
              verified <- requests.verifyEffectCertificate(certificate)
            yield verified,
          )
        lockBytes <- locks.traverse(value =>
          for
            id    <- core(LockCertificate.id(value.certificate))
            bytes <- core(LockCertificate.codec.encode(value.certificate))
          yield ReferencedArtifact(id, bytes),
        )
        effectBytes <- effects.traverse(value =>
          for
            id    <- core(EffectCertificate.id(value.certificate))
            bytes <- core(EffectCertificate.codec.encode(value.certificate))
          yield ReferencedArtifact(id, bytes),
        )
        statementBytes <- verified.plan.statements.traverse(value =>
          for
            id    <- core(ClassificationStatement.commitment(value))
            bytes <- core(ClassificationStatement.codec.encode(value))
          yield ReferencedArtifact(id, bytes),
        )
        // Hints are accepted only as the exact canonical preimage of a used,
        // independently authenticated core artifact. Equality also rejects a
        // different decoder representation, trailing bytes, and unused ids.
        _ <- check(
          evidence.availableArtifacts.forall(
            (lockBytes ++ effectBytes ++ statementBytes).contains,
          ),
          "supplied exact artifact is unused or differs from its canonical authenticated preimage",
        )
      yield BoundCandidate(
        record,
        authenticated,
        resolved,
        verified,
        entries,
        selected.map(_._2),
        locks,
        effects,
      )

    private def result(
        bound: BoundCandidate,
        selectedIndex: Int,
    ): Result[F, Bytes] =
      for
        position <- present(
          bound.entryPositions.lift(selectedIndex),
          "exact result position missing",
        )
        bytes <- present(
          bound.proposal.normalizedResults.lift(position),
          "complete exact result missing",
        )
      yield bytes

    def ordered(
        nodePipelineId: Text,
        candidate: ExactCandidateEvidence,
    ): Result[F, VerifiedOrderedExecution] =
      for
        verified <- bound(
          nodePipelineId,
          candidate,
          ExactMode.OrderedAtomic,
          Vector(0, 1),
        )
        _ <- check(
          verified.proposal.plan.waves.exists(wave =>
            wave.kind == WaveKind.Ordered &&
              wave.entries.sliding(2).exists(_ == verified.entries),
          ),
          "exact ordered producer and consumer must be consecutive in one ordered wave",
        )
        first    <- result(verified, 0)
        second   <- result(verified, 1)
        produced <- core(
          verified.authenticated.producerOutput(first),
        )
        outcome <- produced.outcome match
          case Left(error) =>
            EitherT.pure[F, V2RuntimeFailure](
              Left[V2RuntimeFailure, Unit](error),
            )
          case Right(_) =>
            core(
              verified.authenticated.consumerAcceptance(
                produced.output,
                second,
              ),
            )
      yield new VerifiedOrderedExecution(
        verified.record,
        candidate,
        verified.proposal,
        produced.output,
        verified.locks,
        verified.effects,
        outcome,
      )

    def producer(
        nodePipelineId: Text,
        candidate: ExactCandidateEvidence,
    ): Result[F, VerifiedProducerExecution] =
      for
        verified <- bound(
          nodePipelineId,
          candidate,
          ExactMode.CertifiedAncestor,
          Vector(0),
        )
        _ <- check(
          !verified.proposal.plan.waves
            .flatMap(_.entries)
            .exists(entry =>
              verified.record.binding.executionIds
                .lift(1)
                .contains(entry.executionId),
            ),
          "certified ancestor producer candidate also contains its consumer",
        )
        first    <- result(verified, 0)
        produced <- core(
          verified.authenticated.producerOutput(first),
        )
      yield new VerifiedProducerExecution(
        verified.record,
        candidate,
        verified.proposal,
        produced.output,
        verified.locks,
        verified.effects,
        produced.outcome,
      )

    def consumer(
        nodePipelineId: Text,
        candidate: ExactCandidateEvidence,
    ): Result[F, VerifiedConsumerExecution] =
      for
        verified <- bound(
          nodePipelineId,
          candidate,
          ExactMode.CertifiedAncestor,
          Vector(1),
        )
        producerId <- present(
          verified.record.binding.executionIds.headOption,
          "registered producer missing",
        )
        _ <- check(
          !verified.proposal.plan.waves
            .flatMap(_.entries)
            .exists(_.executionId == producerId),
          "ancestor consumer candidate also includes its producer",
        )
        location <- present(
          verified.resolved.producer,
          "certified producer location unavailable",
        )
        lookup <- EitherT.liftF(
          ancestry.lookup(
            AncestorRequest(
              candidate.context,
              candidate.parentBlockId,
              location.blockId,
              producerId,
              verified.record.binding.signedPlanDigest,
              location.normalizedResultDigest,
              verified.record.request.signedPlan.intent.lastInclusionHeight,
            ),
          ),
        )
        proof <- lookup match
          case AncestorLookupResult.Available(value) =>
            EitherT.pure[F, V2RuntimeFailure](value)
          case AncestorLookupResult.Unavailable(reason) =>
            EitherT.leftT[F, VerifiedAncestorProof](reason)
          case AncestorLookupResult.Invalid(reason) =>
            EitherT.leftT[F, VerifiedAncestorProof](reason)
        _ <- check(
          proof.approvedWindow == verified.proposal.proposal.window &&
            proof.producerHeight.toBigNat.toBigInt < verified.proposal.candidateHeight.toBigNat.toBigInt,
          "certified producer proof does not authorize this current candidate window",
        )
        approvedParent <- present(
          proof.certifiedPath.headOption,
          "certified parent path missing",
        )
        _ <- check(
          verified.proposal.candidateHeight.toBigNat.toBigInt == approvedParent.proposal.block.height.toBigNat.toBigInt + 1,
          "consumer does not immediately extend its certified candidate parent",
        )
        _        <- stageBinding(verified.record, proof.producerEntry, 0)
        produced <- core(
          verified.authenticated.producerOutput(
            proof.normalizedOutput.bytes,
          ),
        )
        _        <- EitherT.fromEither[F](produced.outcome)
        consumed <- result(verified, 0)
        outcome  <- core(
          verified.authenticated.consumerAcceptance(
            produced.output,
            consumed,
          ),
        )
      yield new VerifiedConsumerExecution(
        verified.record,
        candidate,
        verified.proposal,
        proof,
        produced.output,
        verified.locks,
        verified.effects,
        outcome,
      )
