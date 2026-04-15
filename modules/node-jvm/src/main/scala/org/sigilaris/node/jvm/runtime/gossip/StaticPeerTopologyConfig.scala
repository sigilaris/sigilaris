package org.sigilaris.node.jvm.runtime.gossip

import cats.syntax.all.*
import com.typesafe.config.Config

import org.sigilaris.node.gossip.{PeerIdentity, StaticPeerTopology}
import org.sigilaris.node.jvm.runtime.config.TypesafeConfigParsing
import org.sigilaris.node.jvm.runtime.config.TypesafeConfigParsing.{
  ConfigAliases,
  ConfigField,
  ConfigReader,
}

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
/** Loads a `StaticPeerTopology` from Typesafe Config. */
object StaticPeerTopologyConfig:

  /** Default config path for peer topology settings. */
  val DefaultPath: String = "sigilaris.node.gossip.peers"

  /** Loads a peer topology from the given config at the specified path.
    *
    * @param config
    *   the root config
    * @param path
    *   the config path to read from
    * @return
    *   the topology, or an error message
    */
  def load(
      config: Config,
      path: String = DefaultPath,
  ): Either[String, StaticPeerTopology] =
    TypesafeConfigParsing.requiredSection(config, path).flatMap(loadSection)

  /** Loads a peer topology from a pre-resolved config section.
    *
    * @param section
    *   the config section at the gossip peers path
    * @return
    *   the topology, or an error message
    */
  def loadSection(
      section: Config,
  ): Either[String, StaticPeerTopology] =
    parseSection(section).flatMap: input =>
      StaticPeerTopology.fromValidated(
        localNodeIdentity = input.localNodeIdentity,
        knownPeers = input.knownPeers.toSet,
        directNeighbors = input.directNeighbors.toSet,
      )

  /** Parses the raw config input model from the root config. */
  def parse(
      config: Config,
      path: String = DefaultPath,
  ): Either[String, StaticPeerTopologyConfigInput] =
    TypesafeConfigParsing.requiredSection(config, path).flatMap(parseSection)

  /** Parses the raw config input model from a pre-resolved section. */
  def parseSection(
      section: Config,
  ): Either[String, StaticPeerTopologyConfigInput] =
    for
      localNodeIdentityRaw <- LocalNodeIdentity.required(section)
      localNodeIdentity <- PeerIdentity.parse(localNodeIdentityRaw)
      knownPeersRaw <- KnownPeers.required(section)
      knownPeers <- knownPeersRaw.traverse(PeerIdentity.parse)
      directNeighborsRaw <- DirectNeighbors.required(section)
      directNeighbors <- directNeighborsRaw.traverse(PeerIdentity.parse)
    yield StaticPeerTopologyConfigInput(
      localNodeIdentity = localNodeIdentity,
      knownPeers = knownPeers,
      directNeighbors = directNeighbors,
    )

  private val LocalNodeIdentity =
    ConfigField(
      aliases = ConfigAliases("local-node-identity", "localNodeIdentity"),
      reader = ConfigReader.string,
    )

  private val KnownPeers =
    ConfigField(
      aliases = ConfigAliases("known-peers", "knownPeers"),
      reader = ConfigReader.stringList,
    )

  private val DirectNeighbors =
    ConfigField(
      aliases = ConfigAliases("direct-neighbors", "directNeighbors"),
      reader = ConfigReader.stringList,
    )
