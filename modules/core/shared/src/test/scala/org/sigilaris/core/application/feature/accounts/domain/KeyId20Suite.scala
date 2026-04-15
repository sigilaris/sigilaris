package org.sigilaris.core.application.feature.accounts.domain

import hedgehog.*
import hedgehog.munit.HedgehogSuite

import org.sigilaris.core.codec.CodecLawSupport
import org.sigilaris.core.testing.HedgehogGenSupport

final class KeyId20Suite extends HedgehogSuite:
  property("KeyId20 byte codec round-trips through the shared fixed-size helper"):
    HedgehogGenSupport.fixedByteVector(20).forAll.map: bytes =>
      KeyId20(bytes) match
        case Right(keyId) => CodecLawSupport.ByteLaws.roundTrip(keyId)
        case Left(_)      => Result.failure

  property("KeyId20 rejects byte vectors with the wrong width"):
    HedgehogGenSupport.byteVector(Range.linear(0, 40)).forAll.map: bytes =>
      if bytes.size == 20L then Result.success
      else Result.assert(KeyId20(bytes).isLeft)
