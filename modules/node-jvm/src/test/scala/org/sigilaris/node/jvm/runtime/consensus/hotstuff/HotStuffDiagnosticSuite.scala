package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import munit.FunSuite

import org.sigilaris.core.failure.FailureDiagnosticFamily

final class HotStuffDiagnosticSuite extends FunSuite:

  test("HotStuff reason/detail failures expose typed diagnostic families"):
    val validation = HotStuffValidationFailure(
      reason = "proposalWindowMismatch",
      detail = Some("proposal=abc"),
    )
    val policy = HotStuffPolicyViolation(
      reason = "localSignerUnavailable",
      detail = Some("validator=node-a"),
    )
    val snapshot = SnapshotSyncFailure(
      reason = "snapshotAnchorNotFound",
      detail = Some("chain=main"),
    )

    assertEquals(
      validation.diagnosticFamily,
      FailureDiagnosticFamily.HotStuffValidation,
    )
    assertEquals(
      policy.diagnosticFamily,
      FailureDiagnosticFamily.HotStuffPolicyViolation,
    )
    assertEquals(snapshot.diagnosticFamily, FailureDiagnosticFamily.SnapshotSync)
