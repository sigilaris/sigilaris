package org.sigilaris.node.txpipeline

/** Scala.js SHA-256 implementation shared with the parity fixtures. */
private[node] object TxPipelineSha256Platform:
  def digestHex(bytes: Array[Byte]): String =
    TxPipelineSha256.digestHex(bytes)
