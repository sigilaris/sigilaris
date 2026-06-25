package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID

import cats.effect.LiftIO
import cats.effect.kernel.{Async, Resource, Sync}
import cats.syntax.all.*

import com.typesafe.config.Config

import org.sigilaris.core.application.scheduling.SchedulingClassification
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.block.BlockQuery
import org.sigilaris.node.gossip.*
import org.sigilaris.node.gossip.tx.{TxGossipRuntime, TxRuntimePolicy}
import org.sigilaris.node.jvm.runtime.gossip.{
  StaticPeerBootstrapHttpTransportConfig,
  StaticPeerTopologyConfig,
  StaticPeerTransportAuthConfig,
}
import org.sigilaris.node.jvm.runtime.gossip.tx.{
  TxGossipBootstrap,
  TxGossipRuntimeBootstrap,
}
import org.sigilaris.node.jvm.storage.swaydb.StorageLayout
import org.sigilaris.node.jvm.transport.armeria.gossip.HotStuffBootstrapHttpTransport

/** The assembled runtime bootstrap containing peer topology, authentication,
  * consensus, and gossip runtime.
  */
final case class HotStuffRuntimeBootstrap[F[_]](
    topology: StaticPeerTopology,
    registry: StaticPeerRegistry,
    authenticator: StaticPeerAuthenticator[F],
    transportAuth: StaticPeerTransportAuth,
    consensus: HotStuffNodeRuntime[F],
    runtime: TxGossipRuntime[F, HotStuffGossipArtifact],
):
  /** Releases consensus resources. */
  def close: F[Unit] =
    consensus.close

/** The assembled HotStuff runtime bootstrap whose peer gossip runtime also
  * carries embedder-owned application artifacts.
  */
final case class HotStuffRuntimeBootstrapWithApplications[F[_], A](
    topology: StaticPeerTopology,
    registry: StaticPeerRegistry,
    authenticator: StaticPeerAuthenticator[F],
    transportAuth: StaticPeerTransportAuth,
    consensus: HotStuffNodeRuntime[F],
    runtime: TxGossipRuntime[F, HotStuffPeerArtifact[A]],
    applicationTopics: Vector[ApplicationGossipTopic[F, A]],
):
  /** Releases consensus resources. */
  def close: F[Unit] =
    consensus.close

/** Companion for `HotStuffRuntimeBootstrap`, providing full bootstrap from
  * config or topology.
  */
