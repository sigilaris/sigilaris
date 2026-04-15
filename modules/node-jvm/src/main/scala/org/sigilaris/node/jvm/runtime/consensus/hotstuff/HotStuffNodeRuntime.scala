package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import cats.effect.kernel.{Async, Sync}
import cats.syntax.all.*

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.{Hash, KeyPair}
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.block.{
  BlockBody,
  BlockHeader,
  BlockHeight,
  BlockQuery,
  BlockRecord,
  BlockStore,
  BlockTimestamp,
  BlockView,
  StateRoot,
}
import org.sigilaris.node.gossip.*

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class HotStuffRuntimeBootstrapInput(
    localPeer: PeerIdentity,
    role: LocalNodeRole,
    holders: Vector[ValidatorKeyHolder],
    validatorSet: ValidatorSet,
    localKeys: Map[ValidatorId, KeyPair],
    gossipPolicy: HotStuffGossipPolicy = HotStuffGossipPolicy.default,
    bootstrapTrustRootOverride: Option[BootstrapTrustRoot] = None,
):
  def bootstrapTrustRoot: BootstrapTrustRoot =
    bootstrapTrustRootOverride.getOrElse:
      BootstrapTrustRoot.staticValidatorSet(validatorSet)

/** Runtime-owned HotStuff service surface.
  *
  * The bootstrap caller owns the coherence between `publisher` and `source`. If
  * locally emitted artifacts must later be readable through `source`, both
  * endpoints need to target the same backing store or an equivalent replicated
  * feed.
  */
final case class HotStuffRuntimeServices[F[_]](
    publisher: HotStuffArtifactPublisher[F],
    source: GossipArtifactSource[F, HotStuffGossipArtifact],
    sink: GossipArtifactSink[F, HotStuffGossipArtifact],
    topicContracts: GossipTopicContractRegistry[HotStuffGossipArtifact],
    bootstrap: HotStuffBootstrapServices[F],
)

/** In-memory diagnostics handles for the gossip artifact source and sink. */
final case class HotStuffInMemoryRuntimeDiagnostics[F[_]](
    source: InMemoryHotStuffArtifactSource[F],
    sink: InMemoryHotStuffArtifactSink[F],
)

