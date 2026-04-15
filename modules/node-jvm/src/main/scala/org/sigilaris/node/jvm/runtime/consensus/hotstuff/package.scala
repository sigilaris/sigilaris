package org.sigilaris.node.jvm.runtime.consensus

import org.sigilaris.node.jvm.runtime.block.{BlockId as CanonicalBlockId}

/** HotStuff consensus protocol implementation for the Sigilaris node runtime. */
package object hotstuff:
  /** Type alias for block identifiers used within the HotStuff consensus layer. */
  type BlockId = CanonicalBlockId

  /** The block ID companion object, re-exported for convenience. */
  val BlockId: CanonicalBlockId.type =
    CanonicalBlockId
