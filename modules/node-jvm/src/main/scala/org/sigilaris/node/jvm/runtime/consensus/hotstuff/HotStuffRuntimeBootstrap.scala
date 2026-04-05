package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Duration
import java.util.Locale

import scala.jdk.CollectionConverters.*

import cats.effect.kernel.Sync
import cats.syntax.all.*
import scodec.bits.ByteVector

import com.typesafe.config.Config

import org.sigilaris.core.crypto.{CryptoOps, KeyPair, PublicKey}
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.gossip.*
import org.sigilaris.node.jvm.runtime.gossip.tx.{
  TxGossipRuntime,
  TxGossipRuntimeBootstrap,
  TxRuntimePolicy,
}

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class HotStuffBootstrapConfig(
    role: LocalNodeRole,
    validatorSet: ValidatorSet,
    holders: Vector[ValidatorKeyHolder],
    localKeys: Map[ValidatorId, KeyPair],
    gossipPolicy: HotStuffGossipPolicy = HotStuffGossipPolicy.default,
):
  def bootstrapTrustRoot: BootstrapTrustRoot =
    BootstrapTrustRoot.staticValidatorSet(validatorSet)

object HotStuffBootstrapConfig:
  val DefaultPath: String = "sigilaris.node.consensus.hotstuff"

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def load(
      config: Config,
      path: String = DefaultPath,
  ): Either[String, HotStuffBootstrapConfig] =
    Either
      .cond(
        config.hasPath(path),
        config.getConfig(path),
        ss"missing config path: ${path}",
      )
      .flatMap(loadSection)

  def loadSection(
      section: Config,
  ): Either[String, HotStuffBootstrapConfig] =
    for
      role <- requiredString(section, "local-role", "localRole")
        .flatMap(parseRole)
      validators <- requiredConfigList(section, "validators", "validators")
        .flatMap(parseValidators)
      validatorSet <- ValidatorSet(validators).leftMap(_.message)
      holders <- requiredConfigList(section, "key-holders", "keyHolders")
        .flatMap(parseHolders)
      localKeys <- requiredConfigList(section, "local-signers", "localSigners")
        .flatMap(parseLocalKeys(validatorSet))
      gossipPolicy <- loadGossipPolicy(section)
      _            <- validateHolders(holders, validatorSet)
    yield HotStuffBootstrapConfig(
      role = role,
      validatorSet = validatorSet,
      holders = holders,
      localKeys = localKeys,
      gossipPolicy = gossipPolicy,
    )

  private def parseValidators(
      sections: List[Config],
  ): Either[String, Vector[ValidatorMember]] =
    sections.traverse(parseValidator).map(_.toVector)

  private def parseHolders(
      sections: List[Config],
  ): Either[String, Vector[ValidatorKeyHolder]] =
    sections.traverse(parseHolder).map(_.toVector)

  private def parseLocalKeys(
      validatorSet: ValidatorSet,
  )(
      sections: List[Config],
  ): Either[String, Map[ValidatorId, KeyPair]] =
    sections.foldM(Map.empty[ValidatorId, KeyPair]): (acc, signer) =>
      for
        validatorId <- requiredString(signer, "validator-id", "validatorId")
          .flatMap(ValidatorId.parse)
        privateKeyHex <- requiredString(signer, "private-key", "privateKey")
        privateKey    <- UInt256.fromHex(privateKeyHex).leftMap(_.toString)
        keyPair = CryptoOps.fromPrivate(privateKey.toBigIntUnsigned)
        validator <- validatorSet
          .member(validatorId)
          .toRight(ss"unknown validator signer: ${validatorId.value}")
        _ <- Either.cond(
          validator.publicKey === keyPair.publicKey,
          (),
          ss"local signer public key mismatch: ${validatorId.value}",
        )
        _ <- Either.cond(
          !acc.contains(validatorId),
          (),
          ss"duplicate local signer entry: ${validatorId.value}",
        )
      yield acc.updated(validatorId, keyPair)

  private def parseValidator(
      section: Config,
  ): Either[String, ValidatorMember] =
    for
      id <- requiredString(section, "id", "id").flatMap(ValidatorId.parse)
      publicKeyHex <- requiredString(section, "public-key", "publicKey")
      publicKey    <- parsePublicKey(publicKeyHex)
    yield ValidatorMember(id = id, publicKey = publicKey)

  private def parseHolder(
      section: Config,
  ): Either[String, ValidatorKeyHolder] =
    for
      validatorId <- requiredString(section, "validator-id", "validatorId")
        .flatMap(ValidatorId.parse)
      peer <- requiredString(section, "holder", "holder")
        .flatMap(PeerIdentity.parse)
      statusValue <- requiredString(section, "status", "status")
      status      <- parseHolderStatus(statusValue)
    yield ValidatorKeyHolder(
      validatorId = validatorId,
      holder = peer,
      status = status,
    )

  private def validateHolders(
      holders: Vector[ValidatorKeyHolder],
      validatorSet: ValidatorSet,
  ): Either[String, Unit] =
    holders
      .find(holder => !validatorSet.contains(holder.validatorId))
      .toLeft(())
      .leftMap: holder =>
        ss"unknown key holder validator: ${holder.validatorId.value}"

  private def parseRole(
      value: String,
  ): Either[String, LocalNodeRole] =
    value.trim.toLowerCase(Locale.ROOT) match
      case "validator" => LocalNodeRole.Validator.asRight[String]
      case "audit"     => LocalNodeRole.Audit.asRight[String]
      case other => ss"unsupported local role: ${other}".asLeft[LocalNodeRole]

  private def parseHolderStatus(
      value: String,
  ): Either[String, ValidatorKeyHolderStatus] =
    value.trim.toLowerCase(Locale.ROOT) match
      case "active" => ValidatorKeyHolderStatus.Active.asRight[String]
      case "fenced" => ValidatorKeyHolderStatus.Fenced.asRight[String]
      case other =>
        ss"unsupported key holder status: ${other}"
          .asLeft[ValidatorKeyHolderStatus]

  private def parsePublicKey(
      value: String,
  ): Either[String, PublicKey] =
    for
      bytes     <- ByteVector.fromHexDescriptive(value).leftMap(identity)
      publicKey <- PublicKey.fromByteArray(bytes.toArray).leftMap(_.toString)
    yield publicKey

  private def loadGossipPolicy(
      section: Config,
  ): Either[String, HotStuffGossipPolicy] =
    optionalConfig(section, "gossip-policy", "gossipPolicy") match
      case Left(error) =>
        error.asLeft[HotStuffGossipPolicy]
      case Right(None) =>
        HotStuffGossipPolicy.default.asRight[String]
      case Right(Some(policySection)) =>
        for
          proposal <- parseTopicPolicy(
            optionalConfig(policySection, "proposal", "proposal"),
            HotStuffGossipPolicy.default.proposal,
          )
          vote <- parseTopicPolicy(
            optionalConfig(policySection, "vote", "vote"),
            HotStuffGossipPolicy.default.vote,
          )
        yield HotStuffGossipPolicy(
          proposal = proposal,
          vote = vote,
        )

  private def parseTopicPolicy(
      section: Either[String, Option[Config]],
      default: HotStuffTopicPolicy,
  ): Either[String, HotStuffTopicPolicy] =
    section match
      case Left(error) =>
        error.asLeft[HotStuffTopicPolicy]
      case Right(None) =>
        default.asRight[String]
      case Right(Some(config)) =>
        for
          exactKnownSetLimit <- optionalInt(
            config,
            "exact-known-set-limit",
            "exactKnownSetLimit",
            default.exactKnownSetLimit,
          )
          requestByIdLimit <- optionalInt(
            config,
            "request-by-id-limit",
            "requestByIdLimit",
            default.requestByIdLimit,
          )
          maxBatchItems <- optionalInt(
            config,
            "max-batch-items",
            "maxBatchItems",
            default.maxBatchItems,
          )
          flushIntervalMs <- optionalLong(
            config,
            "flush-interval-ms",
            "flushIntervalMs",
            default.flushInterval.toMillis,
          )
          deliveryPriority <- optionalInt(
            config,
            "delivery-priority",
            "deliveryPriority",
            default.deliveryPriority,
          )
        yield HotStuffTopicPolicy(
          exactKnownSetLimit = exactKnownSetLimit,
          requestByIdLimit = requestByIdLimit,
          maxBatchItems = maxBatchItems,
          flushInterval = Duration.ofMillis(flushIntervalMs),
          deliveryPriority = deliveryPriority,
        )

  private def requiredString(
      config: Config,
      primary: String,
      alternate: String,
  ): Either[String, String] =
    findPath(config, primary, alternate)
      .toRight(ss"missing required config key: ${primary}")
      .flatMap: path =>
        Either.catchNonFatal(config.getString(path)).leftMap(_.getMessage)

  private def requiredConfigList(
      config: Config,
      primary: String,
      alternate: String,
  ): Either[String, List[Config]] =
    findPath(config, primary, alternate)
      .toRight(ss"missing required config key: ${primary}")
      .flatMap: path =>
        Either
          .catchNonFatal(config.getConfigList(path).asScala.toList)
          .leftMap(_.getMessage)

  private def optionalInt(
      config: Config,
      primary: String,
      alternate: String,
      default: Int,
  ): Either[String, Int] =
    findPath(config, primary, alternate) match
      case None =>
        default.asRight[String]
      case Some(path) =>
        Either.catchNonFatal(config.getInt(path)).leftMap(_.getMessage)

  private def optionalLong(
      config: Config,
      primary: String,
      alternate: String,
      default: Long,
  ): Either[String, Long] =
    findPath(config, primary, alternate) match
      case None =>
        default.asRight[String]
      case Some(path) =>
        Either.catchNonFatal(config.getLong(path)).leftMap(_.getMessage)

  private def optionalConfig(
      config: Config,
      primary: String,
      alternate: String,
  ): Either[String, Option[Config]] =
    findPath(config, primary, alternate) match
      case None =>
        Option.empty[Config].asRight[String]
      case Some(path) =>
        Either
          .catchNonFatal(config.getConfig(path))
          .leftMap(_.getMessage)
          .map(Some(_))

  private def findPath(
      config: Config,
      primary: String,
      alternate: String,
  ): Option[String] =
    List(primary, alternate).find(config.hasPath)

