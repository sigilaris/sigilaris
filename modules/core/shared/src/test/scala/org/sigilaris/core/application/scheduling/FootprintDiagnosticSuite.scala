package org.sigilaris.core.application.scheduling

import munit.FunSuite

import org.sigilaris.core.failure.FailureDiagnosticFamily

final class FootprintDiagnosticSuite extends FunSuite:

  test("FootprintDerivationFailure exposes the normalized diagnostic surface"):
    val failure = FootprintDerivationFailure(
      reason = "unsupportedTx",
      detail = Some("tx=groups.create"),
    )

    assertEquals(
      failure.diagnosticFamily,
      FailureDiagnosticFamily.FootprintDerivation,
    )
    assertEquals(
      failure.diagnostic,
      org.sigilaris.core.failure.FailureDiagnostic(
        reason = "unsupportedTx",
        detail = Some("tx=groups.create"),
      ),
    )