object HotStuffRuntimeBootstrap:
  /** The default transaction runtime policy for consensus proposals. */
  val DefaultRuntimePolicy: TxRuntimePolicy =
    TxRuntimePolicy(
      maxExactRequestRetriesPerScope =
        Some(HotStuffPolicy.requestPolicy.maxRetryAttemptsPerWindow),
    )

  /** Generates a UUID-formatted idempotency key from a proposal ID for control
    * batch deduplication.
    */
  def proposalControlIdempotencyKey(
      proposal: Proposal,
  ): String =
    val bytes = proposal.proposalId.toUInt256.bytes.take(16).toArray
    bytes(6) = ((bytes(6) & 0x0f) | 0x40).toByte
    bytes(8) = ((bytes(8) & 0x3f) | 0x80).toByte
    val buffer = ByteBuffer.wrap(bytes)
    UUID(buffer.getLong(), buffer.getLong()).toString

  /** Creates a proposal catch-up readiness evaluator backed by a block query.
    */
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def proposalCatchUpReadinessFromBlockQuery[
      F[_]: Sync,
      TxRef: ByteEncoder: Hash,
      ResultRef: ByteEncoder,
      Event: ByteEncoder,
  ](
      validatorSet: ValidatorSet,
      knownTxIds: F[Set[StableArtifactId]],
      blockQuery: BlockQuery[F, TxRef, ResultRef, Event],
      txPolicy: TxRuntimePolicy = DefaultRuntimePolicy,
      idempotencyKeyFor: Proposal => String = proposalControlIdempotencyKey,
  )(
      classifyTx: TxRef => SchedulingClassification,
  ): ProposalCatchUpReadiness[F] =
    ProposalCatchUpReadiness.fromBlockQuery(
      validatorSet = validatorSet,
      knownTxIds = knownTxIds,
      blockQuery = blockQuery,
      txPolicy = txPolicy,
      idempotencyKeyFor = idempotencyKeyFor,
    )(classifyTx)

  /** Bootstraps the full HotStuff runtime from Typesafe Config. */
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def fromConfig[F[_]: Async: LiftIO](
      config: Config,
      clock: GossipClock[F],
      runtimePolicy: TxRuntimePolicy = DefaultRuntimePolicy,
      handshakePolicy: HandshakePolicy = HandshakePolicy.default,
      bootstrapTransport: Option[HotStuffBootstrapTransportServices[F]] = None,
      storageLayout: StorageLayout = StorageLayout.default,
      peerConfigPath: String = StaticPeerTopologyConfig.DefaultPath,
      consensusConfigPath: String = HotStuffBootstrapConfig.DefaultPath,
      proposalInputConfig: HotStuffProposalInputRuntimeConfig[F] =
        HotStuffProposalInputRuntimeConfig.legacyCompatible[F],
      proposalValidationConfig: HotStuffProposalValidationRuntimeConfig[F] =
        HotStuffProposalValidationRuntimeConfig.legacyCompatible[F],
      txUniquenessConfig: HotStuffProposalTxUniquenessRuntimeConfig =
        HotStuffProposalTxUniquenessRuntimeConfig.enforceUnfinalizedAncestors,
      pacemakerPolicy: HotStuffPacemakerPolicy =
        HotStuffPacemakerPolicy.default,
      finalityDrivePolicy: HotStuffFinalityDrivePolicy =
        HotStuffFinalityDrivePolicy.disabled,
  ): Resource[F, Either[String, HotStuffRuntimeBootstrap[F]]] =
    Resource
      .eval:
        Async[F].delay:
          (
            Either
              .catchNonFatal:
                StaticPeerTopologyConfig.load(config, peerConfigPath)
              .leftMap(_.getMessage)
              .flatMap(identity),
            Either
              .catchNonFatal:
                HotStuffBootstrapConfig.load(config, consensusConfigPath)
              .leftMap(_.getMessage)
              .flatMap(identity),
          )
      .flatMap:
        case (Left(peerError), Left(consensusError)) =>
          Resource.pure:
            ss"${peerError}; ${consensusError}"
              .asLeft[HotStuffRuntimeBootstrap[F]]
        case (Left(error), _) =>
          Resource.pure(error.asLeft[HotStuffRuntimeBootstrap[F]])
        case (_, Left(error)) =>
          Resource.pure(error.asLeft[HotStuffRuntimeBootstrap[F]])
        case (Right(topology), Right(consensusConfig)) =>
          StaticPeerTransportAuthConfig.load(
            config,
            topology,
            peerConfigPath,
          ) match
            case Left(error) =>
              Resource.pure(error.asLeft[HotStuffRuntimeBootstrap[F]])
            case Right(transportAuth) =>
              StaticPeerBootstrapHttpTransportConfig
                .load(config, topology, peerConfigPath) match
                case Left(error) =>
                  Resource.pure(error.asLeft[HotStuffRuntimeBootstrap[F]])
                case Right(httpBootstrapConfig) =>
                  val resolvedBootstrapTransport =
                    bootstrapTransport match
                      case Some(transport) =>
                        Resource.pure(transport.some)
                      case None =>
                        httpBootstrapConfig.traverse: config =>
                          HotStuffBootstrapHttpTransport.servicesResource[F](
                            peerBaseUris = config.peerBaseUris,
                            transportAuth = transportAuth,
                            requestTimeout = config.requestTimeout,
                            maxConcurrentRequests =
                              config.maxConcurrentRequests,
                          )
                  resolvedBootstrapTransport.flatMap: transport =>
                    fromTopology(
                      topology = topology,
                      transportAuth = transportAuth,
                      consensusConfig = consensusConfig,
                      clock = clock,
                      runtimePolicy = runtimePolicy,
                      handshakePolicy = handshakePolicy,
                      bootstrapTransport = transport,
                      storageLayout = storageLayout,
                      proposalInputConfig = proposalInputConfig,
                      proposalValidationConfig = proposalValidationConfig,
                      txUniquenessConfig = txUniquenessConfig,
                      pacemakerPolicy = pacemakerPolicy,
                      finalityDrivePolicy = finalityDrivePolicy,
                    )

  /** Bootstraps HotStuff plus embedder-owned application gossip topics from
    * Typesafe Config.
    */
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def fromConfigWithApplicationTopics[F[_]: Async: LiftIO, A](
      config: Config,
      clock: GossipClock[F],
      applicationTopics: Vector[ApplicationGossipTopic[F, A]],
      runtimePolicy: TxRuntimePolicy = DefaultRuntimePolicy,
      handshakePolicy: HandshakePolicy = HandshakePolicy.default,
      bootstrapTransport: Option[HotStuffBootstrapTransportServices[F]] = None,
      storageLayout: StorageLayout = StorageLayout.default,
      peerConfigPath: String = StaticPeerTopologyConfig.DefaultPath,
      consensusConfigPath: String = HotStuffBootstrapConfig.DefaultPath,
      proposalInputConfig: HotStuffProposalInputRuntimeConfig[F] =
        HotStuffProposalInputRuntimeConfig.legacyCompatible[F],
      proposalValidationConfig: HotStuffProposalValidationRuntimeConfig[F] =
        HotStuffProposalValidationRuntimeConfig.legacyCompatible[F],
      txUniquenessConfig: HotStuffProposalTxUniquenessRuntimeConfig =
        HotStuffProposalTxUniquenessRuntimeConfig.enforceUnfinalizedAncestors,
      pacemakerPolicy: HotStuffPacemakerPolicy =
        HotStuffPacemakerPolicy.default,
      finalityDrivePolicy: HotStuffFinalityDrivePolicy =
        HotStuffFinalityDrivePolicy.disabled,
  ): Resource[F, Either[
    String,
    HotStuffRuntimeBootstrapWithApplications[F, A],
  ]] =
    Resource
      .eval:
        Async[F].delay:
          (
            Either
              .catchNonFatal:
                StaticPeerTopologyConfig.load(config, peerConfigPath)
              .leftMap(_.getMessage)
              .flatMap(identity),
            Either
              .catchNonFatal:
                HotStuffBootstrapConfig.load(config, consensusConfigPath)
              .leftMap(_.getMessage)
              .flatMap(identity),
          )
      .flatMap:
        case (Left(peerError), Left(consensusError)) =>
          Resource.pure:
            ss"${peerError}; ${consensusError}"
              .asLeft[HotStuffRuntimeBootstrapWithApplications[F, A]]
        case (Left(error), _) =>
          Resource.pure(
            error.asLeft[HotStuffRuntimeBootstrapWithApplications[F, A]],
          )
        case (_, Left(error)) =>
          Resource.pure(
            error.asLeft[HotStuffRuntimeBootstrapWithApplications[F, A]],
          )
        case (Right(topology), Right(consensusConfig)) =>
          StaticPeerTransportAuthConfig.load(
            config,
            topology,
            peerConfigPath,
          ) match
            case Left(error) =>
              Resource.pure(
                error.asLeft[HotStuffRuntimeBootstrapWithApplications[F, A]],
              )
            case Right(transportAuth) =>
              StaticPeerBootstrapHttpTransportConfig
                .load(config, topology, peerConfigPath) match
                case Left(error) =>
                  Resource.pure(
                    error
                      .asLeft[HotStuffRuntimeBootstrapWithApplications[F, A]],
                  )
                case Right(httpBootstrapConfig) =>
                  val resolvedBootstrapTransport =
                    bootstrapTransport match
                      case Some(transport) =>
                        Resource.pure(transport.some)
                      case None =>
                        httpBootstrapConfig.traverse: config =>
                          HotStuffBootstrapHttpTransport.servicesResource[F](
                            peerBaseUris = config.peerBaseUris,
                            transportAuth = transportAuth,
                            requestTimeout = config.requestTimeout,
                            maxConcurrentRequests =
                              config.maxConcurrentRequests,
                          )
                  resolvedBootstrapTransport.flatMap: transport =>
                    fromTopologyWithApplicationTopics(
                      topology = topology,
                      transportAuth = transportAuth,
                      consensusConfig = consensusConfig,
                      clock = clock,
                      applicationTopics = applicationTopics,
                      runtimePolicy = runtimePolicy,
                      handshakePolicy = handshakePolicy,
                      bootstrapTransport = transport,
                      storageLayout = storageLayout,
                      proposalInputConfig = proposalInputConfig,
                      proposalValidationConfig = proposalValidationConfig,
                      txUniquenessConfig = txUniquenessConfig,
                      pacemakerPolicy = pacemakerPolicy,
                      finalityDrivePolicy = finalityDrivePolicy,
                    )

  /** Bootstraps the full HotStuff runtime from an explicit peer topology and
    * consensus config.
    */
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def fromTopology[F[_]: Async: LiftIO](
      topology: StaticPeerTopology,
      transportAuth: StaticPeerTransportAuth,
      consensusConfig: HotStuffBootstrapConfig,
      clock: GossipClock[F],
      runtimePolicy: TxRuntimePolicy = DefaultRuntimePolicy,
      handshakePolicy: HandshakePolicy = HandshakePolicy.default,
      bootstrapTransport: Option[HotStuffBootstrapTransportServices[F]] = None,
      storageLayout: StorageLayout = StorageLayout.default,
      proposalInputConfig: HotStuffProposalInputRuntimeConfig[F] =
        HotStuffProposalInputRuntimeConfig.legacyCompatible[F],
      proposalValidationConfig: HotStuffProposalValidationRuntimeConfig[F] =
        HotStuffProposalValidationRuntimeConfig.legacyCompatible[F],
      txUniquenessConfig: HotStuffProposalTxUniquenessRuntimeConfig =
        HotStuffProposalTxUniquenessRuntimeConfig.enforceUnfinalizedAncestors,
      pacemakerPolicy: HotStuffPacemakerPolicy =
        HotStuffPacemakerPolicy.default,
      finalityDrivePolicy: HotStuffFinalityDrivePolicy =
        HotStuffFinalityDrivePolicy.disabled,
  ): Resource[F, Either[String, HotStuffRuntimeBootstrap[F]]] =
    fromTopologyWithGossipRuntime[
      F,
      HotStuffGossipArtifact,
      HotStuffRuntimeBootstrap[F],
    ](
      topology = topology,
      consensusConfig = consensusConfig,
      clock = clock,
      bootstrapTransport = bootstrapTransport,
      storageLayout = storageLayout,
      proposalInputConfig = proposalInputConfig,
      proposalValidationConfig = proposalValidationConfig,
      txUniquenessConfig = txUniquenessConfig,
      pacemakerPolicy = pacemakerPolicy,
      finalityDrivePolicy = finalityDrivePolicy,
      buildGossipRuntime = consensus =>
        TxGossipRuntimeBootstrap.fromTopology[F, HotStuffGossipArtifact](
          topology = topology,
          transportAuth = transportAuth,
          clock = clock,
          source = consensus.source,
          sink = consensus.sink,
          topicContracts = consensus.topicContracts,
          runtimePolicy = runtimePolicy,
          handshakePolicy = handshakePolicy,
        ),
      assembleBootstrap = (consensus, gossipBootstrap) =>
        HotStuffRuntimeBootstrap(
          topology = gossipBootstrap.topology,
          registry = gossipBootstrap.registry,
          authenticator = gossipBootstrap.authenticator,
          transportAuth = gossipBootstrap.transportAuth,
          consensus = consensus,
          runtime = gossipBootstrap.runtime,
        ),
    )

  /** Bootstraps HotStuff plus embedder-owned application gossip topics from an
    * explicit peer topology and consensus config.
    */
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def fromTopologyWithApplicationTopics[F[_]: Async: LiftIO, A](
      topology: StaticPeerTopology,
      transportAuth: StaticPeerTransportAuth,
      consensusConfig: HotStuffBootstrapConfig,
      clock: GossipClock[F],
      applicationTopics: Vector[ApplicationGossipTopic[F, A]],
      runtimePolicy: TxRuntimePolicy = DefaultRuntimePolicy,
      handshakePolicy: HandshakePolicy = HandshakePolicy.default,
      bootstrapTransport: Option[HotStuffBootstrapTransportServices[F]] = None,
      storageLayout: StorageLayout = StorageLayout.default,
      proposalInputConfig: HotStuffProposalInputRuntimeConfig[F] =
        HotStuffProposalInputRuntimeConfig.legacyCompatible[F],
      proposalValidationConfig: HotStuffProposalValidationRuntimeConfig[F] =
        HotStuffProposalValidationRuntimeConfig.legacyCompatible[F],
      txUniquenessConfig: HotStuffProposalTxUniquenessRuntimeConfig =
        HotStuffProposalTxUniquenessRuntimeConfig.enforceUnfinalizedAncestors,
      pacemakerPolicy: HotStuffPacemakerPolicy =
        HotStuffPacemakerPolicy.default,
      finalityDrivePolicy: HotStuffFinalityDrivePolicy =
        HotStuffFinalityDrivePolicy.disabled,
  ): Resource[
    F,
    Either[String, HotStuffRuntimeBootstrapWithApplications[F, A]],
  ] =
    fromTopologyWithGossipRuntime[
      F,
      HotStuffPeerArtifact[A],
      HotStuffRuntimeBootstrapWithApplications[F, A],
    ](
      topology = topology,
      consensusConfig = consensusConfig,
      clock = clock,
      bootstrapTransport = bootstrapTransport,
      storageLayout = storageLayout,
      proposalInputConfig = proposalInputConfig,
      proposalValidationConfig = proposalValidationConfig,
      txUniquenessConfig = txUniquenessConfig,
      pacemakerPolicy = pacemakerPolicy,
      finalityDrivePolicy = finalityDrivePolicy,
      buildGossipRuntime = consensus =>
        TxGossipRuntimeBootstrap.fromTopology[F, HotStuffPeerArtifact[A]](
          topology = topology,
          transportAuth = transportAuth,
          clock = clock,
          source = HotStuffPeerArtifact.source(
            consensus.source,
            consensus.topicContracts,
            applicationTopics,
          ),
          sink = HotStuffPeerArtifact.sink(
            consensus.sink,
            consensus.topicContracts,
            applicationTopics,
          ),
          topicContracts = HotStuffPeerArtifact.topicContracts(
            consensus.topicContracts,
            applicationTopics,
          ),
          runtimePolicy = runtimePolicy,
          handshakePolicy = handshakePolicy,
        ),
      assembleBootstrap = (consensus, gossipBootstrap) =>
        HotStuffRuntimeBootstrapWithApplications(
          topology = gossipBootstrap.topology,
          registry = gossipBootstrap.registry,
          authenticator = gossipBootstrap.authenticator,
          transportAuth = gossipBootstrap.transportAuth,
          consensus = consensus,
          runtime = gossipBootstrap.runtime,
          applicationTopics = applicationTopics,
        ),
    )

  private def fromTopologyWithGossipRuntime[F[_]: Async: LiftIO, A, B](
      topology: StaticPeerTopology,
      consensusConfig: HotStuffBootstrapConfig,
      clock: GossipClock[F],
      bootstrapTransport: Option[HotStuffBootstrapTransportServices[F]],
      storageLayout: StorageLayout,
      proposalInputConfig: HotStuffProposalInputRuntimeConfig[F],
      proposalValidationConfig: HotStuffProposalValidationRuntimeConfig[F],
      txUniquenessConfig: HotStuffProposalTxUniquenessRuntimeConfig,
      pacemakerPolicy: HotStuffPacemakerPolicy,
      finalityDrivePolicy: HotStuffFinalityDrivePolicy,
      buildGossipRuntime: HotStuffNodeRuntime[F] => F[TxGossipBootstrap[F, A]],
      assembleBootstrap: (HotStuffNodeRuntime[F], TxGossipBootstrap[F, A]) => B,
  ): Resource[F, Either[String, B]] =
    given GossipClock[F] = clock
    val bootstrapInput =
      HotStuffRuntimeBootstrapInput(
        localPeer = topology.localNodeIdentity,
        role = consensusConfig.role,
        holders = consensusConfig.holders,
        validatorSet = consensusConfig.validatorSet,
        localKeys = consensusConfig.localKeys,
        gossipPolicy = consensusConfig.gossipPolicy,
        bootstrapTrustRootOverride = consensusConfig.bootstrapTrustRoot.some,
      )
    HotStuffNodeRuntime
      .validateBootstrapInput(bootstrapInput)
      .flatMap: validatedInput =>
        // This assembled bootstrap always attaches the automatic pacemaker.
        for
          _ <- HotStuffProposalInputRuntimeConfig
            .validateForAutomaticConsensus(proposalInputConfig)
          _ <- HotStuffProposalValidationRuntimeConfig
            .validateForAutomaticConsensus(proposalValidationConfig)
          _ <- HotStuffProposalTxUniquenessRuntimeConfig
            .validateForAutomaticConsensus(txUniquenessConfig)
        yield validatedInput
      .leftMap(renderPolicyViolation) match
      case Left(rejection) =>
        Resource.pure(rejection.asLeft[B])
      case Right(validatedInput) =>
        Resource
          .eval(clock.now)
          .flatMap: now =>
            ensureBootstrapTrustRootFreshness(
              validatedInput.bootstrapTrustRoot,
              now,
            ) match
              case Left(error) =>
                Resource.pure(error.asLeft[B])
              case Right(_) =>
                Resource
                  .eval:
                    HistoricalProposalArchive
                      .swaydb[F](storageLayout)
                      .attempt
                  .flatMap:
                    case Left(error) =>
                      Resource.pure(
                        renderThrowable(error)
                          .asLeft[B],
                      )
                    case Right(historicalArchive) =>
                      Resource
                        .make(Async[F].pure(historicalArchive))(_.close)
                        .evalMap: archive =>
                          HotStuffNodeRuntime
                            .inMemoryServices[F](
                              validatorSet = validatedInput.validatorSet,
                              gossipPolicy = validatedInput.gossipPolicy,
                              relayPolicy = HotStuffRelayPolicy
                                .forRole(validatedInput.role),
                            )
                            .flatMap: (services, diagnostics) =>
                              for
                                metadataStore <- SnapshotMetadataStore
                                  .inMemory[F]
                                nodeStore    <- SnapshotNodeStore.inMemory[F]
                                forwardStore <- ForwardCatchUpStore.inMemory[F]
                                emptyDiagnostics = BootstrapDiagnosticsSource
                                  .const[F](
                                    BootstrapDiagnostics.empty,
                                  )
                                bootstrapServices =
                                  HotStuffBootstrapServicesRuntime
                                    .fromTrustRootWithNodeStore[F](
                                      trustRoot =
                                        validatedInput.bootstrapTrustRoot,
                                      validatorSetInventory =
                                        consensusConfig.validatorSetLookupInventory,
                                      sink = diagnostics.sink,
                                      snapshotNodeStore = nodeStore.some,
                                      diagnostics = emptyDiagnostics,
                                    )
                                transportServices =
                                  bootstrapTransport.getOrElse(
                                    HotStuffBootstrapTransportServices
                                      .fromBootstrapServices(bootstrapServices),
                                  )
                                proposalCatchUpReadiness =
                                  transportServices.proposalCatchUpReadiness
                                    .getOrElse:
                                      // The application-neutral fallback closes
                                      // proposals whose body commitment is
                                      // derivable from the carried tx-set itself.
                                      // Richer application-owned bodies can still
                                      // override this via `bootstrapTransport`.
                                      ApplicationNeutralProposalView
                                        .readiness[F](
                                          validatedInput.validatorSet,
                                        )
                                bootstrapLifecycle <- HotStuffBootstrapLifecycle
                                  .inMemory[F](
                                    metadataStore = metadataStore,
                                    nodeStore = nodeStore,
                                    validatorSetLookup =
                                      bootstrapServices.validatorSetLookup,
                                    finalizedAnchorSuggestions =
                                      transportServices.finalizedAnchorSuggestions,
                                    snapshotNodeFetch =
                                      transportServices.snapshotNodeFetch,
                                    proposalReplay =
                                      transportServices.proposalReplay,
                                    historicalBackfill =
                                      transportServices.historicalBackfill,
                                    forwardStore = forwardStore,
                                    historicalArchive = archive,
                                    retryPolicy =
                                      BootstrapRetryPolicy.boundedDefault,
                                    historicalBackfillPolicy =
                                      HistoricalBackfillPolicy.forRole(
                                        validatedInput.role,
                                        enabled =
                                          consensusConfig.historicalSyncEnabled,
                                      ),
                                    beforeCoordinatorBuild = None,
                                    readiness = proposalCatchUpReadiness,
                                    currentInstant = clock.now,
                                  )
                                assembledServices =
                                  services.copy(
                                    bootstrap = bootstrapServices
                                      .copy(diagnostics = bootstrapLifecycle),
                                  )
                                consensus <- InMemoryHotStuffPacemakerDriver
                                  .attach(
                                    HotStuffNodeRuntime
                                      .fromValidatedServices[F](
                                        bootstrapInput = validatedInput,
                                        services = assembledServices,
                                        diagnostics = Some(diagnostics),
                                        bootstrapLifecycle =
                                          bootstrapLifecycle.some,
                                        proposalValidationConfig =
                                          proposalValidationConfig,
                                        txUniquenessConfig = txUniquenessConfig,
                                        pacemakerPolicy = pacemakerPolicy,
                                        finalityDrivePolicy =
                                          finalityDrivePolicy,
                                      ),
                                    automaticConsensus = true,
                                    proposalInputConfig = proposalInputConfig,
                                    txUniquenessConfig = txUniquenessConfig,
                                    finalityDrivePolicy = finalityDrivePolicy,
                                  )
                                gossipBootstrap <- buildGossipRuntime(consensus)
                              yield assembleBootstrap(
                                consensus,
                                gossipBootstrap,
                              ).asRight[String]

  private def renderPolicyViolation(
      rejection: HotStuffPolicyViolation,
  ): String =
    rejection.detail.fold(rejection.reason)(detail =>
      ss"${rejection.reason}: ${detail}",
    )

  private def renderThrowable(
      error: Throwable,
  ): String =
    Option(error.getMessage)
      .filter(_.nonEmpty)
      .getOrElse(error.getClass.getName)

  private def ensureBootstrapTrustRootFreshness(
      trustRoot: BootstrapTrustRoot,
      now: Instant,
  ): Either[String, Unit] =
    trustRoot match
      case BootstrapTrustRoot.WeakSubjectivityAnchor(window, _, freshUntil)
          if now.isAfter(freshUntil) =>
        val freshness = freshUntil.toString
        (
          "weakSubjectivityAnchorExpired: " +
            window.chainId.value +
            "@" +
            window.height.render +
            "/" +
            window.view.render +
            ":" +
            freshness
        ).asLeft[Unit]
      case _ =>
        ().asRight[String]
