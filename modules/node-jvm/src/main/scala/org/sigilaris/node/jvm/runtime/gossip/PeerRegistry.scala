package org.sigilaris.node.jvm.runtime.gossip

final case class StaticPeerTopology(
    localNodeIdentity: PeerIdentity,
    knownPeers: Set[PeerIdentity],
    directNeighbors: Set[PeerIdentity],
):
  def isKnownPeer(peer: PeerIdentity): Boolean =
    knownPeers.contains(peer)

  def isDirectNeighbor(peer: PeerIdentity): Boolean =
    directNeighbors.contains(peer)

object StaticPeerTopology:
  def parse(
      localNodeIdentity: String,
      knownPeers: List[String],
      directNeighbors: List[String],
  ): Either[String, StaticPeerTopology] =
    for
      local <- PeerIdentity.parse(localNodeIdentity)
      parsedKnown <- knownPeers.foldLeft[Either[String, Set[PeerIdentity]]](Right(Set.empty)):
        case (acc, value) =>
          for
            values <- acc
            parsed <- PeerIdentity.parse(value)
          yield values + parsed
      parsedDirect <- directNeighbors.foldLeft[Either[String, Set[PeerIdentity]]](Right(Set.empty)):
        case (acc, value) =>
          for
            values <- acc
            parsed <- PeerIdentity.parse(value)
          yield values + parsed
      _ <- Either.cond(
        parsedDirect.subsetOf(parsedKnown),
        (),
        "direct neighbors must be a subset of known peers",
      )
      _ <- Either.cond(
        !parsedKnown.contains(local),
        (),
        "known peers must not contain the local node identity",
      )
    yield StaticPeerTopology(
      localNodeIdentity = local,
      knownPeers = parsedKnown,
      directNeighbors = parsedDirect,
    )