/** The assembled HotStuff consensus node runtime, providing artifact emission, bootstrap, and diagnostics. */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class HotStuffNodeRuntime[F[_]: Sync](
    bootstrapInput: HotStuffRuntimeBootstrapInput,
    services: HotStuffRuntimeServices[F],
    diagnostics: Option[HotStuffInMemoryRuntimeDiagnostics[F]] = None,
    bootstrapLifecycle: Option[HotStuffBootstrapLifecycle[F]] = None,
    pacemakerSnapshot: Option[F[HotStuffPacemakerRuntimeSnapshot]] = None,
):
  def localPeer: PeerIdentity = bootstrapInput.localPeer

  def role: LocalNodeRole = bootstrapInput.role

  def holders: Vector[ValidatorKeyHolder] = bootstrapInput.holders

  def validatorSet: ValidatorSet = bootstrapInput.validatorSet

  def localKeys: Map[ValidatorId, KeyPair] = bootstrapInput.localKeys

  def gossipPolicy: HotStuffGossipPolicy = bootstrapInput.gossipPolicy

  def bootstrapTrustRoot: BootstrapTrustRoot =
    bootstrapInput.bootstrapTrustRoot

  def bootstrapServices: HotStuffBootstrapServices[F] =
    services.bootstrap

  def source: GossipArtifactSource[F, HotStuffGossipArtifact] = services.source

  def sink: GossipArtifactSink[F, HotStuffGossipArtifact] = services.sink

  def topicContracts: GossipTopicContractRegistry[HotStuffGossipArtifact] =
    services.topicContracts

  def inMemorySource: Option[InMemoryHotStuffArtifactSource[F]] =
    diagnostics.map(_.source)

  def inMemorySink: Option[InMemoryHotStuffArtifactSink[F]] =
    diagnostics.map(_.sink)

  def currentBootstrapDiagnostics: F[BootstrapDiagnostics] =
    bootstrapLifecycle.fold(services.bootstrap.diagnostics.current)(_.current)

  def currentPacemakerSnapshot: F[Option[HotStuffPacemakerRuntimeSnapshot]] =
    pacemakerSnapshot match
      case Some(snapshot) =>
        snapshot.map(_.some)
      case None =>
        none[HotStuffPacemakerRuntimeSnapshot].pure[F]

  def close: F[Unit] =
    bootstrapLifecycle.fold(Sync[F].unit)(_.close)

  def bootstrap(
      chainId: ChainId,
      sessions: Vector[BootstrapSessionBinding],
      startedAt: Instant,
      liveProposals: Vector[Proposal],
  )(using
      Async[F],
  ): F[Either[BootstrapCoordinatorFailure, BootstrapCoordinatorResult]] =
    bootstrapLifecycle match
      case Some(lifecycle) =>
        lifecycle.bootstrap(
          chainId = chainId,
          sessions = sessions,
          startedAt = startedAt,
          liveProposals = liveProposals,
        )
      case None =>
        BootstrapCoordinatorFailure(
          reason = "bootstrapLifecycleUnavailable",
          detail = None,
        ).asLeft[BootstrapCoordinatorResult].pure[F]

  def emitProposal(
      proposer: ValidatorId,
      block: BlockHeader,
      txSet: ProposalTxSet,
      window: HotStuffWindow,
      justify: QuorumCertificate,
      ts: Instant,
  ): F[Either[HotStuffPolicyViolation, GossipEvent[HotStuffGossipArtifact]]] =
    withLocalSigner(proposer, window.chainId): keyPair =>
      Proposal.sign(
        UnsignedProposal(
          window = window,
          proposer = proposer,
          targetBlockId = BlockHeader.computeId(block),
          block = block,
          txSet = txSet,
          justify = justify,
        ),
        keyPair,
      ) match
        case Left(error) =>
          HotStuffPolicyViolation(
            reason = "proposalSigningFailed",
            detail = Some(
              ss"${error.reason}:${error.detail.getOrElse("")}",
            ),
          ).asLeft[GossipEvent[HotStuffGossipArtifact]].pure[F]
        case Right(proposal) =>
          services.publisher
            .append(
              HotStuffGossipArtifact.ProposalArtifact(proposal),
              ts,
            )
            .map(_.asRight[HotStuffPolicyViolation])

  def signTimeoutVote(
      voter: ValidatorId,
      window: HotStuffWindow,
      highestKnownQc: QuorumCertificate,
  ): F[Either[HotStuffPolicyViolation, TimeoutVote]] =
    withLocalSigner(voter, window.chainId): keyPair =>
      TimeoutVote.sign(
        UnsignedTimeoutVote(
          subject = TimeoutVoteSubject(
            window = window,
            highestKnownQc = highestKnownQc.subject,
          ),
          voter = voter,
        ),
        keyPair,
      ) match
        case Left(error) =>
          HotStuffPolicyViolation(
            reason = "timeoutVoteSigningFailed",
            detail = Some(
              ss"${error.reason}:${error.detail.getOrElse("")}",
            ),
          ).asLeft[TimeoutVote].pure[F]
        case Right(timeoutVote) =>
          timeoutVote.asRight[HotStuffPolicyViolation].pure[F]

  def emitTimeoutVote(
      voter: ValidatorId,
      window: HotStuffWindow,
      highestKnownQc: QuorumCertificate,
      ts: Instant,
  ): F[Either[HotStuffPolicyViolation, GossipEvent[HotStuffGossipArtifact]]] =
    signTimeoutVote(voter, window, highestKnownQc).flatMap:
      case Left(rejection) =>
        rejection.asLeft[GossipEvent[HotStuffGossipArtifact]].pure[F]
      case Right(timeoutVote) =>
        services.publisher
          .append(
            HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote),
            ts,
          )
          .map(_.asRight[HotStuffPolicyViolation])

  def signNewView(
      sender: ValidatorId,
      highestKnownQc: QuorumCertificate,
      timeoutCertificate: TimeoutCertificate,
  ): F[Either[HotStuffPolicyViolation, NewView]] =
    val nextWindow =
      HotStuffPacemaker.nextWindowAfter(timeoutCertificate.subject.window)
    val nextLeader =
      HotStuffPacemaker.deterministicLeader(nextWindow, validatorSet)
    withLocalSigner(sender, nextWindow.chainId): keyPair =>
      NewView.sign(
        UnsignedNewView(
          window = nextWindow,
          sender = sender,
          nextLeader = nextLeader,
          highestKnownQc = highestKnownQc,
          timeoutCertificate = timeoutCertificate,
        ),
        keyPair,
      ) match
        case Left(error) =>
          HotStuffPolicyViolation(
            reason = "newViewSigningFailed",
            detail = Some(
              ss"${error.reason}:${error.detail.getOrElse("")}",
            ),
          ).asLeft[NewView].pure[F]
        case Right(newView) =>
          newView.asRight[HotStuffPolicyViolation].pure[F]

  def emitNewView(
      sender: ValidatorId,
      highestKnownQc: QuorumCertificate,
      timeoutCertificate: TimeoutCertificate,
      ts: Instant,
  ): F[Either[HotStuffPolicyViolation, GossipEvent[HotStuffGossipArtifact]]] =
    signNewView(sender, highestKnownQc, timeoutCertificate).flatMap:
      case Left(rejection) =>
        rejection.asLeft[GossipEvent[HotStuffGossipArtifact]].pure[F]
      case Right(newView) =>
        services.publisher
          .append(
            HotStuffGossipArtifact.NewViewArtifact(newView),
            ts,
          )
          .map(_.asRight[HotStuffPolicyViolation])

  def emitProposalFromCandidates[
      TxRef: ByteEncoder: Hash,
      ResultRef: ByteEncoder,
      Event: ByteEncoder,
  ](
      proposer: ValidatorId,
      candidates: Iterable[BlockRecord[TxRef, ResultRef, Event]],
      parent: Option[BlockId],
      height: BlockHeight,
      stateRoot: StateRoot,
      timestamp: BlockTimestamp,
      window: HotStuffWindow,
      justify: QuorumCertificate,
      ts: Instant,
      blockStore: BlockStore[F, TxRef, ResultRef, Event],
  )(
      classifyTx: TxRef => org.sigilaris.core.application.scheduling.SchedulingClassification,
  ): F[Either[
    HotStuffRuntimeRejection,
    HotStuffProposalEmission[TxRef, ResultRef, Event],
  ]] =
    val selection =
      ConflictFreeBlockBodySelector.select(candidates)(classifyTx)
    selection.toBody match
      case Left(failure) =>
        HotStuffRuntimeRejection
          .Validation(failure)
          .asLeft[HotStuffProposalEmission[TxRef, ResultRef, Event]]
          .pure[F]
      case Right(body) =>
        BlockBody
          .computeBodyRoot(body)
          .leftMap(HotStuffRuntimeScheduling.fromBlockValidationFailure) match
          case Left(failure) =>
            HotStuffRuntimeRejection
              .Validation(failure)
              .asLeft[HotStuffProposalEmission[TxRef, ResultRef, Event]]
              .pure[F]
          case Right(bodyRoot) =>
            val view =
              BlockView(
                header = BlockHeader(
                  parent = parent,
                  height = height,
                  stateRoot = stateRoot,
                  bodyRoot = bodyRoot,
                  timestamp = timestamp,
                ),
                body = body,
              )
            blockStore
              .putView(view)
              .leftMap(HotStuffRuntimeScheduling.fromBlockValidationFailure)
              .value
              .flatMap:
                case Left(failure) =>
                  HotStuffRuntimeRejection
                    .Validation(failure)
                    .asLeft[HotStuffProposalEmission[TxRef, ResultRef, Event]]
                    .pure[F]
                case Right(_) =>
                  val proposalTxSet =
                    ProposalTxSet.fromTxs(
                      view.body.records.toVector.map(_.tx),
                    )
                  emitProposal(
                    proposer = proposer,
                    block = view.header,
                    txSet = proposalTxSet,
                    window = window,
                    justify = justify,
                    ts = ts,
                  ).map(
                    _.leftMap(HotStuffRuntimeRejection.Policy.apply).map:
                      event =>
                        HotStuffProposalEmission(
                          selection = selection,
                          view = view,
                          event = event,
                        ),
                  )

  def emitVote(
      voter: ValidatorId,
      proposal: Proposal,
      ts: Instant,
  ): F[Either[HotStuffPolicyViolation, GossipEvent[HotStuffGossipArtifact]]] =
    withLocalSigner(voter, proposal.window.chainId): keyPair =>
      Vote.sign(
        UnsignedVote(
          window = proposal.window,
          voter = voter,
          targetProposalId = proposal.proposalId,
        ),
        keyPair,
      ) match
        case Left(error) =>
          HotStuffPolicyViolation(
            reason = "voteSigningFailed",
            detail = Some(
              ss"${error.reason}:${error.detail.getOrElse("")}",
            ),
          ).asLeft[GossipEvent[HotStuffGossipArtifact]].pure[F]
        case Right(vote) =>
          services.publisher
            .append(
              HotStuffGossipArtifact.VoteArtifact(vote),
              ts,
            )
            .map(_.asRight[HotStuffPolicyViolation])

  def emitVoteForProposalView[
      TxRef: ByteEncoder: Hash,
      ResultRef: ByteEncoder,
      Event: ByteEncoder,
  ](
      voter: ValidatorId,
      proposal: Proposal,
      ts: Instant,
      blockQuery: BlockQuery[F, TxRef, ResultRef, Event],
  )(
      classifyTx: TxRef => org.sigilaris.core.application.scheduling.SchedulingClassification,
  ): F[Either[HotStuffRuntimeRejection, GossipEvent[HotStuffGossipArtifact]]] =
    HotStuffRuntimeScheduling
      .validateProposalViewFromBlockQuery(
        proposal = proposal,
        validatorSet = validatorSet,
        blockQuery = blockQuery,
      )(classifyTx)
      .flatMap:
        case Left(failure) =>
          HotStuffRuntimeRejection
            .Validation(failure)
            .asLeft[GossipEvent[HotStuffGossipArtifact]]
            .pure[F]
        case Right(_) =>
          emitVote(voter, proposal, ts)
            .map(_.leftMap(HotStuffRuntimeRejection.Policy.apply))

  private def withLocalSigner[A](
      validatorId: ValidatorId,
      chainId: ChainId,
  )(
      f: KeyPair => F[Either[HotStuffPolicyViolation, A]],
  ): F[Either[HotStuffPolicyViolation, A]] =
    resolveSigner(validatorId) match
      case Left(rejection) =>
        rejection.asLeft[A].pure[F]
      case Right(keyPair) =>
        ensureQuorumParticipationReadiness(chainId).flatMap:
          case Left(rejection) =>
            rejection.asLeft[A].pure[F]
          case Right(_) =>
            f(keyPair)

  private def resolveSigner(
      validatorId: ValidatorId,
  ): Either[HotStuffPolicyViolation, KeyPair] =
    HotStuffPolicy
      .canEmitLocally(role, localPeer, validatorId, holders)
      .flatMap: _ =>
        localKeys
          .get(validatorId)
          .toRight:
            HotStuffPolicyViolation(
              reason = "localValidatorKeyUnavailable",
              detail = Some(ss"${validatorId.value}@${localPeer.value}"),
            )

  private def ensureQuorumParticipationReadiness(
      chainId: ChainId,
  ): F[Either[HotStuffPolicyViolation, Unit]] =
    bootstrapLifecycle match
      case Some(lifecycle) if role === LocalNodeRole.Validator =>
        lifecycle
          .voteReadiness(chainId)
          .map:
            case BootstrapVoteReadiness.Ready =>
              ().asRight[HotStuffPolicyViolation]
            case BootstrapVoteReadiness.Held(reason) =>
              HotStuffPolicyViolation(
                reason = "bootstrapVoteHeld",
                detail = Some(reason),
              ).asLeft[Unit]
      case _ =>
        ().asRight[HotStuffPolicyViolation].pure[F]

