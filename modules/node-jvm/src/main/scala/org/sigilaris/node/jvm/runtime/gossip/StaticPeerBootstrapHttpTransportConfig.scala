package org.sigilaris.node.jvm.runtime.gossip

import java.time.Duration

import scala.jdk.CollectionConverters.*

import cats.syntax.all.*

import com.typesafe.config.Config

import org.sigilaris.core.util.SafeStringInterp.*

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
    if config.hasPath(path) then loadSection(config.getConfig(path), topology)
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
    optionalConfig(section, "bootstrap", "bootstrap")
      .flatMap:
        case None =>
          none[StaticPeerBootstrapHttpTransportConfig].asRight[String]
        case Some(bootstrapSection) =>
          for
            peerBaseUris <- requiredStringMap(
              bootstrapSection,
              "peer-base-uris",
              "peerBaseUris",
            ).flatMap(parsePeerBaseUris(_, topology))
            requestTimeout <- optionalDurationMillis(
              bootstrapSection,
              "request-timeout-ms",
              "requestTimeoutMs",
              default = DefaultRequestTimeout,
            )
            maxConcurrentRequests <- optionalInt(
              bootstrapSection,
              "max-concurrent-requests",
              "maxConcurrentRequests",
              default = DefaultMaxConcurrentRequests,
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
          yield StaticPeerBootstrapHttpTransportConfig(
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

  private def requiredStringMap(
      config: Config,
      primary: String,
      alternate: String,
  ): Either[String, Map[String, String]] =
    findPath(config, primary, alternate)
      .toRight(ss"missing required config key: ${primary}")
      .flatMap: path =>
        Either
          .catchNonFatal(config.getConfig(path))
          .leftMap(_.getMessage)
          .map: section =>
            section
              .root()
              .entrySet()
              .asScala
              .toVector
              .map(entry => entry.getKey -> section.getString(entry.getKey))
              .toMap

  private def optionalConfig(
      config: Config,
      primary: String,
      alternate: String,
  ): Either[String, Option[Config]] =
    findPath(config, primary, alternate) match
      case None =>
        none[Config].asRight[String]
      case Some(path) =>
        Either
          .catchNonFatal(config.getConfig(path))
          .leftMap(_.getMessage)
          .map(_.some)

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
        Either
          .catchNonFatal(config.getInt(path))
          .leftMap(_.getMessage)

  private def optionalDurationMillis(
      config: Config,
      primary: String,
      alternate: String,
      default: Duration,
  ): Either[String, Duration] =
    findPath(config, primary, alternate) match
      case None =>
        default.asRight[String]
      case Some(path) =>
        Either
          .catchNonFatal(Duration.ofMillis(config.getLong(path)))
          .leftMap(_.getMessage)

  private def findPath(
      config: Config,
      primary: String,
      alternate: String,
  ): Option[String] =
    List(primary, alternate).find(config.hasPath)
