package org.sigilaris.node.jvm.runtime.consensus

import org.sigilaris.node.jvm.runtime.block.{
  BlockHeader,
  BlockHeight,
  BlockId as CanonicalBlockId,
  BlockTimestamp,
  BodyRoot,
  StateRoot,
}

package object hotstuff:
  type BlockId = CanonicalBlockId

  val BlockId: CanonicalBlockId.type =
    CanonicalBlockId

  type Block = BlockHeader

  object Block:
    def apply(
        parent: Option[BlockId],
        height: BlockHeight,
        stateRoot: StateRoot,
        bodyRoot: BodyRoot,
        timestamp: BlockTimestamp,
    ): Block =
      BlockHeader(
        parent = parent,
        height = height,
        stateRoot = stateRoot,
        bodyRoot = bodyRoot,
        timestamp = timestamp,
      )

    def computeId(
        block: Block,
    ): BlockId =
      BlockHeader.computeId(block)
