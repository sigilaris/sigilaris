package org.sigilaris.conformance

import java.time.Instant

import cats.data.EitherT
import cats.effect.{IO, Ref}
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.{
  ExecutionId,
  NormalizedApplicationResult,
}
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.given
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.txpipeline.v2.*
import org.sigilaris.node.gossip.StableArtifactId
import org.sigilaris.node.jvm.runtime.block.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*

/** Maintained public neutral material. Source and outer signatures are real;
  * the retained initial checkpoint is an explicitly installed fixture trust
  * anchor. Opaque output handoff never performs an undeclared mutable lookup.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
  ),
)
object V2ExactFixture:
  import V2RequestConformance.*

  final case class ResultBytes(
      format: Long,
      rejected: Byte,
      output: Bytes,
      consumed: Bytes,
      postStateRoot: Hash,
  )
  object ResultBytes:
    given ByteEncoder[ResultBytes]         = ByteEncoder.derived
    given ByteDecoder[ResultBytes]         = ByteDecoder.derived
    val codec: CanonicalCodec[ResultBytes] = CanonicalCodec.derived(result =>
      V2Validation.require(
        result.format == 1L && (result.rejected == 0.toByte || result.rejected == 1.toByte),
        FailureCode.UnsupportedFormat,
        "neutral.exact.result",
      ),
    )

  final case class StateCell(identity: InputId, value: Long)
  object StateCell:
    given ByteEncoder[StateCell] = ByteEncoder.derived
    given ByteDecoder[StateCell] = ByteDecoder.derived
  object StateCells:
    val codec: CanonicalCodec[Vector[StateCell]] =
      CanonicalCodec.derived(cells =>
        V2Validation.sortedUnique(
          cells.map(_.identity.toHex),
          "neutral.exact.state",
        ),
      )

  final class Material(
      val mode: ExactMode,
      val producerEligible: Boolean,
      val consumerEligible: Boolean,
      val nonce: Long,
      val deadline: Height,
      val rejectProducer: Boolean,
      val rejectConsumer: Boolean,
      val admissions: Ref[IO, Map[Hash, ExactPipelineRecord]],
      val sourceOffset: Int,
      suppliedInitialState: Option[Map[InputId, Long]],
  ):
    val seed = new Fixture(
      nonce,
      Authority.ConsensusOnly,
      sourceOffset,
      Vector.empty,
      suppliedInitialState,
      None,
      deadline,
    )
    val initialState    = seed.state
    val initialRoot     = seed.preStateRoot
    val keys            = seed.keys
    val validators      = seed.validators
    val chain           = seed.chain
    val base            = seed.base
    val baseCertificate = seed.baseCertificate
    val baseBlockId     = seed.baseBlockId
    val profileBinding  = DependencyProfileBinding(
      Utf8("neutral.exact.output"),
      1L,
      Utf8("neutral.exact.reference"),
      uint(707),
    )
    val producerFamily =
      seed.family.copy(familyId = Utf8("neutral.exact.producer"))
    val consumerFamily = seed.family.copy(
      familyId = Utf8("neutral.exact.consumer"),
      fields = (seed.family.fields :+ FieldManifest(
        Utf8("output"),
        FieldRole.Opaque,
        uint(708),
        None,
      )).sortBy(_.fieldId.asString),
    )
    val manifest = seed.manifest.copy(
      families = Vector(consumerFamily, producerFamily),
      profiles = Vector(profileBinding),
    )
    val context  = value(ProtocolManifest.context(manifest))
    val outerKey = CryptoOps.fromPrivate(BigInt(19))

    def stateRoot(state: Map[InputId, Long]): Hash = hash(
      state.toVector
        .sortBy(_._1.toHex)
        .foldLeft(ByteVector.empty)((bytes, pair) =>
          bytes ++ pair._1.bytes ++ pair._2.toBytes,
        ),
    )
    final class Source(
        val index: Int,
        val eligible: Boolean,
        val rejected: Boolean,
        reference: Option[Bytes],
    ):
      val offset    = sourceOffset + index * 10
      val balance   = inputId(ByteVector((offset + 1).toByte))
      val counter   = inputId(ByteVector((offset + 2).toByte))
      val read      = seed.readIdentity
      val created   = inputId(ByteVector((offset + 4).toByte))
      val family    = if index == 0 then producerFamily else consumerFamily
      val authority =
        if eligible then Authority.LockEligible else Authority.ConsensusOnly
      val fields = (Vector(
        ResolvedField(
          Utf8("balance"),
          FieldRole.ExactMutate,
          initialState(balance).toBytes,
          Some(balance),
          Some(ExactPrecondition(seed.schema, initialState(balance).toBytes)),
          Some(authority),
        ),
        ResolvedField(
          Utf8("counter"),
          FieldRole.ExactMutate,
          initialState(counter).toBytes,
          Some(counter),
          Some(ExactPrecondition(seed.schema, initialState(counter).toBytes)),
          Some(Authority.ConsensusOnly),
        ),
        ResolvedField(
          Utf8("read"),
          FieldRole.ExactRead,
          initialState(read).toBytes,
          Some(read),
          Some(ExactPrecondition(seed.schema, initialState(read).toBytes)),
          Some(Authority.ConsensusOnly),
        ),
      ) ++ reference.map(bytes =>
        ResolvedField(Utf8("output"), FieldRole.Opaque, bytes, None, None, None),
      )).sortBy(_.fieldId.asString)
      val descriptor = value(InputDerivation.derive(family, fields))
      val footprint  = value(
        Footprint.canonical(Vector(read), Vector(balance, counter, created)),
      )
      val declaration =
        Declaration.Exact(value(Footprint.declaredCommitment(footprint)))
      val key     = CryptoOps.fromPrivate(BigInt(17 + index))
      val payload = Utf8("neutral.exact.source.v1").toBytes ++ value(
        DomainContext.codec.encode(context),
      ) ++
        value(
          AdmissionBase.codec.encode(base),
        ) ++ (nonce + index).toBytes ++ (if rejected then 1.toByte
                                         else 0.toByte).toBytes ++
        value(InputDescriptor.codec.encode(descriptor)) ++ value(
          Declaration.codec.encode(declaration),
        ) ++ deadline.toBytes
      val signedTransaction = payload ++ signed(key, payload)
      val txId              = hash(signedTransaction)
      def proofs(root: Hash): Vector[ResolutionEvidence] = fields
        .filter(_.stableId.nonEmpty)
        .map(field =>
          ResolutionEvidence(
            field.fieldId,
            hash(value(ResolvedField.codec.encode(field)) ++ root.bytes).bytes,
          ),
        )
      def absence(root: Hash): Bytes = hash(root.bytes ++ created.bytes).bytes
      val intent                     = StageIntent(
        txId,
        signedTransaction,
        descriptor.manifestDigest,
        1.toByte,
        declaration,
        descriptor.fullInputCommitment,
        descriptor.lockSubsetCommitment,
      )
      def verifySignature(raw: Bytes): Boolean =
        raw == signedTransaction && raw.dropRight(72L) == payload && recovered(
          raw.takeRight(72L),
          payload,
          key,
        )

    val producer = new Source(0, producerEligible, rejectProducer, None)
    def referenceFor(rawProducer: Bytes, slot: Long): Bytes = hash(
      Utf8("neutral.exact.reference.v1").toBytes ++ rawProducer ++ slot.toBytes,
    ).bytes
    val reference = referenceFor(producer.signedTransaction, 0L)
    val consumer  =
      new Source(1, consumerEligible, rejectConsumer, Some(reference))
    val sources = Vector(producer, consumer)
    val intent  = ExactPlanIntent(
      2L,
      context,
      Utf8("neutral-pipeline-" + nonce.toString),
      profileBinding,
      Utf8("lane-a"),
      mode,
      sources.map(_.intent),
      0L,
      1L,
      0L,
      Utf8("output"),
      uint(708),
      reference,
      deadline,
    )
    val signedPlan = SignedExactPlan(
      intent,
      signed(outerKey, value(ExactPlanIntent.signingBytes(intent))),
    )
    val request = ExactSubmitRequest(
      3L,
      signedPlan,
      Some(Utf8("neutral-key-" + nonce.toString)),
    )
    val planDigest   = value(SignedExactPlan.digest(signedPlan))
    val executionIds = value(
      ExactIdentityBinding.derive(Utf8("unallocated-fixture-id"), signedPlan),
    ).executionIds

    final case class Trace(
        state: Map[InputId, Long],
        accesses: Vector[ActualAccess],
    ):
      def read(id: InputId, authority: Authority): (Long, Trace) =
        state(id) -> copy(accesses =
          accesses :+ ActualAccess(id, AccessKind.ReadExisting, Some(authority)),
        )
      def write(id: InputId, value: Long, authority: Authority): Trace =
        require(state.contains(id))
        copy(
          state = state.updated(id, value),
          accesses = accesses :+ ActualAccess(
            id,
            AccessKind.MutateExisting,
            Some(authority),
          ),
        )
      def create(id: InputId, value: Long): Trace =
        require(!state.contains(id))
        copy(
          state = state.updated(id, value),
          accesses = accesses :+ ActualAccess(id, AccessKind.CreateAbsent, None),
        )
    final case class Execution(
        source: Source,
        before: Map[InputId, Long],
        trace: Trace,
        result: NormalizedApplicationResult,
    ):
      val beforeRoot = stateRoot(before)
      val afterRoot  = stateRoot(trace.state)
      val footprint  = value(
        InputDerivation.validateActual(
          source.descriptor,
          Some(source.footprint),
          trace.accesses,
        ),
      )
      val witness = value(ReservationWitness.fromFootprint(footprint))
    def execute(
        source: Source,
        before: Map[InputId, Long],
        output: Bytes,
    ): Execution =
      val start        = Trace(before, Vector.empty)
      val (balance, a) = start.read(source.balance, source.authority)
      val b            = a.write(source.balance, balance - 1L, source.authority)
      val (counter, c) = b.read(source.counter, Authority.ConsensusOnly)
      val d = c.write(source.counter, counter + 1L, Authority.ConsensusOnly)
      val (read, e) = d.read(source.read, Authority.ConsensusOnly)
      val completed = e.create(source.created, read)
      val returned  =
        if source.rejected then completed.copy(state = before) else completed
      val ownOutput =
        (balance - 1L).toBytes ++ (counter + 1L).toBytes ++ read.toBytes
      val raw = ResultBytes(
        1L,
        if source.rejected then 1.toByte else 0.toByte,
        ownOutput,
        output,
        stateRoot(returned.state),
      )
      Execution(
        source,
        before,
        returned,
        NormalizedApplicationResult.fromBytes(
          value(ResultBytes.codec.encode(raw)),
        ),
      )
    val producerExecution = execute(producer, initialState, ByteVector.empty)
    val producerOutput    = value(
      ResultBytes.codec.decode(producerExecution.result.bytes),
    ).output
    val consumerExecution =
      execute(consumer, producerExecution.trace.state, producerOutput)
    val executions = Vector(producerExecution, consumerExecution)
    private def source(raw: Bytes): Either[CoreFailure, Source] = sources
      .find(_.signedTransaction == raw)
      .toRight(
        CoreFailure.at(FailureCode.ProofUnavailable, "neutral.exact.source"),
      )
    private def knownState(root: Hash): Option[Map[InputId, Long]] =
      Vector(
        initialState,
        producerExecution.trace.state,
        consumerExecution.trace.state,
      ).find(stateRoot(_) == root)

    val artifacts = new ArtifactAuthentication:
      def historicalValidators(
          actual: DomainContext,
      ): Either[CoreFailure, Vector[Text]] =
        if actual == context then
          seed.artifacts.historicalValidators(seed.context)
        else Left(CoreFailure.at(FailureCode.ProofInvalid, "exact.context"))
      def verifySignature(
          actual: DomainContext,
          signer: Text,
          preimage: Bytes,
          signature: Bytes,
      ): Either[CoreFailure, Unit] =
        if actual == context then
          seed.artifacts.verifySignature(
            seed.context,
            signer,
            preimage,
            signature,
          )
        else Left(CoreFailure.at(FailureCode.InvalidSignature, "exact.context"))
      def verifyFinalizedBase(
          actual: DomainContext,
          supplied: AdmissionBase,
      ): Either[CoreFailure, Unit] =
        if actual == context && supplied == base then
          seed.artifacts.verifyFinalizedBase(seed.context, supplied)
        else
          Left(CoreFailure.at(FailureCode.ProofInvalid, "exact.finalizedBase"))
    val inputs = new InputAuthentication:
      def verify(
          family: InputManifest,
          raw: Bytes,
          root: Hash,
          field: ResolvedField,
          proof: ResolutionEvidence,
      ): Either[CoreFailure, Unit] =
        source(raw).flatMap(source =>
          Either.cond(
            source.family == family && source.verifySignature(
              raw,
            ) && source.fields.contains(field) &&
              knownState(root).exists(state =>
                field.stableId.forall(id =>
                  state.get(id).exists(_.toBytes == field.value),
                ),
              ) &&
              (if field.role == FieldRole.Opaque then
                 proof == ResolutionEvidence(field.fieldId, ByteVector.empty)
               else source.proofs(root).contains(proof)),
            (),
            CoreFailure.at(FailureCode.ProofInvalid, "exact.signedField"),
          ),
        )
      def verifyAbsentCreation(
          family: InputManifest,
          raw: Bytes,
          root: Hash,
          identity: InputId,
          proof: Bytes,
      ): Either[CoreFailure, Unit] =
        source(raw).flatMap(source =>
          Either.cond(
            family == source.family && source.verifySignature(
              raw,
            ) && identity == source.created &&
              knownState(root).exists(!_.contains(identity)) && proof == source
                .absence(root),
            (),
            CoreFailure.at(FailureCode.ProofInvalid, "exact.absence"),
          ),
        )
    val declarations = new DeclaredFootprintAuthentication:
      def derive(
          family: InputManifest,
          raw: Bytes,
          root: Hash,
          descriptor: InputDescriptor,
      ): Either[CoreFailure, Option[Footprint]] =
        source(raw).flatMap(source =>
          Either.cond(
            source.family == family && source.descriptor == descriptor && source
              .verifySignature(raw) && knownState(root).nonEmpty,
            Some(source.footprint),
            CoreFailure.at(FailureCode.ProofInvalid, "exact.declaration"),
          ),
        )
    val creations = new DeclaredCreationAuthentication:
      def derive(
          family: InputManifest,
          raw: Bytes,
          root: Hash,
          descriptor: InputDescriptor,
      ): Either[CoreFailure, Vector[(InputId, Bytes)]] =
        source(raw).flatMap(source =>
          Either.cond(
            source.family == family && source.descriptor == descriptor && source
              .verifySignature(raw) && knownState(root).nonEmpty,
            Vector(source.created -> source.absence(root)),
            CoreFailure.at(FailureCode.ProofInvalid, "exact.creations"),
          ),
        )
    val stages = new ExactStageAuthentication:
      def authenticate(
          actual: DomainContext,
          family: InputManifest,
          raw: Bytes,
      ): Either[CoreFailure, ExactStageMaterial] =
        source(raw).flatMap(source =>
          Either.cond(
            actual == context && source.family == family && source
              .verifySignature(raw),
            ExactStageMaterial(
              source.txId,
              source.descriptor,
              initialRoot,
              base,
              source.proofs(initialRoot),
              source.declaration,
              deadline,
            ),
            CoreFailure.at(FailureCode.InvalidSignature, "exact.stageSignature"),
          ),
        )
    val profile = new ExactProfileAuthentication:
      def verifyAuthorization(
          signedPlan: SignedExactPlan,
          selected: ProtocolManifest,
          preimage: Bytes,
      ): Either[CoreFailure, Unit] =
        Either.cond(
          selected == manifest && signedPlan.intent.context == context && ValidatorSignature
            .validate(
              ValidatorSignature(Utf8("outer"), signedPlan.authorization),
            )
            .isRight && recovered(
            signedPlan.authorization,
            preimage,
            outerKey,
          ),
          (),
          CoreFailure.at(FailureCode.InvalidSignature, "exact.outerSignature"),
        )
      def deriveReference(
          signedPlan: SignedExactPlan,
          first: ExactStageMaterial,
          second: ExactStageMaterial,
      ): Either[CoreFailure, Bytes] =
        Either.cond(
          signedPlan.intent.profile == profileBinding && signedPlan.intent.laneId == Utf8(
            "lane-a",
          ) &&
            signedPlan.intent.outputSlot == 0L && first.txId == producer.txId && second.txId == consumer.txId,
          referenceFor(
            producer.signedTransaction,
            signedPlan.intent.outputSlot,
          ),
          CoreFailure.at(FailureCode.ProofInvalid, "exact.profileReference"),
        )
      def producerOutput(
          signedPlan: SignedExactPlan,
          raw: Bytes,
      ): Either[CoreFailure, ExactOutputEvaluation] =
        ResultBytes.codec
          .decode(raw)
          .map(result =>
            ExactOutputEvaluation(
              result.output,
              Either.cond(
                result.rejected == 0.toByte,
                (),
                V2RuntimeFailure.at(
                  RuntimeFailureCode.InvalidRequest,
                  "deterministic producer rejection",
                ),
              ),
            ),
          )
      def consumerAcceptance(
          signedPlan: SignedExactPlan,
          output: Bytes,
          raw: Bytes,
      ): Either[CoreFailure, Either[V2RuntimeFailure, Unit]] =
        ResultBytes.codec
          .decode(raw)
          .flatMap(result =>
            Either.cond(
              result.consumed == output,
              Either.cond(
                result.rejected == 0.toByte,
                (),
                V2RuntimeFailure.at(
                  RuntimeFailureCode.InvalidRequest,
                  "deterministic consumer rejection",
                ),
              ),
              CoreFailure
                .at(FailureCode.ProofInvalid, "exact.actualOutputConsumption"),
            ),
          )
    val plans = ExactPlanAuthentication.authenticated(
      stages,
      inputs,
      declarations,
      creations,
      profile,
      artifacts,
    )

    def quorum(preimage: Bytes): Vector[ValidatorSignature] = keys
      .take(3)
      .map((id, key) => ValidatorSignature(id, signed(key, preimage)))
    def lockSubject(source: Source): LockSubject = LockSubject(
      context,
      source.txId,
      executionIds(source.index),
      source.descriptor.manifestDigest,
      base,
      source.descriptor.fullInputCommitment,
      source.descriptor.lockSubsetCommitment,
      planDigest,
      deadline,
      value(InputDerivation.lockInputs(source.family, source.descriptor))
        .map(_.stableId),
    )
    lazy val lockCertificates: Vector[LockCertificate] = sources
      .filter(_.eligible)
      .map(source =>
        val subject = lockSubject(source)
        LockCertificate(
          subject,
          quorum(value(LockSubject.signingPreimage(subject))),
        ),
      )
    def lockId(source: Source): Option[Hash] = lockCertificates
      .find(
        _.subject.executionId == executionIds(source.index),
      )
      .map(certificate => value(LockCertificate.id(certificate)))
    def planEntry(execution: Execution): PlanEntry =
      val source = execution.source
      PlanEntry(
        executionIds(source.index),
        PlanSource.ConsensusTransaction(
          source.txId,
          source.signedTransaction,
          lockId(source),
        ),
        source.descriptor.manifestDigest,
        source.declaration,
        value(Declaration.digest(source.declaration)),
        source.descriptor.fullInputCommitment,
        source.descriptor.lockSubsetCommitment,
        value(Footprint.actualCommitment(execution.footprint)),
        planDigest,
        deadline,
        execution.beforeRoot,
        None,
      )

    /** The candidate is built by replaying the neutral reducer over retained
      * state; its body and header commitments are independently computed. Its
      * QC has three real signatures. Separate voter-store tests exercise quorum
      * collection; this helper supplies untrusted cryptographic evidence.
      */
    final class Candidate(
        val selected: Vector[Execution],
        val parentCertificate: QuorumCertificate,
        val parentStateRoot: Hash,
        val blockHeight: Long,
        val producerLocation: Option[ExactProducerLocation],
    ):
      val entries = selected.map(planEntry)
      val plan    = if entries.isEmpty then ExecutionPlan.empty
      else
        ExecutionPlan(
          2L,
          Vector(ExecutionWave(WaveKind.Ordered, entries)),
          Vector.empty,
        )
      val planRoot       = value(ExecutionPlan.computeRoot(plan))
      val finalStateRoot =
        selected.lastOption.fold(parentStateRoot)(_.afterRoot)
      val state =
        selected.lastOption.fold(knownState(parentStateRoot).get)(_.trace.state)
      val statePayload = value(
        StateCells.codec.encode(
          state.toVector
            .sortBy(_._1.toHex)
            .map((id, number) => StateCell(id, number)),
        ),
      )
      val body = BlockBody[Hash, Hash, Bytes](
        selected
          .map(execution =>
            BlockRecord(
              execution.source.txId,
              Some(execution.result.digest.toUInt256),
              Vector.empty[Bytes],
            ),
          )
          .toSet,
      )
      val bodyRoot = BlockBody.computeBodyRoot(body).toOption.get
      val header   = BlockHeader(
        Some(parentCertificate.subject.blockId),
        BlockHeight.unsafeFromLong(blockHeight),
        StateRoot(finalStateRoot),
        bodyRoot,
        BlockTimestamp.unsafeFromEpochMillis(2000L + blockHeight),
        BlockHeaderVersion.V2,
        Some(planRoot),
      )
      val window = HotStuffWindow
        .fromLongs(chain, blockHeight, 0L, validators.hash)
        .toOption
        .get
      val proposal = Proposal
        .sign(
          UnsignedProposal(
            window,
            ValidatorId.unsafe("v1"),
            BlockHeader.computeId(header),
            header,
            ProposalTxSet(
              selected.map(execution =>
                StableArtifactId
                  .fromBytes(execution.source.txId.bytes)
                  .toOption
                  .get,
              ),
            ),
            parentCertificate,
          ),
          keys.head._2,
        )
        .toOption
        .get
      val certificateSubject = QuorumCertificateSubject(
        window,
        proposal.proposalId,
        proposal.targetBlockId,
      )
      val certificate = QuorumCertificateAssembler
        .assemble(
          certificateSubject,
          keys
            .take(3)
            .map((id, key) =>
              Vote
                .sign(
                  UnsignedVote(
                    window,
                    ValidatorId.unsafe(id.asString),
                    proposal.proposalId,
                  ),
                  key,
                )
                .toOption
                .get,
            ),
          validators,
        )
        .toOption
        .get
      val history  = AncestorHistoryEntry(context, proposal, plan, certificate)
      val evidence = ExactCandidateEvidence(
        context,
        parentCertificate.subject.blockId.toUInt256,
        height(blockHeight),
        entries,
        Vector.empty,
      )
      def owner(entry: PlanEntry, index: Int): Owner = Owner(
        context,
        entry.executionId,
        Scope(
          ScopeKind.ConsensusOrdered,
          parentCertificate.subject.blockId.toUInt256,
          height(blockHeight),
          planRoot.toUInt256,
          index.toLong,
          hash(
            parentCertificate.toBytes ++ value(
              PlanEntry.codec.encode(entry),
            ) ++ planRoot.toBytes,
          ),
        ),
      )
      def replay: ExecutedProposal =
        val actual = selected.foldLeft(
          (parentStateRoot, Vector.empty[ExecutedApplication]),
        ) { (acc, selected) =>
          require(acc._1 == selected.beforeRoot)
          val replayed = execute(
            selected.source,
            selected.before,
            if selected.source.index == 0 then ByteVector.empty
            else producerOutput,
          )
          require(replayed == selected)
          val entry = planEntry(replayed)
          val index = acc._2.size
          replayed.afterRoot -> (acc._2 :+ ExecutedApplication(
            entry,
            selected.source.descriptor,
            selected.source.proofs(selected.beforeRoot),
            replayed.trace.accesses,
            Vector(
              selected.source.created -> selected.source
                .absence(selected.beforeRoot),
            ),
            replayed.result.bytes,
            replayed.afterRoot,
            owner(entry, index),
            None,
          ))
        }
        require(actual._1 == finalStateRoot)
        ExecutedProposal(
          proposal,
          UnsignedVote(window, ValidatorId.unsafe("v1"), proposal.proposalId),
          parentStateRoot,
          selected.map(execution =>
            BodyMember(
              execution.source.txId,
              executionIds(execution.source.index),
            ),
          ),
          bodyRoot.toUInt256,
          actual._2,
        )

    lazy val emptyCandidate =
      new Candidate(Vector.empty, baseCertificate, initialRoot, 6L, None)
    lazy val emptyTail = descendants(emptyCandidate, 12L)
    lazy val ordered   =
      new Candidate(executions, baseCertificate, initialRoot, 6L, None)
    lazy val producerCandidate = new Candidate(
      Vector(producerExecution),
      baseCertificate,
      initialRoot,
      6L,
      None,
    )
    lazy val producerLocation = Some(
      ExactProducerLocation(
        producerCandidate.proposal.targetBlockId.toUInt256,
        producerExecution.result.digest.toUInt256,
      ),
    )
    lazy val adjacentConsumer = new Candidate(
      Vector(consumerExecution),
      producerCandidate.certificate,
      producerExecution.afterRoot,
      7L,
      producerLocation,
    )
    lazy val emptySeven = new Candidate(
      Vector.empty,
      producerCandidate.certificate,
      producerExecution.afterRoot,
      7L,
      None,
    )
    lazy val emptyEight = new Candidate(
      Vector.empty,
      emptySeven.certificate,
      producerExecution.afterRoot,
      8L,
      None,
    )
    lazy val distantConsumer = new Candidate(
      Vector(consumerExecution),
      emptyEight.certificate,
      producerExecution.afterRoot,
      9L,
      producerLocation,
    )
    def descendants(start: Candidate, through: Long): Vector[Candidate] =
      (start.blockHeight + 1L to through)
        .foldLeft(Vector(start))((path, nextHeight) =>
          val parent = path.last
          path :+ new Candidate(
            Vector.empty,
            parent.certificate,
            parent.finalStateRoot,
            nextHeight,
            None,
          ),
        )
        .tail
    lazy val orderedTail   = descendants(ordered, 12L)
    lazy val producerTail  = descendants(producerCandidate, 12L)
    lazy val adjacentTail  = descendants(adjacentConsumer, 12L)
    lazy val distantTail   = descendants(distantConsumer, 12L)
    lazy val allCandidates = (Vector(
      emptyCandidate,
      ordered,
      producerCandidate,
      adjacentConsumer,
      emptySeven,
      emptyEight,
      distantConsumer,
    ) ++ emptyTail ++ orderedTail ++ producerTail ++ adjacentTail ++ distantTail)
      .distinctBy(_.proposal.targetBlockId)

    /** An independently retained canonical admission mirror. It is populated
      * only after the real shared journal returns admission; recovery also
      * authenticates the authoritative journal's registration and ownership.
      * Reading this mirror cannot recursively acquire the shared safety gate.
      */
    def retainAdmission(record: ExactPipelineRecord): IO[Unit] = IO {
      require(record.admissionJournalSequence.nonEmpty)
      value(ExactPipelineRecord.validate(record))
      value(
        ExactIdentityBinding.verify(record.binding, record.request.signedPlan),
      )
      require(
        value(
          plans.verify(record.request.signedPlan, manifest),
        ).signedPlan == record.request.signedPlan,
      )
      require(record.binding.signedPlanDigest == planDigest)
    } *> admissions.update(_.updated(planDigest, record))

    def registered: Result[IO, ExactPipelineRecord] = EitherT(
      admissions.get.map(
        _.get(planDigest).toRight(
          V2RuntimeFailure.at(
            RuntimeFailureCode.EvidenceMissing,
            "actual exact admission is not retained",
          ),
        ),
      ),
    )
    val transactions = new TransactionAuthentication[IO]:
      def authenticate(
          actual: DomainContext,
          family: InputManifest,
          raw: Bytes,
      ): Result[IO, SignedApplicationBinding] =
        for
          source <- EitherT.fromEither[IO](
            Material.this.source(raw).left.map(V2RuntimeFailure.fromCore),
          )
          record <- registered
          _      <- EitherT.fromEither[IO](
            Either.cond(
              actual == context && family == source.family && source
                .verifySignature(raw),
              (),
              V2RuntimeFailure.at(
                RuntimeFailureCode.InvalidSignature,
                "exact signed source invalid",
              ),
            ),
          )
        yield SignedApplicationBinding(
          source.txId,
          source.descriptor.manifestDigest,
          source.declaration,
          record.binding.signedPlanDigest,
          deadline,
          Some(value(ExactIdentityBinding.digest(record.binding))),
        )

    def repository(
        availableLocks: Set[Hash],
    ): ApplicationExecutionRepository[IO] =
      new ApplicationExecutionRepository[IO]:
        def lockSource(
            actual: DomainContext,
            execution: ExecutionId,
        ): Result[IO, LockSourceMaterial] =
          sources
            .find(source =>
              actual == context && executionIds(source.index) == execution,
            )
            .fold(unavailable[LockSourceMaterial]("exact lock source"))(
              source =>
                EitherT.pure(
                  LockSourceMaterial(
                    source.descriptor,
                    source.signedTransaction,
                    source.proofs(initialRoot),
                  ),
                ),
            )
        def lockCertificate(id: Hash): Result[IO, LockCertificate] =
          lockCertificates
            .find(certificate =>
              availableLocks.contains(id) && value(
                LockCertificate.id(certificate),
              ) == id,
            )
            .fold(
              unavailable[LockCertificate]("required lock quorum unavailable"),
            )(EitherT.pure(_))
        def effectCertificate(id: Hash): Result[IO, EffectCertificate] =
          unavailable("consensus exact path has no effect certificates")
        def executeEffect(
            actual: DomainContext,
            execution: ExecutionId,
        ): Result[IO, ExecutedEffect] = unavailable(
          "consensus exact stage has no fast effect",
        )
        def verifyExactBinding(
            actual: DomainContext,
            execution: ExecutionId,
            binding: Option[Hash],
        ): Result[IO, Unit] =
          registered.flatMap(record =>
            EitherT.fromEither[IO](
              Either.cond(
                actual == context && record.binding.executionIds
                  .contains(execution) && binding.contains(
                  value(ExactIdentityBinding.digest(record.binding)),
                ),
                (),
                V2RuntimeFailure.at(
                  RuntimeFailureCode.ProofInvalid,
                  "stage is not owned by its durable authenticated exact admission",
                ),
              ),
            ),
          )

    val scopes = new ReservationScopeAuthentication[IO]:
      def verifyFast(
          owner: Owner,
          supplied: AdmissionBase,
          source: SignedApplicationBinding,
      ): Result[IO, Unit] = unavailable("no fast exact source configured")
      def verifyConsensus(
          owner: Owner,
          proposal: Proposal,
          plan: ExecutionPlan,
          entry: PlanEntry,
      ): Result[IO, Unit] =
        EitherT.fromEither[IO](
          Either.cond(
            allCandidates.exists(candidate =>
              candidate.proposal == proposal && candidate.plan == plan && candidate.entries.zipWithIndex
                .exists((expected, index) =>
                  expected == entry && candidate.owner(expected, index) == owner,
                ) &&
                HotStuffValidator
                  .validateProposal(proposal, validators)
                  .isRight &&
                HotStuffValidator
                  .validateQuorumCertificate(
                    candidate.parentCertificate,
                    validators,
                  )
                  .isRight,
            ),
            (),
            V2RuntimeFailure.at(
              RuntimeFailureCode.ProofInvalid,
              "exact owner authorization does not authenticate candidate/entry/branch",
            ),
          ),
        )
    val proposalRepository = new ProposalExecutionRepository[IO]:
      def historicalValidators(
          window: HotStuffWindow,
      ): Result[IO, ValidatorSet] =
        if window.chainId == chain && window.validatorSetHash == validators.hash
        then EitherT.pure(validators)
        else unavailable("exact historical validators")
      def executeSequential(
          proposal: Proposal,
          plan: ExecutionPlan,
      ): Result[IO, ExecutedProposal] =
        allCandidates
          .find(candidate =>
            candidate.proposal == proposal && candidate.plan == plan &&
              HotStuffValidator.validateProposal(proposal, validators).isRight,
          )
          .fold(
            unavailable[ExecutedProposal](
              "actual exact candidate and state unavailable",
            ),
          )(candidate => EitherT.pure(candidate.replay))
    def verifier(availableLocks: Set[Hash]): ApplicationRequestVerifier[IO] =
      ApplicationRequestVerifier.authenticated(
        manifest,
        inputs,
        declarations,
        creations,
        transactions,
        artifacts,
        scopes,
        repository(availableLocks),
        proposalRepository,
      )
    lazy val requests = verifier(
      lockCertificates
        .map(certificate => value(LockCertificate.id(certificate)))
        .toSet,
    )
    val candidateRepository = new ExactCandidateRepository[IO]:
      def resolve(
          evidence: ExactCandidateEvidence,
      ): Result[IO, ResolvedExactCandidate] =
        allCandidates
          .find(_.evidence == evidence)
          .fold(
            unavailable[ResolvedExactCandidate]("exact candidate evidence"),
          )(candidate =>
            EitherT.pure(
              ResolvedExactCandidate(
                candidate.proposal,
                candidate.plan,
                candidate.producerLocation,
              ),
            ),
          )
      def lockCertificate(id: Hash): Result[IO, LockCertificate] = repository(
        lockCertificates.map(c => value(LockCertificate.id(c))).toSet,
      ).lockCertificate(id)
      def effectCertificate(id: Hash): Result[IO, EffectCertificate] =
        unavailable("consensus source cannot need fast effect evidence")

    val anchor =
      ApplicationAnchor(context, baseBlockId.toUInt256, height(5L), initialRoot)
    val validatorLookup = ValidatorSetLookup.static[IO](
      BootstrapTrustRoot.staticValidatorSet(validators),
    )
    val stateAuthentication = new ApplicationStateAuthentication[IO]:
      def authenticate(
          request: VerifiedConsensusProposal,
          payload: Bytes,
      ): Result[IO, AuthenticatedApplicationState] =
        for
          cells <- EitherT.fromEither[IO](
            StateCells.codec.decode(payload).left.map(V2RuntimeFailure.fromCore),
          )
          candidate <- EitherT.fromOption[IO](
            allCandidates.find(candidate =>
              candidate.proposal == request.proposal && candidate.plan == request.plan,
            ),
            V2RuntimeFailure.at(
              RuntimeFailureCode.ProofUnavailable,
              "actual exact state candidate unavailable",
            ),
          )
          actual  = candidate.replay
          decoded = cells.map(cell => cell.identity -> cell.value).toMap
          _ <- EitherT.fromEither[IO](
            Either.cond(
              decoded == candidate.state && stateRoot(
                decoded,
              ) == candidate.finalStateRoot && actual.entries.map(
                _.normalizedResult,
              ) == request.normalizedResults,
              (),
              V2RuntimeFailure.at(
                RuntimeFailureCode.ProofInvalid,
                "state payload differs from actual sequential replay",
              ),
            ),
          )
        yield AuthenticatedApplicationState(
          payload,
          stateRoot(decoded),
          actual.entries.map(_.normalizedResult),
        )

    def ancestry(
        approved: Ref[IO, Option[Proposal]],
        retainedHistory: Ref[IO, Map[Hash, AncestorHistoryEntry]],
        backfilled: Ref[IO, Map[Hash, AncestorHistoryEntry]],
    ): CanonicalAncestorLookup[IO] =
      val history = new AncestorHistoryRepository[IO]:
        def retained(
            actual: DomainContext,
            id: Hash,
        ): Result[IO, Option[AncestorHistoryEntry]] =
          if actual == context then
            EitherT.liftF(retainedHistory.get.map(_.get(id)))
          else unavailable("retained exact history context")
        def backfill(
            actual: DomainContext,
            id: Hash,
        ): Result[IO, Option[AncestorHistoryEntry]] =
          if actual == context then EitherT.liftF(backfilled.get.map(_.get(id)))
          else unavailable("backfill exact history context")
      val profiles = new AncestorProfileRepository[IO]:
        def historical(
            actual: DomainContext,
            window: HotStuffWindow,
        ): Result[IO, AncestorApplicationProfile[IO]] =
          if actual == context && window.chainId == chain && window.validatorSetHash == validators.hash
          then EitherT.pure(AncestorApplicationProfile(manifest, requests))
          else unavailable("exact historical profile")
      val approval = new ApprovedAncestorParentSource[IO]:
        def current(actual: DomainContext): Result[IO, HotStuffPacemakerState] =
          EitherT(
            approved.get.map(
              _.filter(_ => actual == context)
                .map(candidate =>
                  HotStuffPacemakerState(
                    candidate.window,
                    ValidatorId.unsafe("v1"),
                    candidate.justify,
                    Instant.ofEpochSecond(1000),
                    TimeoutVoteAccumulator.empty,
                    None,
                    false,
                    None,
                    false,
                    None,
                    0,
                  ),
                )
                .toRight(
                  V2RuntimeFailure.at(
                    RuntimeFailureCode.RecoveryRequired,
                    "exact current pacemaker candidate unavailable",
                  ),
                ),
            ),
          )
      CanonicalAncestorLookup.authenticated(
        context,
        history,
        profiles,
        ValidatorSetLookup.static[IO](
          BootstrapTrustRoot.staticValidatorSet(validators),
        ),
        approval,
        AncestorLookupCapacity(16L),
      )

  def material(
      mode: ExactMode,
      producerEligible: Boolean,
      consumerEligible: Boolean,
      nonce: Long,
      deadline: Height,
      rejectProducer: Boolean,
      rejectConsumer: Boolean,
  ): IO[Material] =
    Ref
      .of[IO, Map[Hash, ExactPipelineRecord]](Map.empty)
      .map(admissions =>
        new Material(
          mode,
          producerEligible,
          consumerEligible,
          nonce,
          deadline,
          rejectProducer,
          rejectConsumer,
          admissions,
          0,
          None,
        ),
      )

  def materialAt(
      mode: ExactMode,
      producerEligible: Boolean,
      consumerEligible: Boolean,
      nonce: Long,
      deadline: Height,
      rejectProducer: Boolean,
      rejectConsumer: Boolean,
      sourceOffset: Int,
      initialState: Map[InputId, Long],
  ): IO[Material] = Ref
    .of[IO, Map[Hash, ExactPipelineRecord]](Map.empty)
    .map(admissions =>
      new Material(
        mode,
        producerEligible,
        consumerEligible,
        nonce,
        deadline,
        rejectProducer,
        rejectConsumer,
        admissions,
        sourceOffset,
        Some(initialState),
      ),
    )
