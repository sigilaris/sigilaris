package org.sigilaris.conformance

object V2ConformanceMain:
  def main(args: Array[String]): Unit =
    PlatformConformance.runV2()
    CryptoDependencyConformance.run()
    LegacyProtocolVectors.run(LegacyProfile.M2)
    V2CoreConformance.run()
    V2TransitionEvidenceConformance.run()
    V2OpeningConformance.run()
    V2PlatformConformance.run()
