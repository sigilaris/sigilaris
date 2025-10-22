package org.sigilaris.core
package crypto

import cats.syntax.either.*

import scodec.bits.*

//import Hash.ops.*
//import Recover.ops.*
//import Sign.ops.*
import codec.byte.ByteEncoder.ops.*
import failure.SigilarisFailure

import hedgehog.munit.HedgehogSuite
import hedgehog.*

class CryptoOpsTest extends HedgehogSuite:

  test("smoke: keccak256 empty"):
    withMunitAssertions: assertions =>
      val expected =
        hex"c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"
      val result = ByteVector.view(CryptoOps.keccak256(Array.empty))
      assertions.assertEquals(result, expected)

  test("keccak256 #2"):
    withMunitAssertions: assertions =>
      val expected =
        hex"4d741b6f1eb29cb2a9b9911c82f56fa8d73b04959d3d9d222895df6c0b28aa15"
      val result = ByteVector.view:
        CryptoOps.keccak256:
          "The quick brown fox jumps over the lazy dog".getBytes()
      assertions.assertEquals(result, expected)
//
  test("keccak256 #3"):
    withMunitAssertions: assertions =>
      val expected =
        hex"578951e24efd62a3d63a86f7cd19aaa53c898fe287d2552133220370240b572d"
      val result = ByteVector.view:
        CryptoOps.keccak256:
          "The quick brown fox jumps over the lazy dog.".getBytes()
      assertions.assertEquals(result, expected)
//
  val keyPair = CryptoOps.fromPrivate:
    BigInt(
      "10e93a6c964aa6bc089f84e4fe3fb37583f3e1162891a689dd99bb629520f3df",
      16,
    )
//
  test("keypair"):
    withMunitAssertions: assertions =>
      val expected =
        hex"e72699136b12ffd11549616ff047cd5ec93665cd6f13b859030a3c99d14842abc27a7442bc05143db53c41407a7059c85def28f6749b86b3123c48be3085e459"

      assertions.assertEquals(keyPair.publicKey.toBytes, expected)
//
  val genHashValue = Gen
    .bytes(Range.singleton(32))
    .map(ByteVector.view)
    .map(datatype.UInt256.fromBytesBE)
    .flatMap:
      case Left(error)    => Gen.discard
      case Right(uint256) => Gen.element1(uint256)
    .forAll
//
  def uint256(s: String): Either[String, UInt256BigInt] =
    UInt256.from(BigInt(s, 16)).leftMap(_.msg)
//
  property("sign and recover"):
    genHashValue.map: dataHashValue =>

      val recoveredPublicKey = for
        sig       <- CryptoOps.sign(keyPair, dataHashValue.bytes.toArray)
        publicKey <- CryptoOps.recover(sig, dataHashValue.bytes.toArray)
      yield publicKey

      keyPair.publicKey.asRight[SigilarisFailure] ==== recoveredPublicKey
//
  test("recover case #1"):
    withMunitAssertions: assertions =>
      val sigEither = for
        r <- uint256:
          "edae9df0c59097b58f5e73e3b88e6568290c207e8e172c8e55e1d6a0bc3e1f1c"
        s <- uint256:
          "60193d715a8c7ef90c4e58649b66c4bdafc6ff1121efcef77037425fb840d567"
        v = 27
      yield Signature(v, r, s)

      val data          = datatype.Utf8("some-data")
      val dataHashValue = CryptoOps.keccak256(data.toBytes.toArray)

      val recoveredPublicKey = for
        sig       <- sigEither
        publicKey <- CryptoOps.recover(sig, dataHashValue)
      yield publicKey

      assertions.assertEquals(
        keyPair.publicKey.asRight[String],
        recoveredPublicKey,
      )
//
  test("recover case #2"):
    withMunitAssertions: assertions =>
      val sigEither = for
        r <- uint256:
          "edae9df0c59097b58f5e73e3b88e6568290c207e8e172c8e55e1d6a0bc3e1f1c"
        s <- uint256:
          "9fe6c28ea5738106f3b1a79b64993b410ae7ddd58d58d1444f9b1c2d17f56bda"
        v = 28
      yield Signature(v, r, s)

      val data          = datatype.Utf8("some-data")
      val dataHashValue = CryptoOps.keccak256(data.toBytes.toArray)

      val recoveredPublicKey = for
        sig       <- sigEither
        publicKey <- CryptoOps.recover(sig, dataHashValue)
      yield publicKey

      assertions.assertEquals(
        keyPair.publicKey.asRight[String],
        recoveredPublicKey,
      )
