package org.sigilaris.node.jvm.runtime.application.v2

import cats.Monad
import cats.data.EitherT
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.{
  ExecutionId,
  NormalizedApplicationResult,
}
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.jvm.runtime.block.BlockHeader
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  HotStuffValidator,
  HotStuffWindow,
  Proposal,
  UnsignedVote,
  ValidatorSet,
}

/** Decoded only after the configured transaction domain and signature policy
  * have been checked. The declaration and common deadline come from signed
  * bytes, never from the requested subject.
  */
final case class SignedApplicationBinding(
    txId: Hash,
    manifestDigest: Hash,
    declaration: Declaration,
    dependencyPlanDigest: Hash,
    lastInclusionHeight: Height,
    exactBinding: Option[Hash],
)

trait TransactionAuthentication[F[_]]:
  def authenticate(
      context: DomainContext,
      manifest: InputManifest,
      signedTransaction: Bytes,
  ): Result[F, SignedApplicationBinding]

/** Resolves every creation target from the signed complete declaration and
  * authenticated entry state, including targets not used by the reducer.
  */
trait DeclaredCreationAuthentication:
  def derive(
      manifest: InputManifest,
      signedTransaction: Bytes,
      entryPreStateRoot: Hash,
      descriptor: InputDescriptor,
  ): Either[CoreFailure, Vector[(InputId, Bytes)]]

final case class LockSourceMaterial(
    descriptor: InputDescriptor,
    signedTransaction: Bytes,
    proofs: Vector[ResolutionEvidence],
)

/** An independently executed entry, including every actual access and the
  * authenticated authorization artifact root in owner.scope. Implementations
  * execute against retained authenticated state; they must not echo requests.
  */
final case class ExecutedApplication(
    entry: PlanEntry,
    descriptor: InputDescriptor,
    proofs: Vector[ResolutionEvidence],
    actualAccesses: Vector[ActualAccess],
    absentCreations: Vector[(InputId, Bytes)],
    normalizedResult: Bytes,
    nextStateRoot: Hash,
    owner: Owner,
    authenticatedStatement: Option[ClassificationStatement],
)

/** Pre-certification fast execution. It deliberately contains no completed plan
  * source or effect-certificate reference, so the first effect quorum can be
  * formed using only its authenticated input-lock certificate.
  */
final case class ExecutedEffect(
    signedTransaction: Bytes,
    inputLockCertificateId: Hash,
    entryPreStateRoot: Hash,
    descriptor: InputDescriptor,
    proofs: Vector[ResolutionEvidence],
    actualAccesses: Vector[ActualAccess],
    absentCreations: Vector[(InputId, Bytes)],
    normalizedResult: Bytes,
    nextStateRoot: Hash,
    owner: Owner,
)

trait ApplicationExecutionRepository[F[_]]:
  def lockSource(
      context: DomainContext,
      executionId: ExecutionId,
  ): Result[F, LockSourceMaterial]
  def lockCertificate(id: Hash): Result[F, LockCertificate]
  def effectCertificate(id: Hash): Result[F, EffectCertificate]
  def executeEffect(
      context: DomainContext,
      executionId: ExecutionId,
  ): Result[F, ExecutedEffect]

  /** A present exact binding must match the durable authenticated admission and
    * its owned stage. None is accepted only for genuinely generic work.
    */
  def verifyExactBinding(
      context: DomainContext,
      executionId: ExecutionId,
      binding: Option[Hash],
  ): Result[F, Unit]

/** Resolve and authenticate the artifact addressed by authorizationDigest. A
  * digest or caller's scope coordinates alone are not branch evidence.
  */
trait ReservationScopeAuthentication[F[_]]:
  def verifyFast(
      owner: Owner,
      base: AdmissionBase,
      source: SignedApplicationBinding,
  ): Result[F, Unit]
  def verifyConsensus(
      owner: Owner,
      candidate: Proposal,
      plan: ExecutionPlan,
      entry: PlanEntry,
  ): Result[F, Unit]

final case class ExecutedProposal(
    candidate: Proposal,
    unsignedVote: UnsignedVote,
    parentStateRoot: Hash,
    body: Vector[BodyMember],
    bodyRoot: Hash,
    entries: Vector[ExecutedApplication],
)

