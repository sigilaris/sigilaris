package org.sigilaris.node.jvm.runtime.gossip

import java.net.URI
import java.time.Duration

import org.sigilaris.node.gossip.{PeerIdentity, TransportSharedSecret}

/** Raw config input model for static peer topology parsing. */
final case class StaticPeerTopologyConfigInput(
    localNodeIdentity: PeerIdentity,
    knownPeers: List[PeerIdentity],
    directNeighbors: List[PeerIdentity],
)

/** Raw config input model for static peer transport auth parsing. */
final case class StaticPeerTransportAuthConfigInput(
    peerSecrets: Map[PeerIdentity, TransportSharedSecret],
)

/** Raw config input model for static peer bootstrap transport parsing. */
final case class StaticPeerBootstrapHttpTransportConfigInput(
    peerBaseUris: Map[PeerIdentity, URI],
    requestTimeout: Duration,
    maxConcurrentRequests: Int,
)
