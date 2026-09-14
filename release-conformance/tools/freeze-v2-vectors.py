#!/usr/bin/env python3
"""Freeze bytes produced by the executed JVM exporter; never computes protocol bytes."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
source = root / "fixtures/v2-core-vectors.txt"
records = []
for line in source.read_text().splitlines():
    name, hexadecimal = line.split(" ", 1)
    if not re.fullmatch(r"[a-z][a-z0-9-]*", name):
        raise ValueError(f"invalid vector name: {name}")
    if not re.fullmatch(r"(?:[0-9a-f]{2})+", hexadecimal):
        raise ValueError(f"invalid canonical hex: {name}")
    records.append((name, hexadecimal))
if len({name for name, _ in records}) != len(records) or not records:
    raise ValueError("empty or duplicate vector set")
entries = "\n".join(f'    "{name}" -> "{hexadecimal}",' for name, hexadecimal in records)
output = root / "shared/v2/scala/org/sigilaris/conformance/V2GoldenVectors.scala"
output.write_text('''package org.sigilaris.conformance

import scodec.bits.ByteVector

/** Executed JVM codec output; shared JVM/Scala.js consumers assert every byte.
  * Regenerate only after reviewing a protocol-compatible source change.
  */
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object V2GoldenVectors:
  val expected: Map[String, String] = Map(
''' + entries + '''
  )

  def validate(actual: Vector[(String, ByteVector)]): Unit =
    assert(actual.size == expected.size, "golden vector membership changed")
    assert(actual.map(_._1).distinct.size == actual.size, "duplicate vector name")
    actual.foreach: (name, bytes) =>
      assert(expected.get(name).contains(bytes.toHex), s"canonical vector mismatch: $name")
''')
print(f"Froze {len(records)} executed vectors in {output.name}")
