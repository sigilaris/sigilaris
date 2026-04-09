package org.sigilaris.node.jvm.runtime.gossip

import cats.syntax.all.*

import scala.jdk.CollectionConverters.*

import com.typesafe.config.Config

import org.sigilaris.core.util.SafeStringInterp.*

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
object StaticPeerTransportAuthConfig:
  val DefaultPath: String = StaticPeerTopologyConfig.DefaultPath

  def load(
      config: Config,
      topology: StaticPeerTopology,
      path: String = DefaultPath,
  ): Either[String, StaticPeerTransportAuth] =
    Either
      .cond(
        config.hasPath(path),
        config.getConfig(path),
        ss"missing config path: ${path}",
      )
      .flatMap(loadSection(_, topology))

  def loadSection(
      section: Config,
      topology: StaticPeerTopology,
  ): Either[String, StaticPeerTransportAuth] =
    for
      authSection <- requiredConfig(section, "transport-auth", "transportAuth")
      secretSection <- requiredConfig(
        authSection,
        "peer-secrets",
        "peerSecrets",
      )
      peerSecrets <- secretSection
        .root()
        .keySet()
        .asScala
        .toList
        .sorted
        .traverse: peer =>
          Either
            .catchNonFatal(secretSection.getString(peer))
            .leftMap(_.getMessage)
            .map(peer -> _)
        .map(_.toMap)
      transportAuth <- StaticPeerTransportAuth.configure(topology, peerSecrets)
    yield transportAuth

  private def requiredConfig(
      config: Config,
      primary: String,
      alternate: String,
  ): Either[String, Config] =
    findPath(config, primary, alternate)
      .toRight(ss"missing required config key: ${primary}")
      .map(config.getConfig)

  private def findPath(
      config: Config,
      primary: String,
      alternate: String,
  ): Option[String] =
    List(primary, alternate).find(config.hasPath)
