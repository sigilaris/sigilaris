package org.sigilaris.node.gossip

import munit.FunSuite

import org.sigilaris.core.failure.FailureDiagnosticFamily

final class GossipDiagnosticSuite extends FunSuite:

  test("CanonicalRejection exposes typed diagnostic families"):
    val handshake =
      CanonicalRejection.HandshakeRejected(
        reason = "authenticatedPeerMismatch",
        detail = Some("expected=node-a actual=node-b"),
      )
    val stale = CanonicalRejection.StaleCursor("cursorExpired")
    val control = CanonicalRejection.ControlBatchRejected("invalidControlOp")
    val artifact = CanonicalRejection.ArtifactContractRejected("invalidArtifact")
    val backfill = CanonicalRejection.BackfillUnavailable("txBackfillUnavailable")

    assertEquals(
      handshake.diagnosticFamily,
      FailureDiagnosticFamily.GossipHandshakeRejected,
    )
    assertEquals(stale.diagnosticFamily, FailureDiagnosticFamily.GossipStaleCursor)
    assertEquals(
      control.diagnosticFamily,
      FailureDiagnosticFamily.GossipControlBatchRejected,
    )
    assertEquals(
      artifact.diagnosticFamily,
      FailureDiagnosticFamily.GossipArtifactContractRejected,
    )
    assertEquals(
      backfill.diagnosticFamily,
      FailureDiagnosticFamily.GossipBackfillUnavailable,
    )
