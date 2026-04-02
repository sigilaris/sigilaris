package org.sigilaris.node.jvm.runtime.gossip

import scala.jdk.CollectionConverters.*

import com.typesafe.config.Config

import org.sigilaris.core.util.SafeStringInterp.*

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
object StaticPeerTopologyConfig:
  val DefaultPath: String = "sigilaris.node.gossip.peers"

  def load(
      config: Config,
      path: String = DefaultPath,
  ): Either[String, StaticPeerTopology] =
    Either
      .cond(
        config.hasPath(path),
        config.getConfig(path),
        ss"missing config path: ${path}",
      )
      .flatMap(loadSection)

  def loadSection(
      section: Config,
  ): Either[String, StaticPeerTopology] =
    for
      localNodeIdentity <- requiredString(
        section,
        "local-node-identity",
        "localNodeIdentity",
      )
      knownPeers <- requiredStringList(section, "known-peers", "knownPeers")
      directNeighbors <- requiredStringList(
        section,
        "direct-neighbors",
        "directNeighbors",
      )
      topology <- StaticPeerTopology.parse(
        localNodeIdentity = localNodeIdentity,
        knownPeers = knownPeers,
        directNeighbors = directNeighbors,
      )
    yield topology

  private def requiredString(
      config: Config,
      primary: String,
      alternate: String,
  ): Either[String, String] =
    findPath(config, primary, alternate)
      .toRight(ss"missing required config key: ${primary}")
      .map(config.getString)

  private def requiredStringList(
      config: Config,
      primary: String,
      alternate: String,
  ): Either[String, List[String]] =
    findPath(config, primary, alternate)
      .toRight(ss"missing required config key: ${primary}")
      .map(path => config.getStringList(path).asScala.toList)

  private def findPath(
      config: Config,
      primary: String,
      alternate: String,
  ): Option[String] =
    List(primary, alternate).find(config.hasPath)
