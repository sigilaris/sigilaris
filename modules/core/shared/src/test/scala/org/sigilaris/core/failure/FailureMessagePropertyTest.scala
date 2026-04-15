package org.sigilaris.core
package failure

import hedgehog.*
import hedgehog.munit.HedgehogSuite

final class FailureMessagePropertyTest extends HedgehogSuite:

  // The legacy parser treats the first literal " | " as the detail boundary,
  // so canonical property inputs keep payload text separator-free.
  private val genToken: Gen[String] =
    Gen.string(Gen.alphaNum.map(_.toLower), Range.linear(1, 16))

  private val genMessageText: Gen[String] =
    Gen.string(
      Gen.choice1(
        Gen.alphaNum,
        Gen.constant(' '),
        Gen.constant('-'),
        Gen.constant('_'),
        Gen.constant('.'),
        Gen.constant('\\'),
      ),
      Range.linear(0, 32),
    )

  private val genOptionalText: Gen[Option[String]] =
    Gen.choice1(
      Gen.constant(None),
      genMessageText.map(Some(_)),
    )

  private val genClientKind: Gen[ClientFailureMessage.Kind] =
    Gen.element1(
      ClientFailureMessage.Kind.InvalidRequest,
      ClientFailureMessage.Kind.Forbidden,
      ClientFailureMessage.Kind.NotFound,
    )

  property("ClientFailureMessage render/parse round-trips through envelopes"):
    for
      kind <- genClientKind.forAll
      domain <- genToken.forAll
      reason <- genToken.forAll
      message <- genMessageText.forAll
      detail <- genOptionalText.forAll
    yield
      val code =
        kind match
          case ClientFailureMessage.Kind.InvalidRequest =>
            ClientFailureMessage.InvalidRequestCode
          case ClientFailureMessage.Kind.Forbidden =>
            ClientFailureMessage.ForbiddenCode
          case ClientFailureMessage.Kind.NotFound =>
            ClientFailureMessage.NotFoundCode
      val envelope = ClientFailureMessage.envelope(
        kind = kind,
        domain = domain,
        reason = reason,
        message = message,
        detail = detail,
        code = code,
      )
      Result.assert(FailureMessageEnvelope.parse(envelope.render) == envelope)

  property("ConflictMessage render/parse round-trips through envelopes"):
    for
      domain <- genToken.forAll
      reason <- genToken.forAll
      message <- genMessageText.forAll
      detail <- genOptionalText.forAll
    yield
      val envelope = ConflictMessage.envelope(
        domain = domain,
        reason = reason,
        message = message,
        detail = detail,
        code = ConflictMessage.DefaultCode,
      )
      Result.assert(FailureMessageEnvelope.parse(envelope.render) == envelope)