/** Companion for `HotStuffNodeRuntime`, providing validation and factory methods. */
object HotStuffNodeRuntime:
  /** Validates bootstrap input by checking for dual-active key holder violations. */
  def validateBootstrapInput(
      bootstrapInput: HotStuffRuntimeBootstrapInput,
  ): Either[HotStuffPolicyViolation, HotStuffRuntimeBootstrapInput] =
    HotStuffPolicy
      .ensureDistinctActiveKeyHolders(bootstrapInput.holders)
      .map(_ => bootstrapInput)

  private[hotstuff] def fromValidatedServices[F[_]: Sync](
      bootstrapInput: HotStuffRuntimeBootstrapInput,
      services: HotStuffRuntimeServices[F],
      diagnostics: Option[HotStuffInMemoryRuntimeDiagnostics[F]],
      bootstrapLifecycle: Option[HotStuffBootstrapLifecycle[F]],
  ): HotStuffNodeRuntime[F] =
    HotStuffNodeRuntime(
      bootstrapInput = bootstrapInput,
      services = services,
      diagnostics = diagnostics,
      bootstrapLifecycle = bootstrapLifecycle,
      pacemakerSnapshot = None,
    )

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def fromServices[F[_]: Sync](
      bootstrapInput: HotStuffRuntimeBootstrapInput,
      services: HotStuffRuntimeServices[F],
      diagnostics: Option[HotStuffInMemoryRuntimeDiagnostics[F]] = None,
      bootstrapLifecycle: Option[HotStuffBootstrapLifecycle[F]] = None,
  ): Either[HotStuffPolicyViolation, HotStuffNodeRuntime[F]] =
    validateBootstrapInput(bootstrapInput)
      .map(fromValidatedServices(_, services, diagnostics, bootstrapLifecycle))

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  // This helper intentionally assembles only the artifact-plane in-memory
  // runtime. The newcomer bootstrap lifecycle is wired by
  // `HotStuffRuntimeBootstrap.fromTopology`, not by the raw test helper path.
  def inMemoryServices[F[_]: Sync](
      validatorSet: ValidatorSet,
      gossipPolicy: HotStuffGossipPolicy = HotStuffGossipPolicy.default,
      relayPolicy: HotStuffRelayPolicy =
        HotStuffRelayPolicy(relayValidatedArtifacts = false),
  )(using
      clock: GossipClock[F],
  ): F[(HotStuffRuntimeServices[F], HotStuffInMemoryRuntimeDiagnostics[F])] =
    for
      source <- InMemoryHotStuffArtifactSource.create[F]
      sink <- InMemoryHotStuffArtifactSink
        .create[F](validatorSet, relayPolicy, source)
    yield
      val diagnostics =
        HotStuffInMemoryRuntimeDiagnostics(source = source, sink = sink)
      val services =
        HotStuffRuntimeServices(
          publisher = source,
          source = source,
          sink = sink,
          topicContracts = HotStuffTopic.registry(gossipPolicy),
          bootstrap =
            HotStuffBootstrapServicesRuntime.inMemory[F](validatorSet, sink),
        )
      services -> diagnostics

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  // `create` remains a lightweight test/runtime convenience that omits the
  // shipped newcomer bootstrap lifecycle. Use `HotStuffRuntimeBootstrap` when
  // the assembled bootstrap gate and diagnostics are required. Automatic
  // consensus stays opt-in here so manual harnesses can seed proposals/votes
  // deterministically; the assembled bootstrap path enables it explicitly.
  def create[F[_]: Sync](
      localPeer: PeerIdentity,
      role: LocalNodeRole,
      holders: Vector[ValidatorKeyHolder],
      validatorSet: ValidatorSet,
      localKeys: Map[ValidatorId, KeyPair],
      gossipPolicy: HotStuffGossipPolicy = HotStuffGossipPolicy.default,
      automaticConsensus: Boolean = false,
  )(using
      clock: GossipClock[F],
  ): F[Either[HotStuffPolicyViolation, HotStuffNodeRuntime[F]]] =
    val bootstrapInput =
      HotStuffRuntimeBootstrapInput(
        localPeer = localPeer,
        role = role,
        holders = holders,
        validatorSet = validatorSet,
        localKeys = localKeys,
        gossipPolicy = gossipPolicy,
      )
    validateBootstrapInput(bootstrapInput) match
      case Left(rejection) =>
        rejection.asLeft[HotStuffNodeRuntime[F]].pure[F]
      case Right(validatedInput) =>
        inMemoryServices[F](
          validatorSet,
          gossipPolicy,
          HotStuffRelayPolicy.forRole(role),
        ).flatMap: (services, diagnostics) =>
          InMemoryHotStuffPacemakerDriver
            .attach(
              fromValidatedServices(
                validatedInput,
                services,
                Some(diagnostics),
                none[HotStuffBootstrapLifecycle[F]],
              ),
              automaticConsensus = automaticConsensus,
            )
            .map(_.asRight[HotStuffPolicyViolation])
