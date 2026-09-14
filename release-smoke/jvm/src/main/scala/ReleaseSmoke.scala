import cats.effect.{IO, IOApp}

import org.sigilaris.core.application.protocol.ProtocolVersion
import org.sigilaris.node.jvm.runtime.application.ApplicationSafetyDiagnostics
import org.sigilaris.node.jvm.runtime.txpipeline.{
  ExactPipelineRequestIdentity,
  InMemoryExactTxPipelineStore,
}
import org.sigilaris.node.txpipeline.*

object ReleaseSmoke extends IOApp.Simple:
  def run: IO[Unit] =
    for
      _       <- IO(checkSharedContracts())
      _       <- IO(M2SharedBaseline.run())
      _       <- M2JvmBaseline.run
      store   <- InMemoryExactTxPipelineStore.create[IO]
      missing <- store
        .getByRequestIdentity(ExactPipelineRequestIdentity("missing-request"))
        .value
      unjournaled <- store
        .countUnjournaled(Set.empty[TxPipelineId], stopAt = 1)
        .value
      _ <- IO {
        assert(missing == Right(None))
        assert(unjournaled == Right(0))
        assert(ApplicationSafetyDiagnostics.SchemaVersion == 1)
      }
      _ <- IO.println("0.3.0-M2 JVM public-artifact baseline passed")
    yield ()

  private def checkSharedContracts(): Unit =
    assert(ProtocolVersion.M1.value == 1)
    assert(
      ExactExecutionMode.all.map(_.wire) == Vector(
        "orderedAtomic",
        "certifiedAncestor",
      ),
    )
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
