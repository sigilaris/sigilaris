package org.sigilaris.conformance

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.util.{Try, Using}

import cats.data.EitherT
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.{
  ExecutionId,
  ExecutionPlan as OldPlan,
}
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.given
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.crypto.{CryptoOps, PublicKey}
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.gossip.StableArtifactId
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.block.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  given ByteDecoder[Proposal],
  given ByteDecoder[QuorumCertificate],
}

/** Neutral historical application with original signed counter commands and
  * complete retained state data. Old blocks 1..5 really change state; F=3 and
  * P=5 have distinct roots. The M1/M2 language is selected by an independently
  * pinned immutable schedule. This fixture supplies no pre-certified future V2
  * block and no invented transition-time lock/watermark.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Throw",
  ),
)
object V2HistoricalFixture:
  import V2RequestConformance.*

  final case class CounterCommand(
      format: Long,
      family: Text,
      inputs: Vector[InputId],
      delta: Long,
      nonce: Long,
  )
  object CounterCommand:
    given ByteEncoder[CounterCommand] = ByteEncoder.derived
    given ByteDecoder[CounterCommand] = ByteDecoder.derived
    val codec = CanonicalCodec.derived[CounterCommand](value =>
      V2Validation.format(value.format, 1L, "neutralCounter.format"),
    )
  final case class SignedCounter(command: CounterCommand, signature: Bytes)
  object SignedCounter:
    given ByteEncoder[SignedCounter] = ByteEncoder.derived
    given ByteDecoder[SignedCounter] = ByteDecoder.derived
    val codec = CanonicalCodec.derived[SignedCounter](value =>
      CounterCommand.codec.encode(value.command).map(_ => ()),
    )
  final case class OldMaterial(
      transaction: SignedCounter,
      priorPayload: Bytes,
      nextPayload: Bytes,
      result: Bytes,
      planBytes: Bytes,
  )
  object OldMaterial:
    given ByteEncoder[OldMaterial] = ByteEncoder.derived
    given ByteDecoder[OldMaterial] = ByteDecoder.derived
    val codec                      =
      CanonicalCodec.derived[OldMaterial](_ => Right[CoreFailure, Unit](()))
  final case class Archive(
      format: Long,
      proposals: Vector[Proposal],
      certificates: Vector[QuorumCertificate],
      materials: Vector[(Hash, OldMaterial)],
  )
  object Archive:
    given ByteEncoder[(Hash, OldMaterial)] = ByteEncoder.derived
    given ByteDecoder[(Hash, OldMaterial)] = ByteDecoder.derived
    given ByteEncoder[Archive]             = ByteEncoder.derived
    given ByteDecoder[Archive]             = ByteDecoder.derived
    val codec = CanonicalCodec.derived[Archive](value =>
      V2Validation.format(value.format, 1L, "historicalFixture.archive"),
    )

  trait Signer:
    def proposal(unsigned: UnsignedProposal): Result[IO, Proposal]
    def vote(proposal: Proposal): Result[IO, Vote]

  def accepted[A](value: Result[IO, A]): IO[A] = value.value.flatMap(
    _.fold(
      error => IO.raiseError(new IllegalStateException(error.message)),
      IO.pure,
    ),
  )
  def check(condition: Boolean, detail: String): Result[IO, Unit] =
    EitherT.fromEither[IO](
      Either.cond(
        condition,
        (),
        V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, detail),
      ),
    )
  def core[A](value: Either[CoreFailure, A]): Result[IO, A] =
    EitherT.fromEither[IO](value.leftMap(V2RuntimeFailure.fromCore))
  def durable(path: Path, bytes: Bytes): IO[Unit] = IO.blocking {
    Using.resource(
      FileChannel.open(
        path,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
      ),
    ) { channel =>
      val buffer = ByteBuffer.wrap(bytes.toArray)
      while buffer.hasRemaining do
        val _ = channel.write(buffer)
      channel.force(true)
    }
    Using.resource(FileChannel.open(path.getParent, StandardOpenOption.READ))(
      _.force(true),
    )
  }
  val CounterDomain: Text = Utf8("neutral.historical-counter.source.v1")
  val ResultDomain: Text  = Utf8("neutral.historical-counter.result.v1")
  val ProofDomain: Text   = Utf8("neutral.historical-counter.replay.v1")

  final class Material private[V2HistoricalFixture] (
      val root: Path,
      val source: Fixture,
      val retained: Ref[IO, Map[ProposalId, Proposal]],
      val certificates: Ref[IO, Map[ProposalId, QuorumCertificate]],
      val original: Ref[IO, Map[Hash, OldMaterial]],
      val v2: V2RuntimeMaterial,
  ):
    private val counterSignatures =
      new java.util.HashSet[(SignedCounter, Bytes)]()

    /** Only the deterministic signature equation is memoized. The key retains
      * the complete signed command and actual public key. Source availability,
      * historical language and full state/result replay stay outside this
      * cache.
      */
    def counterSignature(
        transaction: SignedCounter,
        publicKey: PublicKey,
    ): Either[CoreFailure, Boolean] =
      SignedCounter.codec.encode(transaction).flatMap { canonical =>
        CounterCommand.codec.encode(transaction.command).map { command =>
          val key = transaction -> publicKey.toBytes
          if counterSignatures.synchronized(counterSignatures.contains(key))
          then true
          else
            val valid = signature(transaction.signature)
              .flatMap(value =>
                Try(
                  CryptoOps.recover(
                    value,
                    CryptoOps.keccak256(
                      Commitment.preimage(CounterDomain, command).toArray,
                    ),
                  ),
                ).toOption.flatMap(_.toOption),
              )
              .contains(publicKey)
            if valid && canonical.size <= 65536L then
              counterSignatures.synchronized {
                if counterSignatures.size() < 16 then
                  val _ = counterSignatures.add(key)
                ()
              }
            valid
        }
      }

    val manifest = source.manifest
    val target   = source.context
    val old      =
      DomainContext(1L, target.chainId, uint(1701), 0L, target.validatorSetHash)
    val initialState = source.state.updated(
      source.counterIdentity,
      source.state(source.counterIdentity) - 5L,
    )
    val emptyBody = BlockBody
      .computeBodyRoot(BlockBody[Hash, Hash, Bytes](Set.empty))
      .toOption
      .get
    val originalHeader = V2RuntimeMaterial
      .genesis(source)
      .block
      .copy(stateRoot = StateRoot(stateRoot(initialState)))
    // Actual genesis signatures belong to the independently installed birth
    // inventory. Every subsequent consensus signature uses the supplied Signer.
    val genesis = Proposal
      .sign(
        UnsignedProposal(
          HotStuffWindow.unsafe(source.chain, 0L, 0L, source.validators.hash),
          source.validators.members.head.id,
          BlockHeader.computeId(originalHeader),
          originalHeader,
          ProposalTxSet.empty,
          V2RuntimeMaterial.genesis(source).justify,
        ),
        source.keys.head._2,
      )
      .toOption
      .get
    val initialQc = QuorumCertificate(
      QuorumCertificateSubject(
        genesis.window,
        genesis.proposalId,
        genesis.targetBlockId,
      ),
      source.keys
        .take(3)
        .map((id, key) =>
          Vote
            .sign(
              UnsignedVote(
                genesis.window,
                ValidatorId.unsafe(id.asString),
                genesis.proposalId,
              ),
              key,
            )
            .toOption
            .get,
        ),
    )
    val ranges = HistoricalProfileConfiguration(
      1L,
      Vector(
        HistoricalProfileRange(
          height(0L),
          Some(height(2L)),
          old,
          HistoricalArtifactProfile(
            HistoricalApplicationRelease.LegacyM1,
            1L,
            None,
          ),
          uint(1702),
        ),
        HistoricalProfileRange(
          height(3L),
          Some(height(5L)),
          old,
          HistoricalArtifactProfile(
            HistoricalApplicationRelease.LegacyM2,
            2L,
            None,
          ),
          uint(1703),
        ),
        HistoricalProfileRange(
          height(6L),
          None,
          target,
          HistoricalArtifactProfile(
            HistoricalApplicationRelease.ApplicationV2,
            2L,
            Some(manifest),
          ),
          uint(1704),
        ),
      ),
    )
    val profiles = AuthenticatedHistoricalProfiles
      .pinned(
        value(HistoricalProfileConfiguration.digest(ranges)),
        value(HistoricalProfileConfiguration.codec.encode(ranges)),
      )
      .toOption
      .get
    val validators: ValidatorSetLookup[IO] = ValidatorSetLookup.static(
      BootstrapTrustRoot.staticValidatorSet(source.validators),
    )
    val history: HistoricalConsensusRepository[IO] =
      new HistoricalConsensusRepository[IO]:
        def retained(id: ProposalId): Result[IO, Option[Proposal]] =
          EitherT.liftF(Material.this.retained.get.map(_.get(id)))
        def backfill(id: ProposalId): Result[IO, Option[Proposal]] = retained(
          id,
        )

    def stateBytes(state: Map[InputId, Long]): Bytes =
      state.toVector.sortBy(_._1.toHex).foldLeft(ByteVector.empty) {
        case (bytes, (id, amount)) => bytes ++ id.bytes ++ amount.toBytes
      }
    def stateRoot(state: Map[InputId, Long]): Hash = hash(stateBytes(state))
    def decodeState(payload: Bytes): Result[IO, Map[InputId, Long]] =
      val identities = initialState.keys.toVector.sortBy(_.toHex)
      for
        _ <- check(
          identities.forall(
            _.bytes.size == 1L,
          ) && payload.size == identities.size.toLong * 9L,
          "complete neutral state schema length",
        )
        values <- identities.zipWithIndex.traverse { (identity, index) =>
          val row = payload.slice(index.toLong * 9L, (index.toLong + 1L) * 9L)
          for
            _ <- check(
              row.take(1L) == identity.bytes,
              "neutral state changed original identity inventory",
            )
            value <- EitherT.fromEither[IO](
              ByteDecoder[Long]
                .decode(row.drop(1L))
                .leftMap(error =>
                  V2RuntimeFailure
                    .at(RuntimeFailureCode.ProofInvalid, error.toString),
                )
                .map(_.value),
            )
          yield identity -> value
        }
      yield values.toMap
    def transaction(height: Long): SignedCounter =
      val family = if height <= 2L then Utf8(" neutral.counter ")
      else Utf8("neutral.counter")
      val command =
        CounterCommand(1L, family, Vector(source.counterIdentity), 1L, height)
      SignedCounter(
        command,
        signed(
          source.transactionKey,
          Commitment.preimage(
            CounterDomain,
            value(CounterCommand.codec.encode(command)),
          ),
        ),
      )
    def txId(transaction: SignedCounter): Hash = hash(
      value(SignedCounter.codec.encode(transaction)),
    )
    def execution(transaction: SignedCounter): ExecutionId = ExecutionId(
      txId(transaction),
    )
    def result(transaction: SignedCounter, next: Long): Bytes =
      value(CounterCommand.codec.encode(transaction.command)) ++ next.toBytes
    def resultDigest(bytes: Bytes): Hash = Commitment.hash(ResultDomain, bytes)
    def body(transaction: SignedCounter, result: Bytes): BodyRoot = BlockBody
      .computeBodyRoot(
        BlockBody[Hash, Hash, Bytes](
          Set(
            BlockRecord(
              txId(transaction),
              Some(resultDigest(result)),
              Vector.empty[Bytes],
            ),
          ),
        ),
      )
      .toOption
      .get
    def unsignedOld(
        parent: Proposal,
        qc: QuorumCertificate,
        h: Long,
    ): Result[IO, UnsignedProposal] = for
      rows <- EitherT.liftF(original.get)
      priorPayload =
        if h == 1L then stateBytes(initialState)
        else rows(parent.targetBlockId.toUInt256).nextPayload
      prior <- decodeState(priorPayload)
      tx   = transaction(h)
      next = prior.updated(
        source.counterIdentity,
        prior(source.counterIdentity) + tx.command.delta,
      )
      normalized = result(tx, next(source.counterIdentity))
      oldPlan    = OldPlan.compatibilitySingleton(execution(tx))
      header     = BlockHeader(
        Some(parent.targetBlockId),
        BlockHeight.unsafeFromLong(h),
        StateRoot(stateRoot(next)),
        body(tx, normalized),
        BlockTimestamp.unsafeFromEpochMillis(1000L + h),
        if h <= 2L then BlockHeaderVersion.V1 else BlockHeaderVersion.V2,
        if h <= 2L then None else Some(OldPlan.computeRoot(oldPlan)),
      )
      material = OldMaterial(
        tx,
        priorPayload,
        stateBytes(next),
        normalized,
        oldPlan.toBytes,
      )
      _ <- EitherT.liftF(
        original.update(
          _.updated(BlockHeader.computeId(header).toUInt256, material),
        ),
      )
      _ <- save
    yield UnsignedProposal(
      HotStuffWindow.unsafe(source.chain, h, 0L, source.validators.hash),
      source.validators.members.head.id,
      BlockHeader.computeId(header),
      header,
      ProposalTxSet(Vector(StableArtifactId.unsafeFromBytes(txId(tx).bytes))),
      qc,
    )

    val legacy: LegacyHistoricalApplicationReplay[IO] =
      new LegacyHistoricalApplicationReplay[IO]:
        def legacyM1(
            range: HistoricalProfileRange,
            proposal: Proposal,
            parent: Proposal,
        ): Result[IO, HistoricalReplayResult] =
          run(range, proposal, parent, true)
        def legacyM2(
            range: HistoricalProfileRange,
            proposal: Proposal,
            parent: Proposal,
        ): Result[IO, HistoricalReplayResult] =
          run(range, proposal, parent, false)
        private def run(
            range: HistoricalProfileRange,
            proposal: Proposal,
            parent: Proposal,
            m1: Boolean,
        ): Result[IO, HistoricalReplayResult] = for
          material <- EitherT(
            original.get.map(
              _.get(proposal.targetBlockId.toUInt256).toRight(
                V2RuntimeFailure.at(
                  RuntimeFailureCode.ProofUnavailable,
                  "original signed counter source/state missing",
                ),
              ),
            ),
          )
          canonical      <- core(OldMaterial.codec.encode(material))
          signatureValid <- core(
            counterSignature(
              material.transaction,
              source.transactionKey.publicKey,
            ),
          )
          _ <- check(signatureValid, "original counter transaction signature")
          command        = material.transaction.command
          expectedFamily = if m1 then " neutral.counter " else "neutral.counter"
          parsedFamily   =
            if m1 then command.family.asString else command.family.asString.trim
          _ <- check(
            parsedFamily == expectedFamily && command.inputs.nonEmpty && command.inputs
              .forall(
                _ == source.counterIdentity,
              ) && (m1 || command.inputs.distinct
              .sizeCompare(command.inputs.size) == 0),
            "selected original M1/M2 family and duplicate-input language",
          )
          prior <- decodeState(material.priorPayload)
          _     <- check(
            stateRoot(prior) == parent.block.stateRoot.toUInt256,
            "original full prior state does not match actual parent",
          )
          next = command.inputs.foldLeft(prior)((state, id) =>
            state.updated(id, Math.addExact(state(id), command.delta)),
          )
          normalized = result(
            material.transaction,
            next(source.counterIdentity),
          )
          plan = OldPlan.compatibilitySingleton(execution(material.transaction))
          _ <- check(
            material.nextPayload == stateBytes(
              next,
            ) && material.result == normalized && material.planBytes == plan.toBytes && proposal.txSet == ProposalTxSet(
              Vector(
                StableArtifactId.unsafeFromBytes(
                  txId(material.transaction).bytes,
                ),
              ),
            ),
            "original result/state/plan/source membership differs from execution",
          )
          _ <- check(
            range.context == old && (range.profile.release == HistoricalApplicationRelease.LegacyM1) == m1,
            "original interpreter release provenance",
          )
        yield HistoricalReplayResult(
          stateRoot(prior),
          stateRoot(next),
          body(material.transaction, normalized).toUInt256,
          if m1 then None else Some(OldPlan.computeRoot(plan)),
          HistoricalApplicationReplay.resultsDigest(Vector(normalized)),
          Commitment.hash(ProofDomain, canonical),
          HistoricalExecutionMaterial(
            material.planBytes,
            canonical,
            material.nextPayload,
            Vector(normalized),
          ),
        )

    val replay: HistoricalApplicationReplay[IO] =
      HistoricalApplicationReplay.authenticated(
        new HistoricalV2ExecutionRepository[IO]:
          def material(
              proposal: Proposal,
          ): Result[IO, HistoricalV2ExecutionMaterial] =
            val plan = if proposal.block.height.toBigNat.toBigInt == 6 then
              source.consensusPlan
            else ExecutionPlan(2L, Vector.empty, Vector.empty)
            v2.payload(proposal.block.stateRoot.toUInt256)
              .map(payload =>
                HistoricalV2ExecutionMaterial(
                  plan,
                  payload,
                  HistoricalConsensusAuthentication
                    .proposalDigest(proposal)
                    .bytes,
                ),
              )
          def verifyOriginalProof(
              request: VerifiedConsensusProposal,
              proof: Bytes,
          ): Result[IO, Unit] = check(
            proof == HistoricalConsensusAuthentication
              .proposalDigest(request.proposal)
              .bytes,
            "original V2 proof is bound to the complete retained signed proposal",
          )
        ,
        new HistoricalV2VerifierRegistry[IO]:
          def resolve(
              selected: ProtocolManifest,
          ): Result[IO, HistoricalV2Verifiers[IO]] =
            check(selected == manifest, "original immutable V2 manifest")
              .as(HistoricalV2Verifiers(manifest, v2.requests, v2.states))
        ,
        legacy,
      )
    val consensus = HistoricalConsensusAuthentication.authenticated(
      profiles,
      validators,
      history,
      replay,
    )
    def retain(proposal: Proposal): Result[IO, Unit] = check(
      HotStuffValidator.validateProposal(proposal, source.validators).isRight,
      "actual historical proposal signature/QC",
    ).flatMap(_ =>
      EitherT.liftF(retained.update(_.updated(proposal.proposalId, proposal))),
    ).flatMap(_ => save)
    def retain(certificate: QuorumCertificate): Result[IO, Unit] = check(
      HotStuffValidator
        .validateQuorumCertificate(certificate, source.validators)
        .isRight,
      "actual historical QC quorum",
    ).flatMap(_ =>
      EitherT.liftF(
        certificates.update(
          _.updated(certificate.subject.proposalId, certificate),
        ),
      ),
    ).flatMap(_ => save)
    def save: Result[IO, Unit] = for
      proposals <- EitherT.liftF(retained.get)
      certs     <- EitherT.liftF(certificates.get)
      materials <- EitherT.liftF(original.get)
      bytes     <- core(
        Archive.codec.encode(
          Archive(
            1L,
            proposals.values.toVector.sortBy(_.proposalId.toHexLower),
            certs.values.toVector.sortBy(_.subject.proposalId.toHexLower),
            materials.toVector.sortBy(_._1.bytes.toHex),
          ),
        ),
      )
      _ <- EitherT.liftF(durable(root.resolve("original-history"), bytes))
    yield ()
    def buildOld(signers: Vector[Signer]): Result[IO, Vector[Proposal]] =
      check(
        signers.sizeCompare(4) == 0,
        "four independent historical signer/store owners required",
      ).flatMap { _ =>
        (1L to 5L).toVector.foldLeftM(Vector(genesis)) { (previous, h) =>
          for
            certs    <- EitherT.liftF(certificates.get)
            unsigned <- unsignedOld(
              previous.last,
              certs(previous.last.proposalId),
              h,
            )
            proposal <- signers.head.proposal(unsigned)
            _        <- retain(proposal)
            votes    <- signers.traverse(_.vote(proposal))
            qc = QuorumCertificate(
              QuorumCertificateSubject(
                proposal.window,
                proposal.proposalId,
                proposal.targetBlockId,
              ),
              votes.take(3),
            )
            _ <- retain(qc)
          yield previous :+ proposal
        }
      }
    def finalized(h: Long): Result[IO, FinalizedAnchorSuggestion] = for
      rows <- EitherT.liftF(retained.get)
      sorted = rows.values.toVector.sortBy(_.block.height.toBigNat.toBigInt)
      first <- EitherT.fromOption[IO](
        sorted.find(_.block.height.toBigNat.toBigInt == h),
        V2RuntimeFailure.at(
          RuntimeFailureCode.ProofUnavailable,
          "finality source missing",
        ),
      )
      child <- EitherT.fromOption[IO](
        sorted.find(p =>
          p.block.parent.contains(
            first.targetBlockId,
          ) && p.block.height.toBigNat.toBigInt == h + 1,
        ),
        V2RuntimeFailure.at(
          RuntimeFailureCode.ProofUnavailable,
          "finality child missing",
        ),
      )
      grandchild <- EitherT.fromOption[IO](
        sorted.find(p =>
          p.block.parent.contains(
            child.targetBlockId,
          ) && p.block.height.toBigNat.toBigInt == h + 2,
        ),
        V2RuntimeFailure.at(
          RuntimeFailureCode.ProofUnavailable,
          "finality grandchild missing",
        ),
      )
    yield FinalizedAnchorSuggestion(first, FinalizedProof(child, grandchild))

  def material(path: Path): Resource[IO, Material] = Resource.eval(for
    _ <- IO.blocking(Files.createDirectories(path)).void
    source = new Fixture(1700L, Authority.ConsensusOnly)
    retained  <- Ref.of[IO, Map[ProposalId, Proposal]](Map.empty)
    certs     <- Ref.of[IO, Map[ProposalId, QuorumCertificate]](Map.empty)
    original  <- Ref.of[IO, Map[Hash, OldMaterial]](Map.empty)
    finalized <- Ref.of[IO, FinalizationTrackerSnapshot](
      FinalizationTrackerSnapshot.empty,
    )
    live <- Ref.of[IO, Option[HotStuffNodeRuntime[IO]]](None)
    v2 = new V2RuntimeMaterial(
      source,
      source.validators.members.head.id,
      retained,
      finalized,
      live,
    )
    value = new Material(path, source, retained, certs, original, v2)
    _ <- IO.blocking(Files.exists(path.resolve("original-history"))).flatMap {
      exists =>
        if exists then
          for
            raw <- IO.blocking(
              ByteVector
                .view(Files.readAllBytes(path.resolve("original-history"))),
            )
            archive <- accepted(core(Archive.codec.decode(raw)))
            _       <- retained.set(
              archive.proposals.map(value => value.proposalId -> value).toMap,
            )
            _ <- certs.set(
              archive.certificates
                .map(value => value.subject.proposalId -> value)
                .toMap,
            )
            _ <- original.set(archive.materials.toMap)
          yield ()
        else
          accepted(
            value.retain(value.genesis) *> value.retain(value.initialQc),
          ) *> V2HistoricalTransitionFixture.initialize(value)
    }
  yield value)
