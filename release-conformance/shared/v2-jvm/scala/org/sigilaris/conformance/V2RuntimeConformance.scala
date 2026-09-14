package org.sigilaris.conformance

import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.{Duration, Instant}
import scala.jdk.CollectionConverters.*

import cats.data.EitherT
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.{
  ApplicationValidatorId,
  ExecutionPlanRoot,
}
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.gossip.*
import org.sigilaris.node.gossip.tx.{TxGossipRuntime, TxGossipStateStore}
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.block.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.storage.file.FileApplicationJournal

/** Four independently assembled ordinary runtimes. Consensus artifacts travel
  * through authenticated Gossip sessions; every node owns exactly one local
  * signer and a different file journal. Only the historical starting checkpoint
  * is fixture trust input. Future QCs and three-chain finality are formed by
  * the actual ordinary runtimes and their transported votes.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Throw",
  ),
)
object V2RuntimeConformance:
  import V2RequestConformance.*
  import V2VotingConformance.accepted

  val start: Instant = Instant.parse("2026-09-11T00:00:00Z")

  private def check(condition: Boolean, detail: String): IO[Unit] =
    IO.raiseUnless(condition)(new IllegalStateException(detail))
  private def policy[A](value: Either[HotStuffPolicyViolation, A]): IO[A] =
    IO.fromEither(
      value.leftMap(error =>
        new IllegalStateException(
          error.reason + ":" + error.detail.getOrElse(""),
        ),
      ),
    )
  private def transport[A](value: Either[CanonicalRejection, A]): IO[A] =
    IO.fromEither(
      value.leftMap(error => new IllegalStateException(error.reason)),
    )

  private object TargetArchive:
    import V2Codecs.given
    given ByteEncoder[HotStuffMaintenanceTarget]         = ByteEncoder.derived
    given ByteDecoder[HotStuffMaintenanceTarget]         = ByteDecoder.derived
    val codec: CanonicalCodec[HotStuffMaintenanceTarget] =
      CanonicalCodec.derived(target =>
        DomainContext.validateActive(target.context),
      )

  private def force(path: Path, bytes: Bytes): IO[Unit] = IO.blocking {
    val channel = FileChannel.open(
      path,
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
      StandardOpenOption.TRUNCATE_EXISTING,
    )
    try
      val buffer = java.nio.ByteBuffer.wrap(bytes.toArray)
      while buffer.hasRemaining do
        val _ = channel.write(buffer)
      channel.force(true)
    finally channel.close()
  }

  private def loadTarget(
      path: Path,
  ): IO[Option[Option[HotStuffMaintenanceTarget]]] = IO.blocking {
    if !Files.exists(path) then None
    else
      val raw = ByteVector.view(Files.readAllBytes(path))
      if raw == ByteVector(0.toByte) then Some(None)
      else
        assert(raw.headOption.contains(1.toByte))
        Some(Some(value(TargetArchive.codec.decode(raw.drop(1L)))))
  }

  enum PreimageFault:
    case Healthy, Missing, Malformed

  final class Clock(val value: Ref[IO, Instant]) extends GossipClock[IO]:
    def now: IO[Instant]                      = value.get
    def advance(duration: Duration): IO[Unit] = value.update(_.plus(duration))

  final class Network(val nodes: Ref[IO, Vector[Node]]):
    def fetch(
        requester: String,
        root: ExecutionPlanRoot,
    ): Result[IO, Option[Bytes]] = EitherT.liftF(
      nodes.get
        .flatMap(_.filter(_.id != requester).traverse(_.exports.get))
        .map(_.iterator.flatMap(_.get(root)).nextOption),
    )

  final case class Node(
      id: String,
      index: Int,
      material: V2RuntimeMaterial,
      journal: DurableJournal[IO],
      safety: JournalSafetyStore[IO],
      voting: DurableApplicationVoting[IO],
      application: FinalizedApplicationRuntime[IO],
      plans: HotStuffExecutionPlanPreimages[IO],
      exports: Ref[IO, Map[ExecutionPlanRoot, Bytes]],
      fault: Ref[IO, PreimageFault],
      target: Ref[IO, Option[HotStuffMaintenanceTarget]],
      consensus: HotStuffNodeRuntime[IO],
      gossip: TxGossipRuntime[IO, HotStuffGossipArtifact],
      clock: Clock,
      path: Path,
  ):
    def snapshot: IO[InMemoryHotStuffSinkSnapshot] =
      consensus.inMemorySink.get.snapshot
    def persistTarget(command: Option[HotStuffMaintenanceTarget]): IO[Unit] =
      force(
        path.resolve("maintenance-target"),
        command.fold(ByteVector(0.toByte))(target =>
          ByteVector(1.toByte) ++ value(TargetArchive.codec.encode(target)),
        ),
      )
    def save: IO[Unit] = for
      snapshot <- this.snapshot
      command  <- target.get
      _        <- persistTarget(command)
      _        <- IO.blocking {
        val archive = path.resolve("artifacts")
        Files.createDirectories(archive)
        val artifacts = snapshot.proposals.valuesIterator
          .map(HotStuffGossipArtifact.ProposalArtifact.apply)
          .toVector ++
          snapshot.votes.valuesIterator
            .map(HotStuffGossipArtifact.VoteArtifact.apply)
            .toVector
        artifacts.foreach { artifact =>
          val bytes = ByteEncoder[HotStuffGossipArtifact].encode(artifact)
          val file  = archive.resolve(
            HotStuffGossipArtifact.stableIdOf(artifact).bytes.toHex,
          )
          val channel = FileChannel.open(
            file,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
          )
          try
            val buffer = java.nio.ByteBuffer.wrap(bytes.toArray)
            while buffer.hasRemaining do
              val _ = channel.write(buffer)
            channel.force(true)
          finally channel.close()
        }
      }
    yield ()

  final case class Link(from: Node, to: Node, session: DirectionalSessionId)
  final case class Cluster(
      nodes: Vector[Node],
      links: Vector[Link],
      network: Network,
  ):
    def pump(rounds: Int): IO[Unit] =
      (0 until rounds).toVector.traverse_(_ => links.traverse_(relay))
    def seed: IO[Unit] =
      val f        = nodes.head.material.source
      val window   = f.baseWindow
      val leader   = HotStuffPacemaker.deterministicLeader(window, f.validators)
      val selected = nodes.find(_.consensus.localKeys.contains(leader)).get
      val historicalTip = selected.material.initialHistory.last
      val trusted       = QuorumCertificate(
        QuorumCertificateSubject(
          historicalTip.window,
          historicalTip.proposalId,
          historicalTip.targetBlockId,
        ),
        f.keys
          .take(3)
          .map((id, key) =>
            Vote
              .sign(
                UnsignedVote(
                  historicalTip.window,
                  ValidatorId.unsafe(id.asString),
                  historicalTip.proposalId,
                ),
                key,
              )
              .toOption
              .get,
          ),
      )
      for
        _ <- selected.material.initialHistory.traverse_(proposal =>
          selected.consensus.services.publisher
            .append(HotStuffGossipArtifact.ProposalArtifact(proposal), start)
            .flatMap(event =>
              selected.consensus.sink
                .applyEvent(event)
                .flatMap(value => transport(value).void),
            ),
        )
        event <- selected.consensus
          .emitProposal(
            leader,
            selected.material.checkpointHeader,
            ProposalTxSet.empty,
            window,
            trusted,
            start,
          )
          .flatMap(policy)
        _ <- selected.consensus.sink
          .applyEvent(event)
          .flatMap(value => transport(value).void)
      yield ()

    def maintenance(value: Long): IO[Unit] = nodes.traverse_(node =>
      val command = Some(
        HotStuffMaintenanceTarget(
          uint(10000L + value),
          node.material.context,
          height(value),
        ),
      )
      node.persistTarget(command) *> node.target.set(command) *>
        node.consensus.notifyApplicationWorkAvailable,
    )

  private def finality(
      material: V2RuntimeMaterial,
      block: Hash,
  ): Result[IO, FinalizedAnchorSuggestion] = for
    history <- EitherT.liftF(material.allProposals)
    anchors = history.values.toVector.filter(_.targetBlockId == BlockId(block))
    descendants = history.values.toVector.sortBy(
      _.block.height.toBigNat.toBigInt,
    )
    proof = anchors.flatMap(anchor =>
      descendants
        .filter(_.justify.subject.proposalId == anchor.proposalId)
        .flatMap(child =>
          descendants
            .filter(_.justify.subject.proposalId == child.proposalId)
            .map(grandchild =>
              FinalizedAnchorSuggestion(
                anchor,
                FinalizedProof(child, grandchild),
              ),
            ),
        ),
    )
    validated <- EitherT.liftF(
      proof.traverse(value =>
        HotStuffFinalizedAnchorVerifier.verify(value, material.validators),
      ),
    )
    verified <- EitherT.fromOption[IO](
      validated.collectFirst { case Right(value) => value },
      V2RuntimeFailure.at(
        RuntimeFailureCode.ProofUnavailable,
        "actual three-chain descendants missing",
      ),
    )
  yield verified

  private def history(
      material: V2RuntimeMaterial,
      plans: HotStuffExecutionPlanPreimages[IO],
  ): FinalizedApplicationHistory[IO] = new FinalizedApplicationHistory[IO]:
    def anchorAncestor(
        anchor: ApplicationAnchor,
        finalized: FinalizedAnchorSuggestion,
    ): Result[IO, VerifiedInitialAnchorAncestor] =
      val archive = new InitialAnchorHistory[IO]:
        def retained(
            context: DomainContext,
            id: Hash,
        ): Result[IO, Option[Proposal]] =
          if context == material.context then
            EitherT.liftF(material.history.map(_.get(BlockId(id))))
          else unavailable("initial ancestry context")
        def backfill(
            context: DomainContext,
            id: Hash,
        ): Result[IO, Option[Proposal]] = retained(context, id)
      InitialAnchorAncestry.legacyEmpty(
        anchor,
        finalized,
        material.validators,
        archive,
        100L,
      )
    def retained(
        context: DomainContext,
        blockId: Hash,
    ): Result[IO, Option[FinalizedApplicationMaterial]] =
      if context != material.context then unavailable("history context")
      else
        for
          finalized <- finality(material, blockId)
          root      <- EitherT.fromOption[IO](
            finalized.proposal.block.executionPlanRoot,
            V2RuntimeFailure.at(
              RuntimeFailureCode.ProofUnavailable,
              "finalized V2 plan root",
            ),
          )
          plan    <- plans.fetch(root)
          payload <- material.payload(
            finalized.proposal.block.stateRoot.toUInt256,
          )
        yield Some(FinalizedApplicationMaterial(finalized, plan, payload))
    def backfill(
        context: DomainContext,
        blockId: Hash,
    ): Result[IO, Option[FinalizedApplicationMaterial]] =
      retained(context, blockId)
    def latestFinalized(
        context: DomainContext,
    ): Result[IO, Option[FinalizedAnchorSuggestion]] =
      if context == material.context then
        EitherT(
          material.finalized.map(snapshot =>
            Either.cond(
              snapshot.safetyFaults.isEmpty,
              snapshot.bestFinalized,
              V2RuntimeFailure.at(
                RuntimeFailureCode.ProofInvalid,
                "actual archived or current conflicting finality",
              ),
            ),
          ),
        )
      else unavailable("latest finality context")

  private def archived(path: Path): IO[Vector[HotStuffGossipArtifact]] =
    IO.blocking {
      val directory = path.resolve("artifacts")
      if !Files.exists(directory) then Vector.empty
      else
        val files = Files.list(directory)
        try
          files.iterator.asScala.toVector.map { file =>
            val bytes   = ByteVector.view(Files.readAllBytes(file))
            val decoded =
              ByteDecoder[HotStuffGossipArtifact].decode(bytes).toOption.get
            assert(
              decoded.remainder.isEmpty && ByteEncoder[HotStuffGossipArtifact]
                .encode(decoded.value) == bytes,
            )
            decoded.value
          }
        finally files.close()
    }

  private def nodeResource(
      source: V2RequestConformance.Fixture,
      index: Int,
      path: Path,
      network: Network,
  ): Resource[IO, Node] =
    FileApplicationJournal.resource(path.resolve("safety")).evalMap { journal =>
      val id    = "ordinary-v2-" + index.toString
      val voter = source.validators.members(index).id
      for
        clockRef <- Ref.of[IO, Instant](start)
        clock                 = new Clock(clockRef)
        given GossipClock[IO] = clock
        material <- V2RuntimeMaterial.create(source, voter)
        past     <- archived(path)
        proposals = past
          .collect { case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
            proposal
          }
          .sortBy(_.block.height.toBigNat.toBigInt)
        _ <- proposals
          .traverse_(proposal => accepted(material.retain(proposal)))
        // Reconstruct proof state from the actual archived three-chain, never a height assertion.
        _ <- material.finalization.set(
          HotStuffFinalizationTracker.track(proposals),
        )
        safety <- accepted(
          JournalSafetyStore.open(
            material.anchor,
            journal,
            material.publication,
            SafetyProfile(source.manifest, material.artifacts),
            SafetyRecoveryAuthentication.combine(
              VotingRecoveryAuthentication.authenticated(material.requests),
              material.applications,
            ),
            ReservationOrdering.isolated[IO],
            SafetyCapacity.unbounded,
          ),
        )
        exports <- Ref.of[IO, Map[ExecutionPlanRoot, Bytes]](Map.empty)
        fault   <- Ref.of[IO, PreimageFault](PreimageFault.Healthy)
        storage = HotStuffExecutionPlanPreimages.journaled[IO](
          safety,
          root =>
            EitherT.liftF(fault.get).flatMap {
              case PreimageFault.Missing   => EitherT.pure(None)
              case PreimageFault.Malformed =>
                network.fetch(id, root).map(_.map(_ ++ ByteVector(0.toByte)))
              case PreimageFault.Healthy => network.fetch(id, root)
            },
        )
        plans = new HotStuffExecutionPlanPreimages[IO]:
          def retain(plan: ExecutionPlan): Result[IO, ExecutionPlanRoot] =
            storage
              .retain(plan)
              .flatTap(root =>
                EitherT.liftF(
                  exports.update(
                    _.updated(root, value(ExecutionPlan.codec.encode(plan))),
                  ),
                ),
              )
          def fetch(root: ExecutionPlanRoot): Result[IO, ExecutionPlan] =
            storage.fetch(root)
        application <- FinalizedApplicationRuntime.authenticated(
          safety,
          material.requests,
          material.applications,
          history(material, plans),
          FinalizedApplicationCapacity(100L),
        )
        contexts = new HotStuffApplicationVotingContexts[IO]:
          def resolve(
              manifest: ProtocolManifest,
              localVoter: ValidatorId,
          ): Result[IO, HotStuffApplicationVotingContext[IO]] =
            if manifest == source.manifest && localVoter == voter then
              EitherT.pure(
                HotStuffApplicationVotingContext(
                  manifest,
                  safety,
                  material.requests,
                  material.artifacts,
                  None,
                ),
              )
            else unavailable("local voting identity or manifest")
        legacy = new HotStuffLegacyApplicationAuthentication[IO]:
          def verify(
              profile: HotStuffHistoricalApplicationProfile,
              proposal: Proposal,
          ): Result[IO, Unit] =
            if profile == HotStuffHistoricalApplicationProfile.LegacyM2(
                BlockHeaderVersion.V1,
              ) && (proposal.block == material.checkpointHeader || material.initialHistory
                .contains(
                  proposal,
                )) && proposal.txSet == ProposalTxSet.empty && HotStuffValidator
                .validateProposal(proposal, source.validators)
                .isRight
            then EitherT.pure(())
            else unavailable("authenticated historical fixture checkpoint")
        dispatcher = HotStuffApplicationVoting.journaled(
          material.schedule,
          plans,
          contexts,
          legacy,
        )
        savedTarget <- loadTarget(path.resolve("maintenance-target"))
        target      <- Ref.of[IO, Option[HotStuffMaintenanceTarget]](
          savedTarget.getOrElse(
            Some(
              HotStuffMaintenanceTarget(uint(10006), source.context, height(6)),
            ),
          ),
        )
        maintenanceSource = new HotStuffMaintenanceSource[IO]:
          def current(
              request: HotStuffProposalInputRequest,
          ): Result[IO, HotStuffMaintenanceObservation] = EitherT.liftF(
            (target.get, material.finalized).mapN((target, finalized) =>
              HotStuffMaintenanceObservation(target, Some(finalized)),
            ),
          )
        maintenance <- HotStuffMaintenanceProgress
          .bounded[IO](maintenanceSource, 20, Duration.ofMinutes(5))
          .flatMap(value =>
            IO.fromEither(value.leftMap(new IllegalStateException(_))),
          )
        assembly = HotStuffProposalApplicationAssembly.default(
          material.schedule,
          material.parents,
          material.validators,
          material.execution,
          plans,
          maintenance,
        )
        holders = source.validators.members.zipWithIndex.map((member, i) =>
          ValidatorKeyHolder(
            member.id,
            PeerIdentity.unsafe("ordinary-v2-" + i.toString),
            ValidatorKeyHolderStatus.Active,
          ),
        )
        runtime <- HotStuffNodeRuntime
          .create[IO](
            PeerIdentity.unsafe(id),
            LocalNodeRole.Validator,
            holders,
            source.validators,
            Map(voter -> source.keys(index)._2),
            automaticConsensus = true,
            proposalInputConfig =
              HotStuffProposalInputRuntimeConfig.application(assembly, None),
            proposalValidationConfig = HotStuffProposalValidationRuntimeConfig
              .application(dispatcher, application, None),
          )
          .flatMap(policy)
        _        <- material.live.set(Some(runtime))
        topology <- IO.fromEither(
          StaticPeerTopology
            .parse(
              id,
              (0 until 4)
                .filter(_ != index)
                .map(i => "ordinary-v2-" + i.toString)
                .toList,
              (0 until 4)
                .filter(_ != index)
                .map(i => "ordinary-v2-" + i.toString)
                .toList,
            )
            .leftMap(new IllegalStateException(_)),
        )
        registry = StaticPeerRegistry(topology)
        gossipState <- TxGossipStateStore.inMemory[IO](
          GossipSessionEngine(registry.localPeer, topology),
        )
        gossip = TxGossipRuntime.withPolicy[IO, HotStuffGossipArtifact](
          StaticPeerAuthenticator[IO](registry),
          clock,
          runtime.source,
          runtime.sink,
          runtime.topicContracts,
          gossipState,
          HotStuffRuntimeBootstrap.DefaultRuntimePolicy,
        )
        voting = DurableApplicationVoting.fromStore(
          safety,
          ApplicationVoteSigner.secp256k1[IO](
            ApplicationValidatorId(Utf8(voter.value)),
            source.keys(index)._2,
          ),
          material.artifacts,
        )
        node = Node(
          id,
          index,
          material,
          journal,
          safety,
          voting,
          application,
          plans,
          exports,
          fault,
          target,
          runtime,
          gossip,
          clock,
          path,
        )
        _ <- past
          .sortBy {
            case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
              (proposal.window.height.toBigNat.toBigInt, 0)
            case HotStuffGossipArtifact.VoteArtifact(vote) =>
              (vote.window.height.toBigNat.toBigInt, 1)
            case _ => (BigInt(0), 2)
          }
          .traverse_(artifact =>
            runtime.services.publisher
              .append(artifact, start)
              .flatMap(event =>
                runtime.sink
                  .applyEvent(event)
                  .flatMap(value => transport(value).void),
              ),
          )
      yield node
    }

  private def connect(from: Node, to: Node): IO[Link] =
    val chain         = from.material.source.chain
    val subscriptions = SessionSubscription.unsafe(
      ChainTopic(chain, GossipTopic.consensusProposal),
      ChainTopic(chain, GossipTopic.consensusVote),
      ChainTopic(chain, GossipTopic.consensusTimeoutVote),
      ChainTopic(chain, GossipTopic.consensusNewView),
    )
    for
      proposed <- from.gossip
        .startOutbound(PeerIdentity.unsafe(to.id), subscriptions)
        .flatMap(transport)
      inbound <- to.gossip.handleInboundProposal(proposed)
      ack     <- inbound match
        case InboundHandshakeResult.Accepted(ack, _)   => IO.pure(ack)
        case rejected: InboundHandshakeResult.Rejected =>
          IO.raiseError(new IllegalStateException(rejected.rejection.reason))
      _ <- from.gossip.applyHandshakeAck(ack).flatMap(transport)
    yield Link(from, to, proposed.sessionId)

  private def relay(link: Link): IO[Unit] = for
    messages <- link.from.gossip.pollEvents(link.session).flatMap(transport)
    // Serialize and strictly decode each payload at the transport boundary.
    decoded = messages.map {
      case EventStreamMessage.Event(event) =>
        val encoded = ByteEncoder[HotStuffGossipArtifact].encode(event.payload)
        val result  =
          ByteDecoder[HotStuffGossipArtifact].decode(encoded).toOption.get
        assert(result.remainder.isEmpty)
        EventStreamMessage.Event(event.copy(payload = result.value))
      case other => other
    }
    _ <- link.to.gossip.receiveEvents(link.session, decoded).flatMap(transport)
  yield ()

  def cluster(
      root: Path,
      source: V2RequestConformance.Fixture,
  ): Resource[IO, Cluster] = for
    refs <- Resource.eval(Ref.of[IO, Vector[Node]](Vector.empty))
    network = new Network(refs)
    nodes <- (0 until 4).toVector.traverse(index =>
      nodeResource(source, index, root.resolve(index.toString), network),
    )
    _     <- Resource.eval(refs.set(nodes))
    links <- Resource.eval(
      nodes
        .traverse(from =>
          nodes.filter(_.id != from.id).traverse(to => connect(from, to)),
        )
        .map(_.flatten),
    )
  yield Cluster(nodes, links, network)

  def directory: Resource[IO, Path] = Resource.make(
    IO.blocking(Files.createTempDirectory("sigilaris-v2-four-runtime-")),
  )(path =>
    IO.blocking {
      val files = Files.walk(path)
      try
        files.iterator.asScala.toVector
          .sortBy(_.getNameCount)
          .reverse
          .foreach(Files.delete)
      finally files.close()
    },
  )

  def automaticAndDrain: IO[Unit] = directory.use { root =>
    val source = new Fixture(33001L, Authority.ConsensusOnly)
    cluster(root, source).use { cluster =>
      for
        _      <- cluster.seed
        _      <- cluster.pump(18)
        before <- cluster.nodes.traverse(_.snapshot)
        states <- cluster.nodes.traverse(node => accepted(node.safety.snapshot))
        _      <- check(
          before.forall(
            _.finalization
              .get(source.chain)
              .flatMap(_.bestFinalized)
              .exists(_.anchorHeight.toBigNat.toBigInt >= 6),
          ),
          "four independent runtimes did not finalize V2 execution",
        )
        _ <- check(
          states.forall(_.appliedEntries.contains(source.executionId)),
          "finality did not materialize every local application",
        )
        _ <- check(
          states.flatMap(_.canonical.map(_.blockId)).distinct.size == 1,
          "application canonical roots diverged",
        )
        roots = states.flatMap(state =>
          state.canonical.flatMap(decision =>
            state.preparations
              .get(decision.batchDigest)
              .map(_.batch.nextStateRoot),
          ),
        )
        _ <- check(
          roots == Vector.fill(4)(source.nextStateRoot),
          "independent durable application roots differ from re-executed state",
        )
        _ <- check(
          before.forall(snapshot =>
            snapshot.qcs.valuesIterator
              .filter(_.subject.window.height.toBigNat.toBigInt >= 6)
              .forall(_.votes.map(_.voter).distinct.size >= 3),
          ),
          "ordinary runtime QC lacks three independent votes",
        )
        _ <- check(
          before.forall(
            _.proposals.valuesIterator
              .filter(_.block.height.toBigNat.toBigInt > 5)
              .forall(_.block.version == BlockHeaderVersion.V2),
          ),
          "ordinary assembly emitted a legacy header in V2",
        )
        beforeMax = before
          .flatMap(
            _.proposals.valuesIterator.map(_.block.height.toBigNat.toBigInt),
          )
          .max
        _     <- cluster.maintenance(11L)
        _     <- cluster.pump(24)
        after <- cluster.nodes.traverse(_.snapshot)
        _     <- check(
          after.forall(
            _.finalization
              .get(source.chain)
              .flatMap(_.bestFinalized)
              .exists(_.anchorHeight.toBigNat.toBigInt >= 11),
          ),
          "transaction-free drain did not reach its finalized target",
        )
        _ <- check(
          after.forall(
            _.proposals.valuesIterator
              .filter(_.block.height.toBigNat.toBigInt > beforeMax)
              .forall(_.txSet == ProposalTxSet.empty),
          ),
          "idle drain unexpectedly required new transactions",
        )
        _ <- cluster.nodes.traverse_(_.save)
      yield ()
    } *> cluster(root, source).use { restarted =>
      for
        states <- restarted.nodes.traverse(node =>
          accepted(node.safety.snapshot),
        )
        _ <- check(
          states.forall(_.appliedEntries.contains(source.executionId)),
          "restart lost durable application execution",
        )
        _ <- check(
          states.flatMap(_.canonical.map(_.blockId)).distinct.size == 1,
          "restart selected different canonical applications",
        )
        commands <- restarted.nodes.traverse(_.target.get)
        _        <- check(
          commands.forall(_.exists(_.finalizedHeight == height(11L))),
          "restart lost the retained maintenance command",
        )
        _ <- restarted.pump(4)
      yield ()
    }
  }

  def unavailablePreimage(mode: PreimageFault): IO[Unit] = directory.use {
    root =>
      val source =
        new Fixture(33100L + mode.ordinal.toLong, Authority.ConsensusOnly)
      cluster(root, source).use { cluster =>
        for
          _         <- cluster.nodes.traverse_(_.fault.set(mode))
          _         <- cluster.seed
          _         <- cluster.pump(14)
          snapshots <- cluster.nodes.traverse(_.snapshot)
          candidate <- IO.fromOption(
            snapshots.head.proposals.valuesIterator
              .find(_.block.height.toBigNat.toBigInt == 6),
          )(new IllegalStateException("ordinary V2 candidate missing"))
          states <- cluster.nodes.traverse(node =>
            accepted(node.safety.snapshot),
          )
          votes = snapshots.head.votes.valuesIterator
            .filter(_.targetProposalId == candidate.proposalId)
            .toVector
          _ <- check(
            votes.size == 1 && states.count(_.consensusIntents.nonEmpty) == 1,
            "missing/malformed preimage allowed a follower to persist or emit a vote",
          )
          _ <- check(
            snapshots.forall(!_.qcs.contains(candidate.proposalId)) && states
              .forall(_.appliedEntries.isEmpty),
            "unavailable preimages formed finality/application",
          )
          follower = cluster.nodes
            .find(_.consensus.localKeys.keys.head != votes.head.voter)
            .get
          denied <- follower.consensus.emitVote(
            follower.consensus.localKeys.keys.head,
            candidate,
            start.plusMillis(1L),
          )
          _ <- check(
            denied.isLeft,
            "explicit emission bypassed the missing/malformed preimage gate",
          )
          // Local time may advance and pacemakers may attempt another view; neither
          // changes the real finalized height or expires an existing reservation.
          ownerNode = cluster.nodes
            .find(_.consensus.localKeys.keys.head == votes.head.voter)
            .get
          before <- accepted(ownerNode.safety.snapshot)
          _ <- cluster.nodes.traverse_(_.clock.advance(Duration.ofMinutes(10L)))
          _ <- cluster.nodes.traverse_(
            _.consensus.notifyApplicationWorkAvailable,
          )
          after <- accepted(ownerNode.safety.snapshot)
          _     <- check(
            before.claims.nonEmpty && before.claims.forall((id, claim) =>
              after.claims.get(id).contains(claim),
            ),
            "stalled finality/clock released a live claim",
          )
        yield ()
      }
  }

  def sameWindowConflictAndRestart: IO[Unit] = directory.use { root =>
    val source = new Fixture(33400L, Authority.ConsensusOnly)
    def unchanged(
        node: Node,
        original: Proposal,
        alternative: Proposal,
    ): IO[Unit] = for
      before  <- accepted(node.safety.snapshot)
      _       <- accepted(node.material.retain(alternative))
      request <- accepted(
        node.material.requests.verifyProposal(alternative, source.consensusPlan),
      )
      rejected <- node.voting.prepareConsensusVote(request).value
      _        <- check(
        rejected.left.exists(_.code == RuntimeFailureCode.Conflict),
        "a valid alternative proposal in the signed window did not report Conflict",
      )
      after <- accepted(node.safety.snapshot)
      _     <- check(
        after == before,
        "ordinary same-window conflict fenced or mutated the ready safety inventory",
      )
      originalRequest <- accepted(
        node.material.requests.verifyProposal(original, source.consensusPlan),
      )
      prepared <- accepted(node.voting.prepareConsensusVote(originalRequest))
      retried  <- accepted(node.voting.signConsensusVote(prepared))
      _        <- check(
        retried.targetProposalId == original.proposalId,
        "the original durable vote could not be retried after an ordinary conflict",
      )
    yield ()
    cluster(root, source)
      .use { cluster =>
        for
          _ <- cluster.nodes.traverse_(_.fault.set(PreimageFault.Missing))
          _ <- cluster.seed
          _ <- cluster.pump(12)
          snapshots <- cluster.nodes.traverse(_.snapshot)
          original  <- IO.fromOption(
            snapshots.head.proposals.valuesIterator
              .find(_.block.height == height(6L)),
          )(
            new IllegalStateException(
              "ordinary proposal for same-window conflict is missing",
            ),
          )
          owner = cluster.nodes
            .find(_.consensus.localKeys.contains(original.proposer))
            .get
          alternativeHeader = original.block
            .copy(timestamp = BlockTimestamp.unsafeFromEpochMillis(1002L))
          alternative = Proposal
            .sign(
              UnsignedProposal(
                original.window,
                original.proposer,
                BlockHeader.computeId(alternativeHeader),
                alternativeHeader,
                original.txSet,
                original.justify,
              ),
              owner.consensus.localKeys(original.proposer),
            )
            .toOption
            .get
          _ <- unchanged(owner, original, alternative)
          _ <- cluster.nodes.traverse_(_.save)
        yield (owner.index, original, alternative)
      }
      .flatMap { (index, original, alternative) =>
        cluster(root, source).use { reopened =>
          unchanged(reopened.nodes(index), original, alternative)
        }
      }
  }

  def splitVotesAndRestart: IO[Unit] = directory.use { root =>
    val source = new Fixture(33200L, Authority.ConsensusOnly)
    def vote(node: Node, index: Int): IO[LockVote] = accepted(
      node.material.fastRequest(index),
    ).flatMap(request => accepted(node.voting.voteLock(request)))
    cluster(root, source).use { cluster =>
      for
        _ <- check(
          cluster.nodes.map(_.consensus.localKeys.size) == Vector.fill(4)(
            1,
          ) && cluster.links.size == 12,
          "four independent ordinary identities/transports were not assembled",
        )
        a <- vote(cluster.nodes(0), 0)
        b <- vote(cluster.nodes(1), 1)
        c <- vote(cluster.nodes(2), 0)
        d <- vote(cluster.nodes(3), 0)
        _ <- Vector(
          cluster.nodes(0) -> 1,
          cluster.nodes(1) -> 0,
          cluster.nodes(2) -> 1,
        ).traverse_((node, other) =>
          accepted(node.material.fastRequest(other)).flatMap(request =>
            node.voting
              .voteLock(request)
              .value
              .flatMap(result =>
                check(
                  result.isLeft,
                  "honest runtime signed an incompatible live lock",
                ),
              ),
          ),
        )
        subjectB = cluster.nodes(3).material.fastSubject(1)
        // Deliberately faulty fourth validator signs both subjects outside its
        // honest store. This is the single tolerated Byzantine participant.
        byzantineB = LockVote(
          subjectB,
          ValidatorSignature(
            source.keys(3)._1,
            signed(
              source.keys(3)._2,
              value(LockSubject.signingPreimage(subjectB)),
            ),
          ),
        )
        certificateA = LockCertificate(
          a.subject,
          Vector(a.vote, c.vote, d.vote),
        )
        partialB = LockCertificate(
          b.subject,
          Vector(b.vote, byzantineB.vote),
        )
        _ <- check(
          LockCertificate
            .validateShape(certificateA)
            .isRight && LockCertificate.validateShape(partialB).isRight,
          "split-vote certificate structure",
        )
        _ <- accepted(
          cluster.nodes.head.material.requests
            .verifyLockCertificate(certificateA),
        )
        rejectedB <- cluster.nodes.head.material.requests
          .verifyLockCertificate(partialB)
          .value
        _ <- check(
          rejectedB.isLeft,
          "two conflicting 3-of-4 quorums were accepted with one Byzantine validator",
        )
        _ <- cluster.nodes.traverse_(_.save)
      yield ()
    } *> cluster(root, source).use { reopened =>
      for
        _ <- Vector(
          reopened.nodes(0) -> 1,
          reopened.nodes(1) -> 0,
          reopened.nodes(2) -> 1,
        ).traverse_((node, other) =>
          accepted(node.material.fastRequest(other)).flatMap(request =>
            node.voting
              .voteLock(request)
              .value
              .flatMap(result =>
                check(
                  result.isLeft,
                  "restart forgot an honest split-vote claim",
                ),
              ),
          ),
        )
        snapshots <- reopened.nodes.traverse(node =>
          accepted(node.safety.snapshot),
        )
        _ <- check(
          snapshots.forall(_.locks.size == 1) && snapshots.forall(
            _.locks.values.forall(_.lifecycle == ClaimLifecycle.Live),
          ),
          "split claims were lost or implicitly expired on restart",
        )
      yield ()
    }
  }

  def historicalAncestryNegatives: IO[Unit] = directory.use { root =>
    val source = new Fixture(33300L, Authority.ConsensusOnly)
    cluster(root, source).use { cluster =>
      for
        _ <- cluster.seed
        _ <- cluster.pump(12)
        node = cluster.nodes.head
        rows   <- node.material.history
        target <- accepted(
          finality(node.material, node.material.genesis.targetBlockId.toUInt256),
        )
        sourceOf = (entries: Map[BlockId, Proposal]) =>
          new InitialAnchorHistory[IO]:
            def retained(
                context: DomainContext,
                block: Hash,
            ): Result[IO, Option[Proposal]] =
              if context == node.material.context then
                EitherT.pure(entries.get(BlockId(block)))
              else unavailable("historical proof context")
            def backfill(
                context: DomainContext,
                block: Hash,
            ): Result[IO, Option[Proposal]] = retained(context, block)
        verified <- accepted(
          InitialAnchorAncestry.legacyEmpty(
            node.material.anchor,
            target,
            node.material.validators,
            sourceOf(rows),
            100L,
          ),
        )
        _ <- check(
          verified.descending.map(_.block.height.toBigNat.toBigInt) == Vector(5,
            4, 3, 2, 1, 0).map(BigInt(_)),
          "historical replay skipped an anchor ancestor",
        )
        missing = rows.removed(node.material.initialHistory(2).targetBlockId)
        absent <- InitialAnchorAncestry
          .legacyEmpty(
            node.material.anchor,
            target,
            node.material.validators,
            sourceOf(missing),
            100L,
          )
          .value
        _ <- check(
          absent.left.exists(_.code == RuntimeFailureCode.ProofUnavailable),
          "missing historical ancestor was treated as verified",
        )
        checkpoint = rows(node.material.checkpointBlock)
        forkHeader = checkpoint.block.copy(timestamp =
          BlockTimestamp.unsafeFromEpochMillis(1001L),
        )
        fork = Proposal
          .sign(
            UnsignedProposal(
              checkpoint.window,
              checkpoint.proposer,
              BlockHeader.computeId(forkHeader),
              forkHeader,
              checkpoint.txSet,
              checkpoint.justify,
            ),
            source.keys.head._2,
          )
          .toOption
          .get
        substituted <- InitialAnchorAncestry
          .legacyEmpty(
            node.material.anchor,
            target,
            node.material.validators,
            sourceOf(rows.updated(node.material.checkpointBlock, fork)),
            100L,
          )
          .value
        _ <- check(
          substituted.isLeft,
          "another validly signed checkpoint fork satisfied installed anchor ancestry",
        )
      yield ()
    }
  }

  def conflictingArchive: IO[Unit] = directory.use { root =>
    val source        = new Fixture(33400L, Authority.ConsensusOnly)
    val original      = V2RuntimeMaterial.initialHistory(source)
    val changedHeader = original.head.block
      .copy(timestamp = BlockTimestamp.unsafeFromEpochMillis(1001L))
    val forkRoot = Proposal
      .sign(
        UnsignedProposal(
          original.head.window,
          original.head.proposer,
          BlockHeader.computeId(changedHeader),
          changedHeader,
          ProposalTxSet.empty,
          original.head.justify,
        ),
        source.keys.head._2,
      )
      .toOption
      .get
    // A deliberately contradictory but cryptographically valid old archive.
    // This is fault-detection evidence, not the ordinary four-runtime quorum.
    val fork = (1L to 2L).foldLeft(Vector(forkRoot)) { (past, nextHeight) =>
      val parent = past.last
      val window = HotStuffWindow.unsafe(
        source.chain,
        nextHeight,
        0L,
        source.validators.hash,
      )
      val qc = QuorumCertificate(
        QuorumCertificateSubject(
          parent.window,
          parent.proposalId,
          parent.targetBlockId,
        ),
        source.keys
          .take(3)
          .map((id, key) =>
            Vote
              .sign(
                UnsignedVote(
                  parent.window,
                  ValidatorId.unsafe(id.asString),
                  parent.proposalId,
                ),
                key,
              )
              .toOption
              .get,
          ),
      )
      val header = parent.block.copy(
        parent = Some(parent.targetBlockId),
        height = BlockHeight.unsafeFromLong(nextHeight),
      )
      past :+ Proposal
        .sign(
          UnsignedProposal(
            window,
            source.validators.members.head.id,
            BlockHeader.computeId(header),
            header,
            ProposalTxSet.empty,
            qc,
          ),
          source.keys.head._2,
        )
        .toOption
        .get
    }
    val noPlans = new HotStuffExecutionPlanPreimages[IO]:
      def retain(plan: ExecutionPlan): Result[IO, ExecutionPlanRoot] =
        unavailable("faulted archive must not materialize plans")
      def fetch(root: ExecutionPlanRoot): Result[IO, ExecutionPlan] =
        unavailable("faulted archive must not choose a winner")
    for
      _ <- IO.blocking(Files.createDirectories(root.resolve("artifacts")))
      _ <- (original ++ fork).traverse_(proposal =>
        force(
          root.resolve("artifacts").resolve(proposal.proposalId.toHexLower),
          ByteEncoder[HotStuffGossipArtifact].encode(
            HotStuffGossipArtifact.ProposalArtifact(proposal),
          ),
        ),
      )
      decoded  <- archived(root)
      reopened <- V2RuntimeMaterial.create(
        source,
        source.validators.members.head.id,
      )
      proposals = decoded.collect {
        case HotStuffGossipArtifact.ProposalArtifact(proposal) => proposal
      }
      _ <- proposals.traverse_(proposal => accepted(reopened.retain(proposal)))
      tracker = HotStuffFinalizationTracker.track(proposals)
      _ <- check(
        tracker.safetyFaults.nonEmpty && tracker.bestFinalized.nonEmpty,
        "actual conflicting archive did not retain both a fault and a tempting later candidate",
      )
      _      <- reopened.finalization.set(tracker)
      latest <- history(reopened, noPlans).latestFinalized(source.context).value
      publication <- reopened.publication.finalizedHeight(source.context).value
      _           <- check(
        latest.left.exists(
          _.code == RuntimeFailureCode.ProofInvalid,
        ) && publication.left.exists(_.code == RuntimeFailureCode.ProofInvalid),
        "reopened conflicting finality archive selected a winner or authorized voting",
      )
    yield ()
  }

  def run(): IO[Unit] = automaticAndDrain *> unavailablePreimage(
    PreimageFault.Missing,
  ) *> unavailablePreimage(
    PreimageFault.Malformed,
  ) *> splitVotesAndRestart *> historicalAncestryNegatives *> conflictingArchive *> sameWindowConflictAndRestart