final case class HotStuffRuntimeBootstrap[F[_]](
    topology: StaticPeerTopology,
    registry: StaticPeerRegistry,
    authenticator: StaticPeerAuthenticator[F],
    consensus: HotStuffNodeRuntime[F],
    runtime: TxGossipRuntime[F, HotStuffGossipArtifact],
)

object HotStuffRuntimeBootstrap:
  val DefaultRuntimePolicy: TxRuntimePolicy =
    TxRuntimePolicy(
      maxExactRequestRetriesPerScope =
        Some(HotStuffPolicy.requestPolicy.maxRetryAttemptsPerWindow),
    )

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def fromConfig[F[_]: Sync](
      config: Config,
      clock: GossipClock[F],
      runtimePolicy: TxRuntimePolicy = DefaultRuntimePolicy,
      handshakePolicy: HandshakePolicy = HandshakePolicy.default,
      bootstrapTransport: Option[HotStuffBootstrapTransportServices[F]] = None,
      peerConfigPath: String = StaticPeerTopologyConfig.DefaultPath,
      consensusConfigPath: String = HotStuffBootstrapConfig.DefaultPath,
  ): F[Either[String, HotStuffRuntimeBootstrap[F]]] =
    Sync[F]
      .delay:
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
          Sync[F].pure:
            ss"${peerError}; ${consensusError}"
              .asLeft[HotStuffRuntimeBootstrap[F]]
        case (Left(error), _) =>
          Sync[F].pure(error.asLeft[HotStuffRuntimeBootstrap[F]])
        case (_, Left(error)) =>
          Sync[F].pure(error.asLeft[HotStuffRuntimeBootstrap[F]])
        case (Right(topology), Right(consensusConfig)) =>
          fromTopology(
            topology = topology,
            consensusConfig = consensusConfig,
            clock = clock,
            runtimePolicy = runtimePolicy,
            handshakePolicy = handshakePolicy,
            bootstrapTransport = bootstrapTransport,
          )

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def fromTopology[F[_]: Sync](
      topology: StaticPeerTopology,
      consensusConfig: HotStuffBootstrapConfig,
      clock: GossipClock[F],
      runtimePolicy: TxRuntimePolicy = DefaultRuntimePolicy,
      handshakePolicy: HandshakePolicy = HandshakePolicy.default,
      bootstrapTransport: Option[HotStuffBootstrapTransportServices[F]] = None,
  ): F[Either[String, HotStuffRuntimeBootstrap[F]]] =
    given GossipClock[F] = clock
    val bootstrapInput =
      HotStuffRuntimeBootstrapInput(
        localPeer = topology.localNodeIdentity,
        role = consensusConfig.role,
        holders = consensusConfig.holders,
        validatorSet = consensusConfig.validatorSet,
        localKeys = consensusConfig.localKeys,
        gossipPolicy = consensusConfig.gossipPolicy,
      )
    HotStuffNodeRuntime
      .validateBootstrapInput(bootstrapInput)
      .leftMap(renderPolicyViolation) match
      case Left(rejection) =>
        Sync[F].pure(rejection.asLeft[HotStuffRuntimeBootstrap[F]])
      case Right(validatedInput) =>
        HotStuffNodeRuntime
          .inMemoryServices[F](
            validatorSet = validatedInput.validatorSet,
            gossipPolicy = validatedInput.gossipPolicy,
            relayPolicy = HotStuffRelayPolicy.forRole(validatedInput.role),
          )
          .flatMap: (services, diagnostics) =>
            for
              metadataStore <- SnapshotMetadataStore.inMemory[F]
              nodeStore     <- SnapshotNodeStore.inMemory[F]
              forwardStore  <- ForwardCatchUpStore.inMemory[F]
              historicalArchive <- HistoricalProposalArchive.inMemory[F]
              emptyDiagnostics = BootstrapDiagnosticsSource.const[F](
                BootstrapDiagnostics.empty,
              )
              bootstrapServices =
                HotStuffBootstrapServicesRuntime.inMemoryWithNodeStore[F](
                  validatorSet = validatedInput.validatorSet,
                  sink = diagnostics.sink,
                  snapshotNodeStore = nodeStore.some,
                  diagnostics = emptyDiagnostics,
                )
              transportServices =
                bootstrapTransport.getOrElse(
                  HotStuffBootstrapTransportServices
                    .fromBootstrapServices(bootstrapServices),
                )
              bootstrapLifecycle <- HotStuffBootstrapLifecycle.inMemory[F](
                metadataStore = metadataStore,
                nodeStore = nodeStore,
                validatorSetLookup = bootstrapServices.validatorSetLookup,
                finalizedAnchorSuggestions =
                  transportServices.finalizedAnchorSuggestions,
                snapshotNodeFetch = transportServices.snapshotNodeFetch,
                proposalReplay = transportServices.proposalReplay,
                historicalBackfill = transportServices.historicalBackfill,
                forwardStore = forwardStore,
                historicalArchive = historicalArchive,
                retryPolicy = BootstrapRetryPolicy.boundedDefault,
                historicalBackfillPolicy =
                  HistoricalBackfillPolicy.backgroundDefault,
                beforeCoordinatorBuild = None,
                // Phase 6 wires the lifecycle gate but does not yet connect
                // concrete replay/view validation into the shipped newcomer
                // runtime path. Nodes with no catch-up proposals can become
                // ready, but any actual replayed/live proposal keeps voting
                // held until a later phase installs the real readiness
                // pipeline.
                readiness = new ProposalCatchUpReadiness[F]:
                  override def assess(
                      proposal: Proposal,
                  ): F[Either[BootstrapCoordinatorFailure, ProposalCatchUpAssessment]] =
                    ProposalCatchUpAssessment(
                      voteReadiness =
                        BootstrapVoteReadiness.Held(
                          "forwardCatchUpUnavailable",
                        ),
                      controlBatch = None,
                    ).asRight[BootstrapCoordinatorFailure].pure[F],
                currentInstant = clock.now,
              )
              assembledServices =
                services.copy(
                  bootstrap =
                    bootstrapServices.copy(diagnostics = bootstrapLifecycle),
                )
              consensus =
                HotStuffNodeRuntime.fromValidatedServices[F](
                  bootstrapInput = validatedInput,
                  services = assembledServices,
                  diagnostics = Some(diagnostics),
                  bootstrapLifecycle = bootstrapLifecycle.some,
                )
              gossipBootstrap <- TxGossipRuntimeBootstrap
                .fromTopology[F, HotStuffGossipArtifact](
                  topology = topology,
                  clock = clock,
                  source = consensus.source,
                  sink = consensus.sink,
                  topicContracts = consensus.topicContracts,
                  runtimePolicy = runtimePolicy,
                  handshakePolicy = handshakePolicy,
                )
            yield HotStuffRuntimeBootstrap(
              topology = gossipBootstrap.topology,
              registry = gossipBootstrap.registry,
              authenticator = gossipBootstrap.authenticator,
              consensus = consensus,
              runtime = gossipBootstrap.runtime,
            ).asRight[String]

  private def renderPolicyViolation(
      rejection: HotStuffPolicyViolation,
  ): String =
    rejection.detail.fold(rejection.reason)(detail =>
      ss"${rejection.reason}: ${detail}",
    )