trait ProposalExecutionRepository[F[_]]:
  def historicalValidators(window: HotStuffWindow): Result[F, ValidatorSet]

  /** Authenticate the canonical parent/branch, then execute in plan order.
    * Owner authorization roots bind the verified branch and order evidence.
    * bodyRoot is computed from the actual complete application body/results.
    */
  def executeSequential(
      proposal: Proposal,
      plan: ExecutionPlan,
  ): Result[F, ExecutedProposal]

type VerifiedLockRequest   = ApplicationRequestVerifier.VerifiedLockRequest
type VerifiedEffectRequest = ApplicationRequestVerifier.VerifiedEffectRequest
type VerifiedConsensusProposal =
  ApplicationRequestVerifier.VerifiedConsensusProposal
type VerifiedReservation     = ApplicationRequestVerifier.VerifiedReservation
type VerifiedLockCertificate =
  ApplicationRequestVerifier.VerifiedLockCertificate
type VerifiedEffectCertificate =
  ApplicationRequestVerifier.VerifiedEffectCertificate

trait ApplicationRequestVerifier[F[_]]:
  def verifyLock(
      subject: LockSubject,
      descriptor: InputDescriptor,
      signedTransaction: Bytes,
      proofs: Vector[ResolutionEvidence],
  ): Result[F, VerifiedLockRequest]
  def verifyEffect(
      subject: EffectSubject,
      owner: Owner,
      witness: ReservationWitness,
  ): Result[F, VerifiedEffectRequest]
  def verifyProposal(
      proposal: Proposal,
      plan: ExecutionPlan,
  ): Result[F, VerifiedConsensusProposal]
  def verifyLockCertificate(
      certificate: LockCertificate,
  ): Result[F, VerifiedLockCertificate]
  def verifyEffectCertificate(
      certificate: EffectCertificate,
  ): Result[F, VerifiedEffectCertificate]

