package org.sigilaris.conformance

import java.nio.file.{Files, Path}
import java.time.{Duration, Instant}
import scala.jdk.CollectionConverters.*
import cats.data.EitherT
import cats.effect.{IO, IOApp, Ref, Resource}
import cats.syntax.all.*
import scodec.bits.ByteVector
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.node.gossip.*
import org.sigilaris.node.gossip.tx.{TxGossipRuntime, TxGossipStateStore}
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.block.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given

/** Actual installed G -> signed opening -> ordinary automatic descendants. Four
  * independent controllers own keys; four journals survive reopen; all future
  * consensus artifacts travel through actual authenticated sessions.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Throw",
  ),
)
object V2BootstrapRuntimeConformance extends IOApp.Simple:
  import V2RequestConformance.*
  import V2VotingConformance.accepted
  val now: Instant = Instant.parse("2026-01-01T00:00:00Z")
  def policy[A](value: Either[HotStuffPolicyViolation, A]): IO[A] =
    IO.fromEither(
      value.leftMap(e =>
        new IllegalStateException(e.reason + e.detail.fold("")(" / " + _)),
      ),
    )
  def transport[A](value: Either[CanonicalRejection, A]): IO[A] =
    IO.fromEither(value.leftMap(e => new IllegalStateException(e.reason)))
  def check(ok: Boolean, detail: String): IO[Unit] = IO(assert(ok, detail))
  val historyVotes = new HistoricalControllerVoteAuthentication:
    def authenticate(
        proposal: Proposal,
        voter: ValidatorId,
        key: Bytes,
    ): Result[IO, ControllerSigningMaterial] = unavailable(
      "new initial domain has no legacy vote branch",
    )
    def authorize(
        proposal: Proposal,
        voter: ValidatorId,
        key: Bytes,
    ): Result[IO, Unit] = unavailable("no legacy key-use in initial domain")
  val preparation = new HotStuffControllerVotePreparation[IO]:
    def prepare(voter: ValidatorId, proposal: Proposal): Result[IO, Unit] =
      unavailable("ordinary initial domain uses durable application voting")
  final case class Source(
      root: Path,
      manifest: ProtocolManifest,
      bootstrap: VerifiedBootstrap,
      verifier: TransitionEvidenceVerifier[IO],
      repository: TransitionEvidenceRepository[IO],
      open: (
          Int,
          DurableJournal[IO] => IO[Option[HotStuffControllerAuthentication]],
      ) => Resource[IO, V2BootstrapFixture.Node],
  )
  final case class Pending(
      index: Int,
      path: Path,
      node: V2BootstrapFixture.Node,
      material: Ref[IO, Option[V2BootstrapRuntimeMaterial]],
      authorization: Ref[IO, Option[HotStuffControllerAuthentication]],
  )
  final case class Node(
      id: String,
      index: Int,
      installation: V2BootstrapFixture.Node,
      parent: VerifiedInitialParent,
      material: V2BootstrapRuntimeMaterial,
      safety: JournalSafetyStore[IO],
      finalizer: FinalizedApplicationRuntime[IO],
      signing: HotStuffControlledSigning[IO],
      plans: HotStuffExecutionPlanPreimages[IO],
      assembly: HotStuffProposalApplicationAssembly[IO],
      runtime: HotStuffNodeRuntime[IO],
      gossip: TxGossipRuntime[IO, HotStuffGossipArtifact],
      path: Path,
  ):
    def snapshot: IO[InMemoryHotStuffSinkSnapshot] =
      runtime.inMemorySink.get.snapshot
    def save: IO[Unit] = for
      rows <- material.all
      _    <- IO.blocking {
        val dir = path.resolve("actual-proposals")
        val _   = Files.createDirectories(dir)
        rows.values.foreach(proposal =>
          val _ = Files.write(
            dir.resolve(proposal.proposalId.toHexLower),
            ByteEncoder[Proposal].encode(proposal).toArray,
          ),
        )
        ()
      }
    yield ()
  final case class Link(from: Node, to: Node, session: DirectionalSessionId)
  final case class Cluster(nodes: Vector[Node], links: Vector[Link]):
    def pump(rounds: Int): IO[Unit] =
      (0 until rounds).toVector.traverse_(_ => links.traverse_(relay))
    def start: IO[Unit]      = startLocal *> pump(12)
    def startLocal: IO[Unit] =
      val first  = nodes.head.material
      val window =
        HotStuffWindow.unsafe(first.chain, 1L, 0L, first.members.hash)
      val leader  = HotStuffPacemaker.deterministicLeader(window, first.members)
      val node    = nodes.find(_.material.voter == leader).get
      val request = HotStuffProposalInputRequest(
        window,
        leader,
        Some(node.parent.genesisBlockId),
        BlockHeight.unsafeFromLong(1L),
        node.parent.quorum,
        now,
        BlockTimestamp.unsafeFromEpochMillis(now.toEpochMilli),
        HotStuffProposalInputBounds.unbounded,
      )
      val unavailableLegacy = new HotStuffProposalInputProvider[IO]:
        def nextProposalInput(
            request: HotStuffProposalInputRequest,
        ): IO[HotStuffProposalInputProviderResult] =
          IO.pure(
            HotStuffProposalInputProviderResult.Failed(
              "noLegacyInitialParent",
              None,
            ),
          )
      for
        supplied <- node.assembly
          .provider(unavailableLegacy)
          .nextProposalInput(request)
        input <- supplied match
          case HotStuffProposalInputProviderResult.Supplied(value) =>
            IO.pure(value)
          case other => IO.raiseError(new IllegalStateException(other.toString))
        _ <- check(
          input.headerVersion == BlockHeaderVersion.V2 && input.txSet.txIds.sizeIs == 1,
          "actual assembler selected sole opening",
        )
        event <- node.runtime
          .emitProposal(
            leader,
            input.blockHeader,
            input.txSet,
            window,
            node.parent.quorum,
            now,
          )
          .flatMap(policy)
        _ <- node.runtime.sink.applyEvent(event).flatMap(transport)
      yield ()
  def archived(path: Path): IO[Map[ProposalId, Proposal]] = IO.blocking {
    val dir = path.resolve("actual-proposals")
    if !Files.exists(dir) then Map.empty
    else
      val stream = Files.list(dir)
      try
        stream
          .iterator()
          .asScala
          .toVector
          .map { file =>
            val decoded = ByteDecoder[Proposal]
              .decode(ByteVector.view(Files.readAllBytes(file)))
              .toOption
              .get
            assert(decoded.remainder.isEmpty)
            decoded.value.proposalId -> decoded.value
          }
          .toMap
      finally stream.close()
  }
  def material(
      source: Source,
      index: Int,
      certificate: BootstrapCertificate,
      path: Path,
  ): IO[V2BootstrapRuntimeMaterial] = for
    rows    <- archived(path)
    archive <- Ref.of[IO, Map[ProposalId, Proposal]](rows)
    live    <- Ref.of[IO, Option[HotStuffNodeRuntime[IO]]](None)
    result = new V2BootstrapRuntimeMaterial(
      source.bootstrap,
      source.manifest,
      certificate,
      ValidatorId.unsafe("v" + (index + 1).toString),
      archive,
      live,
    )
    _ <- rows.values.toVector.traverse_(p =>
      check(
        HotStuffValidator.validateProposal(p, result.members).isRight,
        "restored actual proposal signature",
      ),
    )
  yield result
  def pending(source: Source, index: Int): Resource[IO, Pending] = for
    selected <- Resource.eval(
      Ref.of[IO, Option[V2BootstrapRuntimeMaterial]](None),
    )
    authorization <- Resource.eval(
      Ref.of[IO, Option[HotStuffControllerAuthentication]](None),
    )
    path     = source.root.resolve("validator-" + index.toString)
    original = (journal: DurableJournal[IO]) =>
      authorization.get.flatMap {
        case value @ Some(_) => IO.pure(value)
        case None            =>
          val initial =
            InitialBootstrapConsensus.genesis(source.bootstrap).toOption.get
          (journal.recover *> journal
            .readBlob(
              BootstrapInstallationEvidence.certificateNamespace,
              value(BootstrapSubject.digest(initial.subject)),
            )).value
            .flatMap {
              case Left(_)      => IO.pure(None)
              case Right(bytes) =>
                val certificate =
                  value(BootstrapCertificate.codec.decode(bytes))
                for
                  existing <- selected.get
                  resolved <- existing.fold(
                    material(source, index, certificate, path),
                  )(IO.pure)
                  _ <- selected.set(Some(resolved))
                yield Some(
                  HotStuffControllerAuthentication.authenticated(
                    resolved.profiles,
                    resolved.validators,
                    resolved.controllerArtifacts,
                    historyVotes,
                    ControllerApplicationSigning
                      .recovery(resolved.anchor, journal, resolved.requests),
                    Some(source.bootstrap -> journal),
                    None,
                  ),
                )
            }
      }
    installed <- source.open(index, original)
  yield Pending(index, path, installed, selected, authorization)
  def runtime(
      source: Source,
      pending: Pending,
      certificate: BootstrapCertificate,
  ): IO[Node] = for
    initial <- accepted(
      pending.node.installer.verifyInitialCertificate(certificate),
    )
    cached   <- pending.material.get
    material <- cached.fold(
      V2BootstrapRuntimeConformance.material(
        source,
        pending.index,
        certificate,
        pending.path,
      ),
    )(IO.pure)
    _ <- pending.material.set(Some(material))
    transition = BootstrapHistoryAuthentication.authenticated(
      source.bootstrap,
      source.verifier,
      source.repository,
      pending.node.controller,
    )
    safety <- accepted(
      JournalSafetyStore.open(
        material.anchor,
        pending.node.journal,
        material.publication,
        SafetyProfile(source.manifest, material.artifacts),
        TransitionRecoveryAuthentication.application(
          material.anchor,
          material.requests,
          material.applications,
          transition,
          None,
        ),
        ReservationOrdering.isolated[IO],
        SafetyCapacity.unbounded,
      ),
    )
    plans = HotStuffExecutionPlanPreimages.journaled[IO](
      safety,
      root =>
        IO.pure(
          if value(ExecutionPlan.computeRoot(material.plan)) == root then
            Some(value(ExecutionPlan.codec.encode(material.plan)))
          else if value(ExecutionPlan.computeRoot(ExecutionPlan.empty)) == root
          then Some(value(ExecutionPlan.codec.encode(ExecutionPlan.empty)))
          else None,
        ).liftResult,
    )
    finalizer <- FinalizedApplicationRuntime.authenticated(
      safety,
      material.requests,
      material.applications,
      material.history,
      FinalizedApplicationCapacity(100L),
    )
    _       <- accepted(finalizer.recover)
    signing <- accepted(
      HotStuffControlledSigning.bootstrap(
        initial,
        safety,
        finalizer,
        material.profiles,
        preparation,
      ),
    )
    controller = HotStuffControllerAuthentication.authenticated(
      material.profiles,
      material.validators,
      material.controllerArtifacts,
      historyVotes,
      ControllerApplicationSigning.authenticated(
        safety,
        finalizer,
        material.requests,
        material.publication,
        None,
      ),
      Some(source.bootstrap -> pending.node.journal),
      Some(initial),
    )
    _ <- pending.authorization.set(Some(controller))
    contexts = new HotStuffApplicationVotingContexts[IO]:
      def resolve(
          selected: ProtocolManifest,
          voter: ValidatorId,
      ): Result[IO, HotStuffApplicationVotingContext[IO]] =
        material
          .check(
            selected == source.manifest && voter == material.voter,
            "actual bootstrap local context",
          )
          .as(
            HotStuffApplicationVotingContext(
              source.manifest,
              safety,
              material.requests,
              material.artifacts,
              None,
            ),
          )
    voting = HotStuffApplicationVoting.journaled(
      material.schedule,
      plans,
      contexts,
      HotStuffLegacyApplicationAuthentication.unsupported[IO],
    )
    maintenanceSource = new HotStuffMaintenanceSource[IO]:
      def current(
          request: HotStuffProposalInputRequest,
      ): Result[IO, HotStuffMaintenanceObservation] =
        EitherT.liftF(
          material.finalization.map(snapshot =>
            HotStuffMaintenanceObservation(
              Some(
                HotStuffMaintenanceTarget(
                  uint(998L),
                  material.context,
                  height(2L),
                ),
              ),
              Some(snapshot),
            ),
          ),
        )
    maintenance <- HotStuffMaintenanceProgress
      .bounded[IO](maintenanceSource, 20, Duration.ofMinutes(5))
      .flatMap(r => IO.fromEither(r.leftMap(new IllegalStateException(_))))
    assembly = HotStuffProposalApplicationAssembly.default(
      material.schedule,
      material.parentRepository(initial),
      material.validators,
      material.execution,
      plans,
      maintenance,
    )
    id      = "initial-v2-" + pending.index.toString
    holders = material.members.members.zipWithIndex.map((member, index) =>
      ValidatorKeyHolder(
        member.id,
        PeerIdentity.unsafe("initial-v2-" + index.toString),
        ValidatorKeyHolderStatus.Active,
      ),
    )
    clock = new GossipClock[IO]:
      def now: IO[Instant] = IO.pure(V2BootstrapRuntimeConformance.now)
    node <-
      given GossipClock[IO] = clock
      HotStuffNodeRuntime
        .create[IO](
          PeerIdentity.unsafe(id),
          LocalNodeRole.Validator,
          holders,
          material.members,
          Map.empty,
          automaticConsensus = true,
          proposalInputConfig =
            HotStuffProposalInputRuntimeConfig.application(assembly, None),
          proposalValidationConfig =
            HotStuffProposalValidationRuntimeConfig.controlledApplication(
              voting,
              finalizer,
              signing,
              Some(initial),
              None,
            ),
        )
        .flatMap(policy)
    _        <- material.live.set(Some(node))
    topology <- IO.fromEither(
      StaticPeerTopology
        .parse(
          id,
          (0 until 4)
            .filter(_ != pending.index)
            .map(i => "initial-v2-" + i.toString)
            .toList,
          (0 until 4)
            .filter(_ != pending.index)
            .map(i => "initial-v2-" + i.toString)
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
      node.source,
      node.sink,
      node.topicContracts,
      gossipState,
      HotStuffRuntimeBootstrap.DefaultRuntimePolicy,
    )
  yield Node(
    id,
    pending.index,
    pending.node,
    initial,
    material,
    safety,
    finalizer,
    signing,
    plans,
    assembly,
    node,
    gossip,
    pending.path,
  )
  extension [A](io: IO[A])
    private def liftResult: Result[IO, A]            = EitherT.liftF(io)
  def cluster(source: Source): Resource[IO, Cluster] =
    (0 until 4).toVector.traverse(index => pending(source, index)).evalMap {
      pending =>
        for
          records <- pending.traverse(p =>
            accepted(
              p.node.installer.bind(source.bootstrap.source, source.bootstrap),
            ).flatMap(record => accepted(p.node.installer.install(record))),
          )
          initialVotes <- pending
            .zip(records)
            .traverse((p, record) =>
              accepted(p.node.installer.prepareInitialVote(record))
                .flatMap(v => accepted(p.node.installer.signInitialVote(v))),
            )
          certificates <- Vector(
            initialVotes.take(3),
            initialVotes.drop(1),
            initialVotes,
            initialVotes.take(3),
          ).traverse(votes =>
            IO.fromEither(
              InitialBootstrapConsensus
                .assemble(source.bootstrap, votes)
                .leftMap(e => new IllegalStateException(e.message)),
            ),
          )
          nodes <- pending
            .zip(certificates)
            .traverse((p, certificate) => runtime(source, p, certificate))
          links <- nodes
            .flatMap(from =>
              nodes.filterNot(_.index == from.index).map(to => from -> to),
            )
            .traverse(connect)
        yield Cluster(nodes, links)
    }
  def connect(pair: (Node, Node)): IO[Link] =
    val (from, to)    = pair
    val subscriptions = SessionSubscription.unsafe(
      ChainTopic(from.material.chain, GossipTopic.consensusProposal),
      ChainTopic(from.material.chain, GossipTopic.consensusVote),
      ChainTopic(from.material.chain, GossipTopic.consensusTimeoutVote),
      ChainTopic(from.material.chain, GossipTopic.consensusNewView),
    )
    for
      proposal <- from.gossip
        .startOutbound(PeerIdentity.unsafe(to.id), subscriptions)
        .flatMap(transport)
      response <- to.gossip.handleInboundProposal(proposal)
      ack      <- response match
        case InboundHandshakeResult.Accepted(ack, _)  => IO.pure(ack)
        case failure: InboundHandshakeResult.Rejected =>
          IO.raiseError(new IllegalStateException(failure.rejection.reason))
      _ <- from.gossip.applyHandshakeAck(ack).flatMap(transport)
    yield Link(from, to, proposal.sessionId)
  def relay(link: Link): IO[Unit] = for
    messages <- link.from.gossip.pollEvents(link.session).flatMap(transport)
    decoded = messages.map {
      case EventStreamMessage.Event(event) =>
        val bytes  = ByteEncoder[HotStuffGossipArtifact].encode(event.payload)
        val parsed =
          ByteDecoder[HotStuffGossipArtifact].decode(bytes).toOption.get
        assert(parsed.remainder.isEmpty)
        EventStreamMessage.Event(event.copy(payload = parsed.value))
      case other => other
    }
    _ <- link.to.gossip.receiveEvents(link.session, decoded).flatMap(transport)
  yield ()
  def exercise(source: Source): IO[Unit] = for
    _ <- cluster(source).use { cluster =>
      for
        _ <- IO {
          val quorums = cluster.nodes.map(_.parent.quorum)
          assert(quorums.map(_.votes.size).toSet == Set(3, 4))
          assert(quorums.distinct.size == 3)
          cluster.nodes.foreach { node =>
            val window = HotStuffWindow.unsafe(
              node.material.chain,
              1L,
              0L,
              node.parent.quorum.subject.window.validatorSetHash,
            )
            def validate(quorum: QuorumCertificate) =
              InitialBootstrapParent.validate(
                node.parent,
                window,
                Some(node.parent.genesisBlockId),
                quorum,
              )
            quorums.foreach(quorum => assert(validate(quorum).isRight))
            val quorum = quorums.head
            assert(validate(quorum.copy(votes = quorum.votes.take(2))).isLeft)
            assert(
              validate(
                quorum.copy(votes = quorum.votes :+ quorum.votes.head),
              ).isLeft,
            )
            assert(
              validate(
                quorum.copy(votes =
                  quorum.votes.updated(
                    0,
                    quorum.votes.head.copy(signature =
                      quorum.votes.head.signature.copy(s = uint(0)),
                    ),
                  ),
                ),
              ).isLeft,
            )
          }
        }
        _ <- check(
          cluster.nodes.forall(_.runtime.localKeys.isEmpty),
          "supported runtime exposes no raw local keys",
        )
        _ <- cluster.nodes.traverse_ { node =>
          IO {
            val (cache, exclusion) =
              HotStuffProposalTxUniqueness.exclusionForParent(
                node.material.chain,
                Some(node.parent.genesisBlockId),
                Vector.empty,
                Map.empty,
                HotStuffProposalTxUniquenessBounds.default,
                HotStuffProposalTxUniquenessCache.empty,
                Some(node.parent),
              )
            assert((exclusion match
              case HotStuffProposalTxUniquenessResult.Accepted(_) => true
              case _                                              => false
            ) && exclusion.metadata.bestFinalizedBlockId.isEmpty)
            assert(
              cache.entries.keys.forall(key =>
                key.bestFinalizedBlockId.isEmpty && key.initialStopBlockId
                  .contains(node.parent.genesisBlockId),
              ),
            )
          }
        }
        _         <- cluster.start
        snapshots <- cluster.nodes.traverse(_.snapshot)
        statuses  <- cluster.nodes.traverse(_.finalizer.status)
        _         <- check(
          statuses.forall(status =>
            status.failure.isEmpty && status.canonical.exists(
              _.height.toBigNat.toBigInt >= 2,
            ),
          ),
          "actual first opening and ordinary descendants materialized at every validator",
        )
        _ <- snapshots.traverse_(snapshot =>
          check(
            snapshot.proposals.values
              .exists(_.block.height.toBigNat.toBigInt == 1) &&
              snapshot.proposals.values
                .forall(_.block.height.toBigNat.toBigInt > 0),
            "no fabricated G proposal",
          ),
        )
        _ <- cluster.nodes.traverse_ { node =>
          for
            state <- accepted(node.safety.snapshot)
            _     <- check(
              state.consensusIntents.nonEmpty && state.appliedEntries.contains(
                node.material.executionId,
              ),
              "actual durable first opening vote and applied index",
            )
            _ <- check(
              state.locks.isEmpty && state.intents.isEmpty,
              "initial opening carries no lock/effect votes",
            )
            original = HotStuffControllerAuthentication.authenticated(
              node.material.profiles,
              node.material.validators,
              node.material.controllerArtifacts,
              historyVotes,
              ControllerApplicationSigning.recovery(
                node.material.anchor,
                node.installation.journal,
                node.material.requests,
              ),
              Some(source.bootstrap -> node.installation.journal),
              Some(node.parent),
            )
            intent             = state.consensusIntents.values.head
            applicationRequest = value(
              HotStuffControllerRequests.application(
                node.material.context,
                intent.unsignedVoteSignBytes,
              ),
            )
            _ <- accepted(
              original.signing(
                applicationRequest,
                intent.validatorId,
                node.installation.controller.publicKey,
              ),
            )
            wrongKey = cluster.nodes
              .find(_.index != node.index)
              .get
              .installation
              .controller
              .publicKey
            wrong <- original
              .signing(applicationRequest, intent.validatorId, wrongKey)
              .value
            _ <- check(
              wrong.isLeft,
              "application request rejects an actual different validator key before key use",
            )
            actual <- node.material.all
            qc = actual.values
              .find(_.block.height.toBigNat.toBigInt == 2)
              .get
              .justify
            timeout = UnsignedTimeoutVote(
              TimeoutVoteSubject(
                qc.subject.window.copy(view = qc.subject.window.view.next),
                qc.subject,
              ),
              node.material.voter,
            )
            timeoutRequest = value(HotStuffControllerRequests.timeout(timeout))
            before <- accepted(node.installation.controller.audit)
            bypass <- node.installation.controller.sign(timeoutRequest).value
            after  <- accepted(node.installation.controller.audit)
            _      <- check(
              bypass.isLeft && before.observedSignatures == after.observedSignatures,
              "raw controller control request requires actual finalizer and safety signing leases",
            )
            _ <- accepted(node.signing.timeout(timeout))
            _ <- node.save
          yield ()
        }
      yield ()
    }
    _ <- cluster(source).use { cluster =>
      cluster.nodes.traverse_ { node =>
        for
          status <- node.finalizer.status
          _      <- check(
            status.failure.isEmpty && status.canonical.exists(
              _.height.toBigNat.toBigInt >= 2,
            ),
            "same real controller/application journals recovered ordinary finality",
          )
          _ <- check(
            node.parent.statePayload == source.bootstrap.source.closure.statePayload,
            "reopened runtime consumes exact installed source state",
          )
        yield ()
      }
    }
  yield ()

  def abandonedOpening: IO[Unit] = V2BootstrapConformance.temporary.use {
    root =>
      V2BootstrapFixture.material(root).use { original =>
        accepted(original.verified).flatMap { bootstrap =>
          val source = Source(
            root,
            original.manifest,
            bootstrap,
            original.verifier,
            original.repository,
            (index, auth) =>
              original
                .node(index, bootstrap, JournalFaultInjector.none[IO], auth),
          )
          for
            held <- cluster(source).use { active =>
              for
                _         <- active.startLocal
                snapshots <- active.nodes.traverse(_.snapshot)
                leaderIndex = snapshots.indexWhere(_.proposals.nonEmpty)
                leader      = active.nodes(leaderIndex)
                proposal    = snapshots(leaderIndex).proposals.values
                  .find(_.block.height.toBigNat.toBigInt == 1)
                  .get
                vote <- leader.runtime
                  .emitVote(leader.material.voter, proposal, now)
                  .flatMap(policy)
                _ <- leader.runtime.sink.applyEvent(vote).flatMap(transport)
                before <- accepted(leader.safety.snapshot)
                owners = before.claims.values
                  .filter(_.owner.executionId == leader.material.executionId)
                  .toVector
                _ <- check(
                  owners.nonEmpty && owners.forall(c =>
                    c.lifecycle == ClaimLifecycle.Live &&
                      c.lastInclusionHeight == leader.material.envelope.lastInclusionHeight,
                  ),
                  "an actual signed opening vote retains its complete signed-deadline reservations",
                )
                _ <- check(
                  before.appliedEntries.isEmpty && before.canonical.isEmpty,
                  "one real opening vote cannot publish application success",
                )
                observed <- leader.material.finalization
                _        <- check(
                  observed.bestFinalized.isEmpty,
                  "withheld quorum leaves actual first-opening finality unavailable",
                )
                _ <- active.nodes
                  .traverse_(n => accepted(n.finalizer.recover).void)
                other = active.nodes.find(_.index != leader.index).get
                replay <- other.material.transactions
                  .authenticate(
                    other.material.context,
                    other.material.family,
                    original.dumpRaw,
                  )
                  .value
                _ <- check(
                  replay.isLeft,
                  "source-state authority signatures are not target user transaction authorization",
                )
                changed = other.material.opening.copy(envelope =
                  other.material.envelope
                    .copy(lastInclusionHeight = height(11L)),
                )
                changedReplay <- other.material.transactions
                  .authenticate(
                    other.material.context,
                    other.material.family,
                    value(SignedOpening.codec.encode(changed)),
                  )
                  .value
                _ <- check(
                  changedReplay.isLeft,
                  "the explicit target opening policy binds the signed deadline without resigning",
                )
                emptyHeader = proposal.block.copy(
                  bodyRoot = other.parent.genesis.bodyRoot,
                  executionPlanRoot = Some(
                    value(ExecutionPlan.computeRoot(ExecutionPlan.empty)),
                  ),
                )
                empty <- accepted(
                  leader.signing.proposal(
                    UnsignedProposal(
                      proposal.window,
                      proposal.proposer,
                      BlockHeader.computeId(emptyHeader),
                      emptyHeader,
                      ProposalTxSet.empty,
                      proposal.justify,
                    ),
                  ),
                )
                keysBefore <- accepted(other.installation.controller.audit)
                bypass     <- other.runtime.emitVote(
                  other.material.voter,
                  empty,
                  now,
                )
                keysAfter <- accepted(other.installation.controller.audit)
                _         <- check(
                  bypass.isLeft && keysBefore.observedSignatures == keysAfter.observedSignatures,
                  "a fresh honest validator cannot bypass mandatory opening with an empty first block",
                )
                after <- accepted(leader.safety.snapshot)
                _     <- check(
                  after.claims == before.claims && after.index == before.index &&
                    after.appliedEntries.isEmpty && after.canonical.isEmpty,
                  "abandonment and failed empty-block bypass cannot expire or publish the opening",
                )
                _ <- active.nodes.traverse_(_.save)
              yield (leader.index, after.claims, after.index)
            }
            _ <- cluster(source).use { resumed =>
              for
                state <- accepted(resumed.nodes(held._1).safety.snapshot)
                _     <- check(
                  state.claims == held._2 && state.index == held._3 &&
                    state.appliedEntries.isEmpty && state.canonical.isEmpty,
                  "same physical journal restart retains the abandoned opening and original signed deadline",
                )
                statuses <- resumed.nodes.traverse(_.finalizer.status)
                _        <- check(
                  statuses.forall(_.canonical.exists(_.height == height(0L))),
                  "the installed G state remains unpublished as an ordinary opening until actual quorum progress resumes",
                )
                _       <- resumed.start
                applied <- resumed.nodes
                  .traverse(n => accepted(n.safety.snapshot))
                _ <- check(
                  applied.forall(
                    _.appliedEntries
                      .contains(resumed.nodes.head.material.executionId),
                  ),
                  "only actual resumed quorum/finality resolves the original opening reservations",
                )
              yield ()
            }
          yield ()
        }
      }
  }

  def initialOrdinary: IO[Unit] = V2BootstrapConformance.temporary.use { root =>
    V2BootstrapFixture.material(root).use { original =>
      accepted(original.verified).flatMap { bootstrap =>
        exercise(
          Source(
            root,
            original.manifest,
            bootstrap,
            original.verifier,
            original.repository,
            (index, auth) =>
              original
                .node(index, bootstrap, JournalFaultInjector.none[IO], auth),
          ),
        )
      }
    }
  }
  def retiredOrdinary(present: Boolean, partlyAbsent: Boolean): IO[Unit] =
    V2BootstrapConformance.temporary.use { root =>
      V2RetiredBootstrapFixture.material(root, present, partlyAbsent).use {
        original =>
          accepted(original.verified).flatMap { bootstrap =>
            exercise(
              Source(
                root,
                original.base.manifest,
                bootstrap,
                original.verifier,
                original.repository,
                (index, auth) => original.node(index, bootstrap, auth),
              ),
            )
          }
      }
    }
  def run: IO[Unit] =
    initialOrdinary *> IO.println(
      "v2-bootstrap-runtime no-prior opening/finality/restart passed",
    ) *>
      retiredOrdinary(true, false) *> IO.println(
        "v2-bootstrap-runtime retired-present opening/finality/restart passed",
      ) *>
      retiredOrdinary(false, false) *> IO.println(
        "v2-bootstrap-runtime retired-absent opening/finality/restart passed",
      ) *>
      retiredOrdinary(true, true) *> IO.println(
        "v2-bootstrap-runtime retired-partial opening/finality/restart passed",
      ) *> abandonedOpening *> IO.println(
        "v2-bootstrap-runtime abandoned opening reservations/replay/restart passed",
      )
