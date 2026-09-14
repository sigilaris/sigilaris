package org.sigilaris.conformance

object PlatformConformance:
  def runV2(): Unit = run()

  def run(): Unit =
    assert(
      org.sigilaris.node.gossip.ChainId.parse("neutral-conformance").isRight,
    )
    assert(
      org.sigilaris.node.jvm.runtime.block.BlockHeight.fromLong(-1L).isLeft,
    )
