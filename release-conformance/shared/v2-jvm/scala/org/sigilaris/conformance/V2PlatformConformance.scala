package org.sigilaris.conformance

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*

/** Uses only the selected Maven dependencies and the exported main fixtures. */
object V2PlatformConformance:
  private def scenario(name: String)(run: => IO[Unit]): IO[Unit] = for
    start <- IO.monotonic
    _     <- IO.println(s"V2 JVM START $name")
    _     <- IO.defer(run)
    end   <- IO.monotonic
    _     <- IO.println(s"V2 JVM PASS $name (${(end - start).toMillis} ms)")
  yield ()

  def run(): Unit = Vector(
    scenario("requests")(V2RequestConformance.run()),
    scenario("durable voting")(V2VotingConformance.run()),
    scenario("current signing authorization boundaries")(
      V2SigningAuthorizationConformance.run(),
    ),
    scenario("recoverable canonical application")(
      V2RecoverableApplicationConformance.run(),
    ),
    scenario("canonical ancestry")(V2CanonicalAncestorConformance.run()),
    scenario("exact execution modes")(V2ExactConformance.run()),
    scenario("exact mixed success")(V2ExactMixedConformance.run(false)),
    scenario("exact mixed failure")(V2ExactMixedConformance.run(true)),
    scenario("exact boundary binding")(V2ExactBoundaryConformance.run()),
    scenario("exact finalized application")(
      V2ExactApplicationConformance.run(),
    ),
    scenario("ordered voting")(V2OrderedVotingConformance.run()),
    scenario("finalized application runtime")(
      V2FinalizedApplicationConformance.run(),
    ),
    scenario("four actual voting runtimes")(V2RuntimeConformance.run()),
    scenario("complete stopped consistency groups")(
      V2ConsistencyGroupConformance.run(),
    ),
    scenario("key-owning fence controller and fault recovery")(
      V2FenceControllerConformance.run(),
    ),
    scenario("file journal force and corruption boundaries")(
      V2FileJournalConformance.run(),
    ),
    scenario("initial bootstrap and retired originals")(
      V2BootstrapConformance.run,
    ),
    scenario("four initial bootstrap runtimes across all source forms")(
      V2BootstrapRuntimeConformance.run,
    ),
    scenario("historical signed source and safety recovery")(
      V2HistoricalConformance.run(),
    ),
    scenario("four original stopped handover groups")(
      V2HistoricalGroupConformance.run(),
    ),
    scenario("four direct handover runtimes")(
      V2BootstrapConformance.temporary.use(V2HistoricalRuntimeFixture.run),
    ),
    scenario("four ordinary handover gossip runtimes")(
      V2BootstrapConformance.temporary.use(V2HistoricalTransportFixture.run),
    ),
    scenario("historical canonical publication fault")(
      V2BootstrapConformance.temporary.use(
        V2HistoricalCanonicalFaultFixture.run,
      ),
    ),
    scenario("original deployment/key inventory negatives")(
      V2TransitionInventoryConformance.run(),
    ),
    scenario("pinned historical profile boundaries")(
      V2HistoricalProfilesConformance.run(),
    ),
    scenario("original enabled issuance and late old quorum")(
      V2HistoricalIssuanceConformance.run(),
    ),
    scenario("three authenticated original drain routes")(
      V2BootstrapConformance.temporary.use(V2HistoricalDrainFixture.run),
    ),
    scenario("100000-id durable reservation")(
      V2ReservationScaleConformance.run(),
    ),
  ).sequence_.unsafeRunSync()
