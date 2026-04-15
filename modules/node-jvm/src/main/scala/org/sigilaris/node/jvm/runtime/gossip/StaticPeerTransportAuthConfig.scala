package org.sigilaris.node.jvm.runtime.gossip

import cats.syntax.all.*
import com.typesafe.config.Config

import org.sigilaris.node.gossip.{
  PeerIdentity,
  StaticPeerTopology,
  StaticPeerTransportAuth,
  TransportSharedSecret,
}
import org.sigilaris.node.jvm.runtime.config.TypesafeConfigParsing
import org.sigilaris.node.jvm.runtime.config.TypesafeConfigParsing.{
  ConfigAliases,
  ConfigField,
  ConfigReader,
}

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
/** Loads a `StaticPeerTransportAuth` from Typesafe Config. */
object StaticPeerTransportAuthConfig:

  /** Default config path for transport auth settings. */
  val DefaultPath: String = StaticPeerTopologyConfig.DefaultPath

  /** Loads transport auth from the given config at the specified path.
    *
    * @param config
    *   the root config
    * @param topology
    *   the peer topology for validation
    * @param path
    *   the config path to read from
    * @return
    *   the transport auth, or an error message
    */
  def load(
      config: Config,
      topology: StaticPeerTopology,
      path: String = DefaultPath,
  ): Either[String, StaticPeerTransportAuth] =
    TypesafeConfigParsing.requiredSection(config, path)
      .flatMap(loadSection(_, topology))

  /** Loads transport auth from a pre-resolved config section.
    *
    * @param section
    *   the config section at the gossip path
    * @param topology
    *   the peer topology for validation
    * @return
    *   the transport auth, or an error message
    */
  def loadSection(
      section: Config,
      topology: StaticPeerTopology,
  ): Either[String, StaticPeerTransportAuth] =
    parseSection(section).flatMap: input =>
      StaticPeerTransportAuth.configure(topology, input.peerSecrets)

  /** Parses the raw transport-auth input model from the root config. */
  def parse(
      config: Config,
      path: String = DefaultPath,
  ): Either[String, StaticPeerTransportAuthConfigInput] =
    TypesafeConfigParsing.requiredSection(config, path).flatMap(parseSection)

  /** Parses the raw transport-auth input model from a pre-resolved section. */
  def parseSection(
      section: Config,
  ): Either[String, StaticPeerTransportAuthConfigInput] =
    for
      authSection <- TransportAuth.required(section)
      peerSecretsRaw <- PeerSecrets.required(authSection)
      peerSecrets <- peerSecretsRaw.toList.traverse: (peerRaw, secretRaw) =>
        for
          peer <- PeerIdentity.parse(peerRaw)
          secret <- TransportSharedSecret.fromUtf8(secretRaw)
        yield peer -> secret
    yield StaticPeerTransportAuthConfigInput(peerSecrets = peerSecrets.toMap)

  private val TransportAuth =
    ConfigField(
      aliases = ConfigAliases("transport-auth", "transportAuth"),
      reader = ConfigReader.configSection,
    )

  private val PeerSecrets =
    ConfigField(
      aliases = ConfigAliases("peer-secrets", "peerSecrets"),
      reader = ConfigReader.stringMap,
    )
