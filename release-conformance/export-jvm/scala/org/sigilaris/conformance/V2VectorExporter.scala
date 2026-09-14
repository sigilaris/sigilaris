package org.sigilaris.conformance

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** One-time artifact writer. Shared JVM/Scala.js tests subsequently assert
  * every byte.
  */
object V2VectorExporter:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "supply the output vector file path")
    V2CoreConformance.run()
    val vectors = V2CoreConformance
      .canonicalVectors() ++ V2CertificateConformance.canonicalVectors()
    val output = Path.of(args(0))
    Files.createDirectories(output.toAbsolutePath.getParent)
    Files.writeString(
      output,
      vectors
        .map((name, bytes) => s"$name ${bytes.toHex}")
        .mkString("\n") + "\n",
      StandardCharsets.UTF_8,
    )
    println(s"Exported ${vectors.size} actual V2 canonical vectors")
