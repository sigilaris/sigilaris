package org.sigilaris.node.jvm.runtime.gossip

import java.time.Duration

import cats.syntax.all.*

import com.typesafe.config.Config

import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.gossip.{PeerIdentity, StaticPeerTopology}
import org.sigilaris.node.jvm.runtime.config.TypesafeConfigParsing
import org.sigilaris.node.jvm.runtime.config.TypesafeConfigParsing.{
  ConfigAliases,
  ConfigField,
  ConfigReader,
}

/** Configuration for HTTP-based bootstrap transport to static peers.
  *
  * @param peerBaseUris
  *   mapping of peer identities to their HTTP base URIs
  * @param requestTimeout
  *   timeout for individual HTTP requests
  * @param maxConcurrentRequests
  *   maximum number of concurrent outbound requests
  */
final case class StaticPeerBootstrapHttpTransportConfig(
    peerBaseUris: Map[PeerIdentity, String],
    requestTimeout: Duration,
    maxConcurrentRequests: Int,
)

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
/** Companion for `StaticPeerBootstrapHttpTransportConfig` providing config
  * loading.
  */
object StaticPeerBootstrapHttpTransportConfig:

  /** Default config path for peer gossip settings. */
  val DefaultPath: String = StaticPeerTopologyConfig.DefaultPath

  /** Default HTTP request timeout. */
  val DefaultRequestTimeout: Duration = Duration.ofSeconds(10L)

  /** Default maximum concurrent HTTP requests. */
  val DefaultMaxConcurrentRequests: Int = 16

  /** Loads bootstrap HTTP transport config from the given Typesafe Config.
    *
    * @param config
    *   the root config
    * @param topology
    *   the peer topology for validation
    * @param path
    *   the config path to read from
    * @return
    *   the config, None if no bootstrap section, or an error
    */
  def load(
      config: Config,
      topology: StaticPeerTopology,
      path: String = DefaultPath,
  ): Either[String, Option[StaticPeerBootstrapHttpTransportConfig]] =
    if config.hasPath(path) then
      TypesafeConfigParsing.requiredSection(config, path)
        .flatMap(loadSection(_, topology))
    else none[StaticPeerBootstrapHttpTransportConfig].asRight[String]

  /** Loads bootstrap config from a pre-resolved config section.
    *
    * @param section
    *   the config section at the gossip path
    * @param topology
    *   the peer topology for validation
    * @return
    *   the config, None if no bootstrap section, or an error
    */
  def loadSection(
      section: Config,
      topology: StaticPeerTopology,
  ): Either[String, Option[StaticPeerBootstrapHttpTransportConfig]] =
    parseSection(section).flatMap:
      case None =>
        none[StaticPeerBootstrapHttpTransportConfig].asRight[String]
      case Some(input) =>
        parsePeerBaseUris(input.peerBaseUris, topology).map: peerBaseUris =>
          StaticPeerBootstrapHttpTransportConfig(
            peerBaseUris = peerBaseUris,
            requestTimeout = input.requestTimeout,
            maxConcurrentRequests = input.maxConcurrentRequests,
          )
            .some

  /** Parses the raw bootstrap transport input model from the root config. */
  def parse(
      config: Config,
      path: String = DefaultPath,
  ): Either[String, Option[StaticPeerBootstrapHttpTransportConfigInput]] =
    if config.hasPath(path) then
      TypesafeConfigParsing.requiredSection(config, path).flatMap(parseSection)
    else none[StaticPeerBootstrapHttpTransportConfigInput].asRight[String]

  /** Parses the raw bootstrap transport input model from a pre-resolved section. */
  def parseSection(
      section: Config,
  ): Either[String, Option[StaticPeerBootstrapHttpTransportConfigInput]] =
    Bootstrap.optional(section).flatMap:
      case None =>
        none[StaticPeerBootstrapHttpTransportConfigInput].asRight[String]
      case Some(bootstrapSection) =>
        for
          peerBaseUris <- PeerBaseUris.required(bootstrapSection)
          requestTimeout <- RequestTimeout.optionalOrDefault(
            bootstrapSection,
            DefaultRequestTimeout,
          )
          maxConcurrentRequests <- MaxConcurrentRequests.optionalOrDefault(
            bootstrapSection,
            DefaultMaxConcurrentRequests,
          )
          _ <- Either.cond(
            maxConcurrentRequests > 0,
            (),
            "maxConcurrentRequests must be positive",
          )
          _ <- Either.cond(
            requestTimeout.compareTo(Duration.ZERO) > 0,
            (),
            "requestTimeoutMs must be positive",
          )
        yield StaticPeerBootstrapHttpTransportConfigInput(
          peerBaseUris = peerBaseUris,
          requestTimeout = requestTimeout,
          maxConcurrentRequests = maxConcurrentRequests,
        ).some

  private def parsePeerBaseUris(
      raw: Map[String, String],
      topology: StaticPeerTopology,
  ): Either[String, Map[PeerIdentity, String]] =
    raw.toVector
      .sortBy(_._1)
      .traverse: (peerRaw, uri) =>
        PeerIdentity
          .parse(peerRaw)
          .flatMap: peer =>
            Either.cond(
              topology.knownPeers.contains(peer),
              peer -> uri,
              ss"bootstrap peer base URI configured for unknown peer: ${peer.value}",
            )
      .map(_.toMap)

  private val Bootstrap =
    ConfigField(
      aliases = ConfigAliases("bootstrap"),
      reader = ConfigReader.configSection,
    )

  private val PeerBaseUris =
    ConfigField(
      aliases = ConfigAliases("peer-base-uris", "peerBaseUris"),
      reader = ConfigReader.stringMap,
    )

  private val RequestTimeout =
    ConfigField(
      aliases = ConfigAliases("request-timeout-ms", "requestTimeoutMs"),
      reader = ConfigReader.durationMillis,
    )

  private val MaxConcurrentRequests =
    ConfigField(
      aliases = ConfigAliases(
        "max-concurrent-requests",
        "maxConcurrentRequests",
      ),
      reader = ConfigReader.int,
    )
