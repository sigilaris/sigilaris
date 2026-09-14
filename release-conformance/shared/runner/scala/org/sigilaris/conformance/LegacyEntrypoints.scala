package org.sigilaris.conformance

object LegacyM1Conformance:
  def main(args: Array[String]): Unit =
    PlatformConformance.run()
    LegacyProtocolVectors.run(LegacyProfile.M1)

object LegacyM2Conformance:
  def main(args: Array[String]): Unit =
    PlatformConformance.run()
    LegacyProtocolVectors.run(LegacyProfile.M2)
