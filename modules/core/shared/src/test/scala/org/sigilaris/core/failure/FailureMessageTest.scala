package org.sigilaris.core
package failure

import munit.FunSuite

class FailureMessageTest extends FunSuite:

  test("ClientFailureMessage falls back to safe key for invalid domain or reason"):
    val message = ClientFailureMessage.invalidRequest(
      domain = "Bad-Domain",
      reason = "invalid reason",
      message = "broken input",
      detail = None,
    )

    assertEquals(message, "invalid_request.unknown.invalid_error_key: broken input")

  test("ClientFailureMessage preserves kind when falling back to a safe key"):
    val message = ClientFailureMessage.notFound(
      domain = "Bad-Domain",
      reason = "invalid reason",
      message = "missing account",
      detail = None,
    )

    assertEquals(message, "not_found.unknown.invalid_error_key: missing account")

  test("ClientFailureMessage supports forbidden kind"):
    val message = ClientFailureMessage.forbidden(
      domain = "accounts",
      reason = "unauthorized",
      message = "access denied",
      detail = Some("name=alice"),
    )

    assertEquals(message, "forbidden.accounts.unauthorized: access denied | name=alice")

  test("ClientFailureMessage omits blank detail and supports empty message branch"):
    val message = ClientFailureMessage.notFound(
      domain = "accounts",
      reason = "account_not_found",
      message = "   ",
      detail = Some("   "),
    )

    assertEquals(message, "not_found.accounts.account_not_found")

  test("ConflictMessage falls back to safe key and omits blank detail"):
    val message = ConflictMessage.format(
      domain = "Bad-Domain",
      reason = "invalid reason",
      message = "duplicate",
      detail = Some("   "),
    )

    assertEquals(message, "conflict.unknown.invalid_error_key: duplicate")
