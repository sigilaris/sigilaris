package org.sigilaris.core
package failure

import munit.FunSuite

class FailureMessageTest extends FunSuite:

  test("ErrorKey keeps failure code while rendering client message keys"):
    val code = FailureCode.unsafe("accounts.account_not_found")
    val key = ClientFailureMessage.errorKey(
      ClientFailureMessage.Kind.NotFound,
      domain = "accounts",
      reason = "account_not_found",
      code = code,
    )

    assertEquals(key.rendered, "not_found.accounts.account_not_found")
    assertEquals(key.code, code)

  test(
    "ClientFailureMessage falls back to safe key for invalid domain or reason",
  ):
    val message = ClientFailureMessage.invalidRequest(
      domain = "Bad-Domain",
      reason = "invalid reason",
      message = "broken input",
      detail = None,
    )

    assertEquals(
      message,
      "invalid_request.unknown.invalid_error_key: broken input",
    )

  test("ClientFailureMessage preserves kind when falling back to a safe key"):
    val message = ClientFailureMessage.notFound(
      domain = "Bad-Domain",
      reason = "invalid reason",
      message = "missing account",
      detail = None,
    )

    assertEquals(
      message,
      "not_found.unknown.invalid_error_key: missing account",
    )

  test("ClientFailureMessage supports forbidden kind"):
    val message = ClientFailureMessage.forbidden(
      domain = "accounts",
      reason = "unauthorized",
      message = "access denied",
      detail = Some("name=alice"),
    )

    assertEquals(
      message,
      "forbidden.accounts.unauthorized: access denied | name=alice",
    )

  test(
    "ClientFailureMessage omits blank detail and supports empty message branch",
  ):
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

  test("ConflictMessage keeps failure code while rendering conflict keys"):
    val code = FailureCode.unsafe("groups.group_already_exists")
    val key = ConflictMessage.errorKey(
      domain = "groups",
      reason = "group_already_exists",
      code = code,
    )
    val message = ConflictMessage.formatWithCode(
      domain = "groups",
      reason = "group_already_exists",
      message = "duplicate",
      detail = None,
      code = code,
    )

    assertEquals(key.rendered, "conflict.groups.group_already_exists")
    assertEquals(key.code, code)
    assertEquals(message, "conflict.groups.group_already_exists: duplicate")

  test(
    "SigilarisFailure exposes stable failure codes without changing messages",
  ):
    val failure = DecodeFailure("broken")
    assertEquals(failure.msg, "broken")
    assertEquals(failure.code, FailureCode.Decode)
    assertEquals(failure.kind, SigilarisFailure.Kind.Decode)

  test("FailureMessageEnvelope parses detail-only and empty-message branches"):
    assertEquals(
      FailureMessageEnvelope.parse(
        "not_found.accounts.account_not_found | id=alice",
      ),
      FailureMessageEnvelope(
        errorKey = "not_found.accounts.account_not_found",
        message = None,
        detail = Some("id=alice"),
      ),
    )

  test("FailureMessageEnvelope keeps legacy detail payload after the first separator"):
    assertEquals(
      FailureMessageEnvelope.parse(
        "conflict.groups.group_already_exists: duplicate | detail | extra",
      ),
      FailureMessageEnvelope(
        errorKey = "conflict.groups.group_already_exists",
        message = Some("duplicate"),
        detail = Some("detail | extra"),
      ),
    )

  test("FailureMessageEnvelope parses key-only envelopes"):
    assertEquals(
      FailureMessageEnvelope.parse("not_found.accounts.account_not_found"),
      FailureMessageEnvelope(
        errorKey = "not_found.accounts.account_not_found",
        message = None,
        detail = None,
      ),
    )

  test("FailureCode rejects non-machine-readable values"):
    assertEquals(
      FailureCode("bad code"),
      Left("Invalid FailureCode: bad code"),
    )

  test("FailureCode.unsafe preserves the throwing path for invalid constants"):
    interceptMessage[IllegalArgumentException]("Invalid FailureCode: bad code"):
      FailureCode.unsafe("bad code")
