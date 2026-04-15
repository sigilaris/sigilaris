package org.sigilaris.node.jvm.runtime.gossip

import com.typesafe.config.Config

import org.sigilaris.node.gossip.{StaticPeerTopology, StaticPeerTransportAuth}
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
      peerSecrets <- PeerSecrets.required(authSection)
    yield StaticPeerTransportAuthConfigInput(peerSecrets = peerSecrets)

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
