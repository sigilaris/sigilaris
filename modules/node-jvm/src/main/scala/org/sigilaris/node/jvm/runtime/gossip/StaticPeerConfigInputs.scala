package org.sigilaris.node.jvm.runtime.gossip

import java.time.Duration

/** Raw config input model for static peer topology parsing. */
final case class StaticPeerTopologyConfigInput(
    localNodeIdentity: String,
    knownPeers: List[String],
    directNeighbors: List[String],
)

/** Raw config input model for static peer transport auth parsing. */
final case class StaticPeerTransportAuthConfigInput(
    peerSecrets: Map[String, String],
)

/** Raw config input model for static peer bootstrap transport parsing. */
final case class StaticPeerBootstrapHttpTransportConfigInput(
    peerBaseUris: Map[String, String],
    requestTimeout: Duration,
    maxConcurrentRequests: Int,
)
