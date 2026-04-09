package org.sigilaris.node.jvm.runtime.consensus

import org.sigilaris.node.jvm.runtime.block.{BlockId as CanonicalBlockId}

package object hotstuff:
  type BlockId = CanonicalBlockId

  val BlockId: CanonicalBlockId.type =
    CanonicalBlockId
