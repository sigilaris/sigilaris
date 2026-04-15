package org.sigilaris.node.gossip

import cats.syntax.all.*

/** A static, immutable description of the peer topology for a gossip node.
  *
  * @param localNodeIdentity
  *   the identity of the local node
  * @param knownPeers
  *   all known peer identities (excluding the local node)
  * @param directNeighbors
  *   the subset of known peers that are direct neighbors
  */
final case class StaticPeerTopology(
    localNodeIdentity: PeerIdentity,
    knownPeers: Set[PeerIdentity],
    directNeighbors: Set[PeerIdentity],
):

  /** Checks whether the given peer is in the set of known peers.
    *
    * @param peer
    *   the peer identity to check
    * @return
    *   true if known
    */
  def isKnownPeer(peer: PeerIdentity): Boolean =
    knownPeers.contains(peer)

  /** Checks whether the given peer is a direct neighbor.
    *
    * @param peer
    *   the peer identity to check
    * @return
    *   true if a direct neighbor
    */
  def isDirectNeighbor(peer: PeerIdentity): Boolean =
    directNeighbors.contains(peer)

/** Companion for `StaticPeerTopology` providing parsing from raw strings. */
object StaticPeerTopology:

  /** Builds a topology from already-validated peer identities. */
  def fromValidated(
      localNodeIdentity: PeerIdentity,
      knownPeers: Set[PeerIdentity],
      directNeighbors: Set[PeerIdentity],
  ): Either[String, StaticPeerTopology] =
    for
      _ <- Either.cond(
        directNeighbors.subsetOf(knownPeers),
        (),
        "direct neighbors must be a subset of known peers",
      )
      _ <- Either.cond(
        !knownPeers.contains(localNodeIdentity),
        (),
        "known peers must not contain the local node identity",
      )
    yield StaticPeerTopology(
      localNodeIdentity = localNodeIdentity,
      knownPeers = knownPeers,
      directNeighbors = directNeighbors,
    )

  /** Parses a static peer topology from raw string values, validating that
    * direct neighbors are a subset of known peers and the local node is not in
    * known peers.
    *
    * @param localNodeIdentity
    *   the local node identity string
    * @param knownPeers
    *   the list of known peer identity strings
    * @param directNeighbors
    *   the list of direct neighbor identity strings
    * @return
    *   the validated topology, or an error message
    */
  def parse(
      localNodeIdentity: String,
      knownPeers: List[String],
      directNeighbors: List[String],
  ): Either[String, StaticPeerTopology] =
    for
      local <- PeerIdentity.parse(localNodeIdentity)
      parsedKnown <- knownPeers.foldLeft[Either[String, Set[PeerIdentity]]](
        Set.empty[PeerIdentity].asRight[String],
      ):
        case (acc, value) =>
          for
            values <- acc
            parsed <- PeerIdentity.parse(value)
          yield values + parsed
      parsedDirect <- directNeighbors
        .foldLeft[Either[String, Set[PeerIdentity]]](
          Set.empty[PeerIdentity].asRight[String],
        ):
          case (acc, value) =>
            for
              values <- acc
              parsed <- PeerIdentity.parse(value)
            yield values + parsed
      topology <- fromValidated(
        localNodeIdentity = local,
        knownPeers = parsedKnown,
        directNeighbors = parsedDirect,
      )
    yield topology
