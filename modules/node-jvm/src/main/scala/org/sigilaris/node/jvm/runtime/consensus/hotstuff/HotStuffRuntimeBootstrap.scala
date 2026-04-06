package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.{Duration, Instant}
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
    bootstrapTrustRootOverride: Option[BootstrapTrustRoot] = None,
    historicalValidatorSets: Vector[ValidatorSet] = Vector.empty,
    historicalSyncEnabled: Boolean = true,
):
  def bootstrapTrustRoot: BootstrapTrustRoot =
    bootstrapTrustRootOverride.getOrElse:
      BootstrapTrustRoot.staticValidatorSet(validatorSet)

  def validatorSetLookupInventory: Vector[ValidatorSet] =
    (Vector(bootstrapTrustRoot.validatorSet, validatorSet) ++ historicalValidatorSets)
      .foldLeft((Set.empty[ValidatorSetHash], Vector.empty[ValidatorSet])):
        case ((seen, acc), next) if seen.contains(next.hash) =>
          seen -> acc
        case ((seen, acc), next) =>
          (seen + next.hash) -> (acc :+ next)
      ._2

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
      historicalSyncEnabled <- optionalBoolean(
        section,
        "historical-sync-enabled",
        "historicalSyncEnabled",
        default = true,
      )
      historicalValidatorSets <- loadHistoricalValidatorSets(section)
      bootstrapTrustRootOverride <- loadBootstrapTrustRoot(
        section,
        validatorSet,
      )
      _            <- validateHolders(holders, validatorSet)
    yield HotStuffBootstrapConfig(
      role = role,
      validatorSet = validatorSet,
      holders = holders,
      localKeys = localKeys,
      gossipPolicy = gossipPolicy,
      bootstrapTrustRootOverride = bootstrapTrustRootOverride,
      historicalValidatorSets = historicalValidatorSets,
      historicalSyncEnabled = historicalSyncEnabled,
    )

  private def parseValidators(
      sections: List[Config],
  ): Either[String, Vector[ValidatorMember]] =
    sections.traverse(parseValidator).map(_.toVector)

  private def parseValidatorSet(
      sections: List[Config],
  ): Either[String, ValidatorSet] =
    parseValidators(sections).flatMap(ValidatorSet(_).leftMap(_.message))

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

  private def loadBootstrapTrustRoot(
      section: Config,
      currentValidatorSet: ValidatorSet,
  ): Either[String, Option[BootstrapTrustRoot]] =
    optionalConfig(section, "bootstrap-trust-root", "bootstrapTrustRoot")
      .flatMap:
        case None =>
          none[BootstrapTrustRoot].asRight[String]
        case Some(rootSection) =>
          requiredString(rootSection, "kind", "kind")
            .map(_.trim.toLowerCase(Locale.ROOT))
            .flatMap:
              case "genesis-config" | "genesisconfig" | "static-validator-set" | "staticvalidatorset" =>
                for
                  rootValidatorSet <- loadBootstrapTrustRootValidatorSet(
                    rootSection,
                    currentValidatorSet,
                    allowDefaultToCurrent = true,
                  )
                  _ <- validateExplicitValidatorSetHash(
                    rootSection,
                    rootValidatorSet,
                  )
                yield BootstrapTrustRoot.staticValidatorSet(rootValidatorSet).some
              case "trusted-checkpoint" | "trustedcheckpoint" =>
                for
                  rootValidatorSet <- loadBootstrapTrustRootValidatorSet(
                    rootSection,
                    currentValidatorSet,
                    allowDefaultToCurrent = false,
                  )
                  _ <- validateExplicitValidatorSetHash(
                    rootSection,
                    rootValidatorSet,
                  )
                  window <- parseBootstrapTrustRootWindow(
                    rootSection,
                    rootValidatorSet,
                  )
                  trustRoot <- BootstrapTrustRoot
                    .trustedCheckpoint(window, rootValidatorSet)
                    .leftMap(identity)
                yield trustRoot.some
              case "weak-subjectivity-anchor" | "weaksubjectivityanchor" | "weak-subjectivity" | "weaksubjectivity" =>
                for
                  rootValidatorSet <- loadBootstrapTrustRootValidatorSet(
                    rootSection,
                    currentValidatorSet,
                    allowDefaultToCurrent = false,
                  )
                  _ <- validateExplicitValidatorSetHash(
                    rootSection,
                    rootValidatorSet,
                  )
                  window <- parseBootstrapTrustRootWindow(
                    rootSection,
                    rootValidatorSet,
                  )
                  freshUntil <- requiredString(
                    rootSection,
                    "fresh-until",
                    "freshUntil",
                  ).flatMap(parseInstant)
                  trustRoot <- BootstrapTrustRoot
                    .weakSubjectivityAnchor(
                      window,
                      rootValidatorSet,
                      freshUntil,
                    )
                    .leftMap(identity)
                yield trustRoot.some
              case other =>
                ss"unsupported bootstrap trust root kind: ${other}"
                  .asLeft[Option[BootstrapTrustRoot]]

  private def loadBootstrapTrustRootValidatorSet(
      rootSection: Config,
      currentValidatorSet: ValidatorSet,
      allowDefaultToCurrent: Boolean,
  ): Either[String, ValidatorSet] =
    optionalConfigList(rootSection, "validator-set", "validatorSet")
      .flatMap:
        case Some(sections) =>
          parseValidatorSet(sections)
        case None =>
          Either.cond(
            allowDefaultToCurrent,
            currentValidatorSet,
            ss"missing required config key: validator-set",
          )

  private def parseBootstrapTrustRootWindow(
      rootSection: Config,
      validatorSet: ValidatorSet,
  ): Either[String, HotStuffWindow] =
    for
      chainId <- requiredString(rootSection, "chain-id", "chainId")
        .flatMap(ChainId.parse)
      heightValue <- requiredLong(rootSection, "height", "height")
      height      <- HotStuffHeight.fromLong(heightValue)
      viewValue   <- requiredLong(rootSection, "view", "view")
      view        <- HotStuffView.fromLong(viewValue)
    yield HotStuffWindow(
      chainId = chainId,
      height = height,
      view = view,
      validatorSetHash = validatorSet.hash,
    )

  private def loadHistoricalValidatorSets(
      section: Config,
  ): Either[String, Vector[ValidatorSet]] =
    optionalConfigList(
      section,
      "historical-validator-sets",
      "historicalValidatorSets",
    ).flatMap:
      case None =>
        Vector.empty[ValidatorSet].asRight[String]
      case Some(sections) =>
        sections
          .traverse(parseHistoricalValidatorSet)
          .map(_.toVector)
          .flatTap(validateDistinctValidatorSetHashes)

  private def parseHistoricalValidatorSet(
      section: Config,
  ): Either[String, ValidatorSet] =
    for
      validatorSet <- requiredConfigList(section, "validators", "validators")
        .flatMap(parseValidatorSet)
      _ <- validateExplicitValidatorSetHash(section, validatorSet)
    yield validatorSet

  private def validateDistinctValidatorSetHashes(
      validatorSets: Vector[ValidatorSet],
  ): Either[String, Unit] =
    validatorSets
      .groupBy(_.hash)
      .collectFirst:
        case (hash, duplicates) if duplicates.sizeCompare(1) > 0 =>
          hash
      .toLeft(())
      .leftMap(hash =>
        ss"duplicate historical validator-set hash: ${hash.toHexLower}",
      )

  private def validateExplicitValidatorSetHash(
      section: Config,
      validatorSet: ValidatorSet,
  ): Either[String, Unit] =
    optionalString(section, "validator-set-hash", "validatorSetHash")
      .flatMap:
        case None =>
          ().asRight[String]
        case Some(value) =>
          ValidatorSetHash
            .fromHex(value)
            .leftMap(identity)
            .flatMap: expected =>
              Either.cond(
                expected === validatorSet.hash,
                (),
                ss"validator-set-hash mismatch: expected ${expected.toHexLower}, derived ${validatorSet.hash.toHexLower}",
              )

  private def parseInstant(
      value: String,
  ): Either[String, Instant] =
    Either
      .catchNonFatal(Instant.parse(value))
      .leftMap(_ => "invalid ISO-8601 instant: " + value)

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
          timeoutVote <- parseTopicPolicy(
            optionalConfig(policySection, "timeout-vote", "timeoutVote"),
            HotStuffGossipPolicy.default.timeoutVote,
          )
          newView <- parseTopicPolicy(
            optionalConfig(policySection, "new-view", "newView"),
            HotStuffGossipPolicy.default.newView,
          )
        yield HotStuffGossipPolicy(
          proposal = proposal,
          vote = vote,
          timeoutVote = timeoutVote,
          newView = newView,
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

  private def requiredLong(
      config: Config,
      primary: String,
      alternate: String,
  ): Either[String, Long] =
    findPath(config, primary, alternate)
      .toRight(ss"missing required config key: ${primary}")
      .flatMap: path =>
        Either.catchNonFatal(config.getLong(path)).leftMap(_.getMessage)

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

  private def optionalBoolean(
      config: Config,
      primary: String,
      alternate: String,
      default: Boolean,
  ): Either[String, Boolean] =
    findPath(config, primary, alternate) match
      case None =>
        default.asRight[String]
      case Some(path) =>
        Either.catchNonFatal(config.getBoolean(path)).leftMap(_.getMessage)

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

  private def optionalString(
      config: Config,
      primary: String,
      alternate: String,
  ): Either[String, Option[String]] =
    findPath(config, primary, alternate) match
      case None =>
        Option.empty[String].asRight[String]
      case Some(path) =>
        Either
          .catchNonFatal(config.getString(path))
          .leftMap(_.getMessage)
          .map(Some(_))

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

  private def optionalConfigList(
      config: Config,
      primary: String,
      alternate: String,
  ): Either[String, Option[List[Config]]] =
    findPath(config, primary, alternate) match
      case None =>
        Option.empty[List[Config]].asRight[String]
      case Some(path) =>
        Either
          .catchNonFatal(config.getConfigList(path).asScala.toList)
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
        bootstrapTrustRootOverride = consensusConfig.bootstrapTrustRoot.some,
      )
    HotStuffNodeRuntime
      .validateBootstrapInput(bootstrapInput)
      .leftMap(renderPolicyViolation) match
      case Left(rejection) =>
        Sync[F].pure(rejection.asLeft[HotStuffRuntimeBootstrap[F]])
      case Right(validatedInput) =>
        clock.now.flatMap: now =>
          ensureBootstrapTrustRootFreshness(validatedInput.bootstrapTrustRoot, now) match
            case Left(error) =>
              Sync[F].pure(error.asLeft[HotStuffRuntimeBootstrap[F]])
            case Right(_) =>
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
                      HotStuffBootstrapServicesRuntime
                        .fromTrustRootWithNodeStore[F](
                          trustRoot = validatedInput.bootstrapTrustRoot,
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
                        HistoricalBackfillPolicy.forRole(
                          validatedInput.role,
                          enabled = consensusConfig.historicalSyncEnabled,
                        ),
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
