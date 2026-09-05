import org.sigilaris.core.application.protocol.ProtocolVersion
import org.sigilaris.node.txpipeline.*

object ReleaseSmoke:
  def main(args: Array[String]): Unit =
    assert(ProtocolVersion.M1.value == 1)
    assert(ExactExecutionMode.OrderedAtomic.tag == 1)
    assert(ExactExecutionMode.CertifiedAncestor.tag == 2)
    val invalidManifest = ApplicationProtocolManifestV1.withComputedDigest(
      epoch = 0L,
      validatorSetHash = "00" * 32,
      maxLockLifetimeBlocks = 1L,
      substrate = ApplicationSubstrateVersions.M1,
      profiles = Vector(
        DependencyProfileManifest(
          DependencyProfileId("smoke"),
          DependencyProfileVersion(1),
          VerifierSlot("smoke"),
          VerifierManifestDigest("invalid-digest"),
        ),
      ),
    )
    assert(
      invalidManifest == Left(
        ApplicationProtocolManifestFailure.InvalidVerifierManifestDigest,
      ),
    )
    println("0.3.0-M2 Scala.js staged-artifact smoke passed")