/** Capability constructors live inside the authenticated verifier. There is no
  * apply/copy, package constructor, Boolean factory or decoded capability.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object ApplicationRequestVerifier:
  final class VerifiedLockRequest private[ApplicationRequestVerifier] (
      val subject: LockSubject,
      val descriptor: InputDescriptor,
      val signedTransaction: Bytes,
      val proofs: Vector[ResolutionEvidence],
      val exactBinding: Option[Hash],
  )

  final class VerifiedLockCertificate private[ApplicationRequestVerifier] (
      val certificate: LockCertificate,
      val request: VerifiedLockRequest,
  )

  final class VerifiedEffectRequest private[ApplicationRequestVerifier] (
      val subject: EffectSubject,
      val owner: Owner,
      val witness: ReservationWitness,
      val normalizedResult: NormalizedApplicationResult,
      val entryPreStateRoot: Hash,
      val actualFootprint: Footprint,
      val sourceLock: VerifiedLockCertificate,
      val exactBinding: Option[Hash],
  )

  final class VerifiedEffectCertificate private[ApplicationRequestVerifier] (
      val certificate: EffectCertificate,
      val lockCertificate: VerifiedLockCertificate,
      val request: VerifiedEffectRequest,
  )

  final class VerifiedReservation private[ApplicationRequestVerifier] (
      val owner: Owner,
      val witness: ReservationWitness,
      val lastInclusionHeight: Height,
      val actualFootprint: Footprint,
      val descriptor: InputDescriptor,
      val exactBinding: Option[Hash],
  )

  final class VerifiedConsensusProposal private[ApplicationRequestVerifier] (
      val context: DomainContext,
      val proposal: Proposal,
      val plan: ExecutionPlan,
      val unsignedVote: UnsignedVote,
      val candidateHeight: Height,
      val parentBlockId: Hash,
      val parentStateRoot: Hash,
      val bodyRoot: Hash,
      val validatedStateRoot: Hash,
      val reservations: Vector[VerifiedReservation],
      val normalizedResults: Vector[Bytes],
      val lockCertificates: Vector[VerifiedLockCertificate],
      val effectCertificates: Vector[VerifiedEffectCertificate],
  )

  def authenticated[F[_]: Monad](
      manifest: ProtocolManifest,
      inputAuthentication: InputAuthentication,
      declarationAuthentication: DeclaredFootprintAuthentication,
      creationAuthentication: DeclaredCreationAuthentication,
      transactionAuthentication: TransactionAuthentication[F],
      artifactAuthentication: ArtifactAuthentication,
      scopeAuthentication: ReservationScopeAuthentication[F],
      repository: ApplicationExecutionRepository[F],
      proposals: ProposalExecutionRepository[F],
  ): ApplicationRequestVerifier[F] = new ApplicationRequestVerifier[F]:
    private def core[A](result: Either[CoreFailure, A]): Result[F, A] =
      EitherT.fromEither[F](result.leftMap(V2RuntimeFailure.fromCore))
    private def check(condition: Boolean, detail: String): Result[F, Unit] =
      EitherT.fromEither[F](
        RuntimeCheck.require(
          condition,
          RuntimeFailureCode.InvalidRequest,
          detail,
        ),
      )

    private def family(digest: Hash): Result[F, InputManifest] =
      for
        _        <- core(ProtocolManifest.validate(manifest))
        families <- core(
          manifest.families.traverse(value =>
            InputManifest.digest(value).map(_ -> value),
          ),
        )
        result <- EitherT.fromEither[F](
          families
            .collectFirst { case (`digest`, value) => value }
            .toRight(
              V2RuntimeFailure
                .at(RuntimeFailureCode.DomainMismatch, "unknown input manifest"),
            ),
        )
      yield result

    private def binding(
        context: DomainContext,
        family: InputManifest,
        executionId: ExecutionId,
        descriptor: InputDescriptor,
        signed: Bytes,
    ): Result[F, SignedApplicationBinding] =
      for
        selected <- core(ProtocolManifest.context(manifest))
        _        <- check(
          context == selected,
          "request context differs from configured manifest",
        )
        parsed <- transactionAuthentication.authenticate(
          context,
          family,
          signed,
        )
        digest <- core(Declaration.digest(parsed.declaration))
        id     <- core(
          ExecutionIdentity.compute(
            ExecutionIdentityInput(
              context,
              parsed.manifestDigest,
              parsed.txId,
              signed,
              descriptor.fullInputCommitment,
              descriptor.lockSubsetCommitment,
              digest,
              parsed.dependencyPlanDigest,
              parsed.lastInclusionHeight,
            ),
          ),
        )
        _ <- check(
          parsed.manifestDigest == descriptor.manifestDigest && id == executionId,
          "signed source/manifest/execution identity mismatch",
        )
        _ <- repository.verifyExactBinding(
          context,
          executionId,
          parsed.exactBinding,
        )
      yield parsed

    def verifyLock(
        subject: LockSubject,
        descriptor: InputDescriptor,
        signedTransaction: Bytes,
        proofs: Vector[ResolutionEvidence],
    ): Result[F, VerifiedLockRequest] =
      for
        _        <- core(LockSubject.validate(subject))
        selected <- family(subject.manifestDigest)
        parsed   <- binding(
          subject.context,
          selected,
          subject.executionId,
          descriptor,
          signedTransaction,
        )
        _ <- core(
          artifactAuthentication.verifyFinalizedBase(
            subject.context,
            subject.admissionBase,
          ),
        )
        baseRoot = subject.admissionBase match
          case AdmissionBase.Finalized(_, _, root)        => root
          case AdmissionBase.InitialAnchor(_, _, root, _) => root
        _ <- core(
          InputDerivation.validate(
            selected,
            signedTransaction,
            baseRoot,
            descriptor,
            proofs,
            inputAuthentication,
          ),
        )
        locks <- core(InputDerivation.lockInputs(selected, descriptor))
        start = AdmissionBase.height(subject.admissionBase).toBigNat.toBigInt
        end   = subject.lastInclusionHeight.toBigNat.toBigInt
        _ <- check(
          end > start && end <= start + BigInt(manifest.maxLockLifetimeBlocks),
          "signed lock deadline exceeds admission lifetime",
        )
        _ <- check(
          subject.txId == parsed.txId && subject.manifestDigest == parsed.manifestDigest && subject.dependencyPlanDigest == parsed.dependencyPlanDigest && subject.lastInclusionHeight == parsed.lastInclusionHeight && subject.fullInputCommitment == descriptor.fullInputCommitment && subject.lockSubsetCommitment == descriptor.lockSubsetCommitment && subject.inputs == locks
            .map(_.stableId),
          "lock subject differs from independently authenticated source",
        )
      yield new VerifiedLockRequest(
        subject,
        descriptor,
        signedTransaction,
        proofs,
        parsed.exactBinding,
      )

    def verifyLockCertificate(
        certificate: LockCertificate,
    ): Result[F, VerifiedLockCertificate] =
      for
        _ <- core(
          LockCertificate.verify(certificate, manifest, artifactAuthentication),
        )
        source <- repository.lockSource(
          certificate.subject.context,
          certificate.subject.executionId,
        )
        request <- verifyLock(
          certificate.subject,
          source.descriptor,
          source.signedTransaction,
          source.proofs,
        )
      yield new VerifiedLockCertificate(certificate, request)

    private def sourceCertificates(
        entry: PlanEntry,
    ): Result[F, (Option[VerifiedLockCertificate], Option[EffectCertificate])] =
      val (lockId, effectId) = entry.source match
        case PlanSource.ConsensusTransaction(_, _, lock) =>
          (lock, Option.empty[Hash])
        case PlanSource.CertifiedFastExecution(_, _, lock, effect) =>
          (Some(lock), Some(effect))
      for
        lock <- lockId.traverse(id =>
          repository
            .lockCertificate(id)
            .flatMap(certificate =>
              core(LockCertificate.id(certificate))
                .flatMap(actual =>
                  check(actual == id, "lock repository identity mismatch"),
                )
                .flatMap(_ => verifyLockCertificate(certificate)),
            ),
        )
        effect <- effectId.traverse(id =>
          repository
            .effectCertificate(id)
            .flatMap(certificate =>
              core(EffectCertificate.id(certificate))
                .flatMap(actual =>
                  check(actual == id, "effect repository identity mismatch"),
                )
                .as(certificate),
            ),
        )
      yield (lock, effect)

    private def validateExecutedInputs(
        context: DomainContext,
        executionId: ExecutionId,
        signed: Bytes,
        preStateRoot: Hash,
        descriptor: InputDescriptor,
        proofs: Vector[ResolutionEvidence],
        actualAccesses: Vector[ActualAccess],
        absentCreations: Vector[(InputId, Bytes)],
    ): Result[F, (SignedApplicationBinding, Option[Footprint], Footprint)] =
      for
        selected <- family(descriptor.manifestDigest)
        parsed   <- binding(context, selected, executionId, descriptor, signed)
        _        <- core(
          InputDerivation.validate(
            selected,
            signed,
            preStateRoot,
            descriptor,
            proofs,
            inputAuthentication,
          ),
        )
        declared <- core(
          declarationAuthentication.derive(
            selected,
            signed,
            preStateRoot,
            descriptor,
          ),
        )
        declaredCreations <- core(
          creationAuthentication.derive(
            selected,
            signed,
            preStateRoot,
            descriptor,
          ),
        )
        creationIds = declaredCreations.map(_._1)
        existingIds = descriptor.fields.flatMap(_.stableId).toSet
        _ <- check(
          creationIds.map(_.toHex) == creationIds
            .map(_.toHex)
            .sorted
            .distinct && !creationIds.exists(existingIds.contains),
          "declared creations are repeated, unordered or already resolved as existing",
        )
        _ <- check(
          declared.fold(creationIds.isEmpty)(footprint =>
            creationIds.forall(footprint.writes.contains),
          ),
          "declared creation is outside authenticated write declaration",
        )
        _ <- check(
          !actualAccesses.exists(access =>
            creationIds.contains(
              access.identity,
            ) && access.kind != AccessKind.CreateAbsent,
          ),
          "authenticated absent target is also reported as existing state",
        )
        _ <- declaredCreations.traverse_((id, proof) =>
          core(
            inputAuthentication.verifyAbsentCreation(
              selected,
              signed,
              preStateRoot,
              id,
              proof,
            ),
          ),
        )
        _ <- (parsed.declaration, declared) match
          case (Declaration.Exact(expected), Some(value)) =>
            core(Footprint.declaredCommitment(value)).flatMap(actual =>
              check(
                actual == expected,
                "signed exact declaration differs from authenticated footprint",
              ),
            )
          case (Declaration.Compatibility(_), None) =>
            EitherT.pure[F, V2RuntimeFailure](())
          case _ =>
            check(
              false,
              "declaration kind differs from authenticated execution policy",
            )
        expectedCreations = actualAccesses
          .filter(_.kind == AccessKind.CreateAbsent)
          .map(_.identity)
          .distinct
          .sortBy(_.toHex)
        _ <- check(
          absentCreations.map(_._1) == expectedCreations,
          "creation proof coverage differs from actual creations",
        )
        _ <- absentCreations.traverse_((id, proof) =>
          core(
            inputAuthentication
              .verifyAbsentCreation(selected, signed, preStateRoot, id, proof),
          ),
        )
        _ <- check(
          declared.isEmpty || expectedCreations.forall(creationIds.contains),
          "actual creation is absent from authenticated creation declaration",
        )
        // Absence authentication reads a target even if the reducer does not
        // create it. Actual creation events retain their original write mode.
        executedFootprint <- core(
          InputDerivation.validateActual(descriptor, declared, actualAccesses),
        )
        footprint <- core(
          Footprint.canonical(
            (executedFootprint.reads ++ creationIds).distinct,
            executedFootprint.writes,
          ),
        )
      yield (parsed, declared, footprint)

    private def validateExecution(observed: ExecutedApplication): Result[
      F,
      (VerifiedEntry, SignedApplicationBinding, Option[VerifiedLockCertificate]),
    ] =
      val entry = observed.entry
      for
        _       <- core(PlanEntry.validate(entry))
        context <- core(ProtocolManifest.context(manifest))
        inputs  <- validateExecutedInputs(
          context,
          entry.executionId,
          entry.source.signedTransaction,
          entry.entryPreStateRoot,
          observed.descriptor,
          observed.proofs,
          observed.actualAccesses,
          observed.absentCreations,
        )
        (parsed, declared, footprint) = inputs
        _ <- check(
          parsed.txId == entry.source.txId && parsed.manifestDigest == entry.manifestDigest && parsed.declaration == entry.declaration && parsed.dependencyPlanDigest == entry.dependencyPlanDigest && parsed.lastInclusionHeight == entry.lastInclusionHeight,
          "executed entry differs from signed transaction",
        )
        actualDigest <- core(Footprint.actualCommitment(footprint))
        _            <- check(
          actualDigest == entry.actualFootprintCommitment,
          "entry actual footprint differs from instrumented execution",
        )
        certificates <- sourceCertificates(entry)
        (lock, effect) = certificates
        _ <- core(
          AdmissionValidation.validateSource(
            entry,
            observed.descriptor,
            lock.map(_.certificate),
            effect,
            manifest,
            artifactAuthentication,
          ),
        )
        _ <- effect.traverse_(certificate =>
          check(
            certificate.subject.resultDigest == NormalizedApplicationResult
              .fromBytes(observed.normalizedResult)
              .digest && certificate.subject.stateRoot.toUInt256 == observed.nextStateRoot,
            "certified result differs from independent execution",
          ),
        )
        _ <- core(Owner.validate(observed.owner))
        _ <- check(
          observed.owner.context == context && observed.owner.executionId == entry.executionId,
          "executed owner context or execution mismatch",
        )
        statement <- observed.authenticatedStatement.traverse(value =>
          core(ClassificationStatement.commitment(value)),
        )
        _ <- observed.authenticatedStatement.traverse_(value =>
          check(
            value.manifestDigest == entry.manifestDigest && value.txId == entry.source.txId && value.sourceKind == entry.source.tag && value.entryPreStateRoot == entry.entryPreStateRoot && value.declarationDigest == entry.declarationDigest,
            "authenticated classification belongs to another entry or state",
          ),
        )
        _ <- check(
          statement == entry.classificationStatementCommitment,
          "classification proof differs from authenticated execution",
        )
      yield (VerifiedEntry(entry, declared, footprint), parsed, lock)

    private def effectRequest(
        subject: EffectSubject,
        observed: ExecutedEffect,
    ): Result[F, VerifiedEffectRequest] =
      for
        _      <- core(EffectSubject.validate(subject))
        _      <- core(Owner.validate(observed.owner))
        inputs <- validateExecutedInputs(
          observed.owner.context,
          observed.owner.executionId,
          observed.signedTransaction,
          observed.entryPreStateRoot,
          observed.descriptor,
          observed.proofs,
          observed.actualAccesses,
          observed.absentCreations,
        )
        (parsed, _, footprint) = inputs
        source <- repository.lockCertificate(observed.inputLockCertificateId)
        lock   <- verifyLockCertificate(source)
        lockId <- core(LockCertificate.id(lock.certificate))
        _      <- check(
          lockId == observed.inputLockCertificateId && source.subject.context == observed.owner.context && source.subject.executionId == observed.owner.executionId && source.subject.txId == parsed.txId && source.subject.manifestDigest == parsed.manifestDigest && source.subject.fullInputCommitment == observed.descriptor.fullInputCommitment && source.subject.lockSubsetCommitment == observed.descriptor.lockSubsetCommitment && source.subject.dependencyPlanDigest == parsed.dependencyPlanDigest && source.subject.lastInclusionHeight == parsed.lastInclusionHeight,
          "effect input lock differs from authenticated execution source",
        )
        _ <- scopeAuthentication.verifyFast(
          observed.owner,
          source.subject.admissionBase,
          parsed,
        )
        actual <- core(Footprint.actualCommitment(footprint))
        result = NormalizedApplicationResult.fromBytes(
          observed.normalizedResult,
        )
        _ <- check(
          subject.context == observed.owner.context && subject.txId == parsed.txId && subject.executionId == observed.owner.executionId && subject.manifestDigest == parsed.manifestDigest && subject.inputLockCertificateId == lockId && subject.dependencyPlanDigest == parsed.dependencyPlanDigest && subject.lastInclusionHeight == parsed.lastInclusionHeight && subject.resultDigest == result.digest && subject.stateRoot.toUInt256 == observed.nextStateRoot && subject.actualFootprintCommitment == actual,
          "effect subject differs from complete independent execution",
        )
        _ <- check(
          observed.owner.scope.kind == ScopeKind.FastAdmission && (parsed.declaration match
            case Declaration.Exact(_) => true
            case _                    => false),
          "application effect vote requires authenticated fast-admission ownership",
        )
        witness <- core(ReservationWitness.fromFootprint(footprint))
        ref     <- core(WitnessRef.fromWitness(witness))
        _       <- core(WitnessRef.verify(ref, witness))
      yield new VerifiedEffectRequest(
        subject,
        observed.owner,
        witness,
        result,
        observed.entryPreStateRoot,
        footprint,
        lock,
        parsed.exactBinding,
      )

    def verifyEffect(
        subject: EffectSubject,
        owner: Owner,
        witness: ReservationWitness,
    ): Result[F, VerifiedEffectRequest] =
      for
        observed <- repository.executeEffect(
          subject.context,
          subject.executionId,
        )
        result <- effectRequest(subject, observed)
        _      <- check(
          result.owner == owner && result.witness == witness,
          "requested owner/witness differs from independently executed complete coverage",
        )
      yield result

    def verifyEffectCertificate(
        certificate: EffectCertificate,
    ): Result[F, VerifiedEffectCertificate] =
      for
        lock <- repository.lockCertificate(
          certificate.subject.inputLockCertificateId,
        )
        _ <- core(
          EffectCertificate.verify(
            certificate,
            lock,
            manifest,
            artifactAuthentication,
          ),
        )
        observed <- repository.executeEffect(
          certificate.subject.context,
          certificate.subject.executionId,
        )
        request <- effectRequest(certificate.subject, observed)
        _       <- check(
          request.sourceLock.certificate == lock,
          "effect source lock differs from verified certificate",
        )
      yield new VerifiedEffectCertificate(
        certificate,
        request.sourceLock,
        request,
      )

    def verifyProposal(
        proposal: Proposal,
        plan: ExecutionPlan,
    ): Result[F, VerifiedConsensusProposal] =
      for
        context <- core(ProtocolManifest.context(manifest))
        _       <- core(ExecutionPlan.validateShape(plan))
        _       <- EitherT.fromEither[F](
          BlockHeader
            .validateVersionedCommitment(proposal.block)
            .leftMap(error =>
              V2RuntimeFailure
                .at(RuntimeFailureCode.InvalidRequest, error.reason),
            ),
        )
        validators        <- proposals.historicalValidators(proposal.window)
        justifyValidators <- proposals.historicalValidators(
          proposal.justify.subject.window,
        )
        _ <- EitherT.fromEither[F](
          HotStuffValidator
            .validateProposal(proposal, validators, Some(justifyValidators))
            .leftMap(error =>
              V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, error.reason),
            ),
        )
        root <- core(ExecutionPlan.computeRoot(plan))
        _    <- check(
          proposal.window.chainId.value == context.chainId.asString && proposal.window.validatorSetHash.toUInt256 == context.validatorSetHash && proposal.block.executionPlanRoot
            .contains(root),
          "proposal window or plan root differs from active context",
        )
        observed <- proposals.executeSequential(proposal, plan)
        _        <- check(
          observed.candidate == proposal && observed.unsignedVote.window == proposal.window && observed.unsignedVote.targetProposalId == proposal.proposalId && validators
            .member(observed.unsignedVote.voter)
            .nonEmpty,
          "executed candidate or unsigned vote mismatch",
        )
        entries = plan.waves.flatMap(_.entries)
        _ <- check(
          observed.entries.map(_.entry) == entries && observed.body
            .map(_.txId.bytes)
            .toSet == proposal.txSet.txIds
            .map(_.bytes)
            .toSet && observed.bodyRoot == proposal.block.bodyRoot.toUInt256,
          "executed body or entry membership mismatch",
        )
        validated <- observed.entries.traverse(validateExecution)
        lookup = validated
          .map(_._1)
          .map(value => value.entry.executionId -> value)
          .toMap
        authenticated = new EntryAuthentication:
          def verify(
              entry: PlanEntry,
              statement: Option[ClassificationStatement],
          ): Either[CoreFailure, VerifiedEntry] =
            lookup
              .get(entry.executionId)
              .toRight(
                CoreFailure.at(FailureCode.ProofUnavailable, "executedEntry"),
              )
        _ <- core(ExecutionPlan.validate(plan, observed.body, authenticated))
        parent <- EitherT.fromEither[F](
          proposal.block.parent
            .map(_.toUInt256)
            .toRight(
              V2RuntimeFailure.at(
                RuntimeFailureCode.ProofInvalid,
                "ordinary consensus proposal requires authenticated parent",
              ),
            ),
        )
        height = org.sigilaris.core.application.protocol
          .InclusionHeight(proposal.block.height.toBigNat)
        previousRoots = observed.parentStateRoot +: observed.entries.map(
          _.nextStateRoot,
        )
        _ <- check(
          observed.entries.map(_.entry.entryPreStateRoot) == previousRoots
            .dropRight(1) && previousRoots.lastOption.contains(
            proposal.block.stateRoot.toUInt256,
          ),
          "sequential execution pre/post state roots mismatch",
        )
        kinds = plan.waves.flatMap(wave => wave.entries.map(_ => wave.kind))
        reservations <- observed.entries
          .zip(validated)
          .zip(kinds)
          .zipWithIndex
          .traverse { case (((execution, (entry, parsed, _)), kind), index) =>
            val expectedKind = kind match
              case WaveKind.Ordered      => ScopeKind.ConsensusOrdered
              case WaveKind.ConflictFree => ScopeKind.ConsensusConflictFree
              case WaveKind.CompatibilitySingleton =>
                ScopeKind.CompatibilitySingleton
            val scope = execution.owner.scope
            for
              _ <- check(
                scope.kind == expectedKind && scope.parentBlockId == parent && scope.candidateHeight == height && scope.planRoot == root.toUInt256 && scope.entryIndex == index.toLong,
                "consensus owner is not authorized for this candidate/entry/order",
              )
              _ <- check(
                height.toBigNat.toBigInt <= execution.entry.lastInclusionHeight.toBigNat.toBigInt,
                "candidate exceeds signed entry deadline",
              )
              _ <- scopeAuthentication.verifyConsensus(
                execution.owner,
                proposal,
                plan,
                execution.entry,
              )
              witness <- core(
                ReservationWitness.fromFootprint(entry.actualFootprint),
              )
              ref <- core(WitnessRef.fromWitness(witness))
              _   <- core(WitnessRef.verify(ref, witness))
            yield new VerifiedReservation(
              execution.owner,
              witness,
              execution.entry.lastInclusionHeight,
              entry.actualFootprint,
              execution.descriptor,
              parsed.exactBinding,
            )
          }
        effects <- entries
          .flatMap(entry =>
            entry.source match
              case PlanSource.CertifiedFastExecution(_, _, _, id) => Vector(id)
              case _ => Vector.empty[Hash],
          )
          .traverse(id =>
            repository.effectCertificate(id).flatMap(verifyEffectCertificate),
          )
      yield new VerifiedConsensusProposal(
        context,
        proposal,
        plan,
        observed.unsignedVote,
        height,
        parent,
        observed.parentStateRoot,
        observed.bodyRoot,
        proposal.block.stateRoot.toUInt256,
        reservations,
        observed.entries.map(_.normalizedResult),
        validated.flatMap(_._3),
        effects,
      )
