package org.sigilaris.node.txpipeline

import java.security.MessageDigest

/** JVM SHA-256 implementation backed by the platform provider. */
private[node] object TxPipelineSha256Platform:
  def digestHex(bytes: Array[Byte]): String =
    TxPipelineSha256.hex(
      MessageDigest.getInstance("SHA-256").digest(bytes),
    )
