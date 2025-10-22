package org.sigilaris.core
package crypto

import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.typedarray.Uint8Array

import cats.syntax.either.*
 

import scodec.bits.ByteVector

import util.SafeStringInterp.*

//import typings.bnJs.bnJsStrings.hex
//import typings.elliptic.mod.{ec as EC}
//import typings.elliptic.mod.curve.base.BasePoint
//import typings.elliptic.mod.ec.{KeyPair as JsKeyPair, Signature as JsSignature}
//import typings.jsSha3.mod.{keccak256 as jsKeccak256}


object CryptoOps:

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def keccak256(input: Array[Byte]): Array[Byte] =

    val hexString: String = Keccak256.update(input.toUint8Array).hex()

    ByteVector
      .fromHexDescriptive(hexString)
      .fold(e => throw new Exception(e), _.toArray)

  extension (byteArray: Array[Byte])
    def toUint8Array: Uint8Array =
      Uint8Array.from[Byte](byteArray.toJSArray, _.toShort)

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  val ec: EC =
    val mod = scala.scalajs.js.Dynamic.global.require("elliptic")
    val C   = mod.selectDynamic("ec")
    scala.scalajs.js.Dynamic.newInstance(C)("secp256k1").asInstanceOf[EC]

  def generate(): KeyPair = ec.genKeyPair().asScala

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def fromPrivate(privateKey: BigInt): KeyPair =
    val priv32 = UInt256.from(privateKey).getOrElse {
      throw new Exception(ss"Invalid private key: ${privateKey.toString(16)}")
    }.toBytes
    ec.keyFromPrivate(priv32.toArray.toUint8Array).asScala

  extension (jsKeyPair: JsKeyPair)
    @SuppressWarnings(
      Array("org.wartremover.warts.Throw", "org.wartremover.warts.Overloading"),
    )
    def asScala: KeyPair =
      val privHex = jsKeyPair.getPrivate().toStringBase(16)
      val pBigInt = BigInt(privHex, 16)
      val p: UInt256BigInt = UInt256.from(pBigInt).getOrElse {
        throw new Exception(ss"Wrong private key: ${privHex}")
      }

      KeyPair(p, jsKeyPair.getPublic().asScala)

  extension (pubKey: BasePoint)
    @SuppressWarnings(
      Array("org.wartremover.warts.Throw", "org.wartremover.warts.Overloading"),
    )
    def asScala: PublicKey =
      val xHex    = pubKey.getX().toStringBase(16)
      val yHex    = pubKey.getY().toStringBase(16)
      val xBigInt = BigInt(xHex, 16)
      val yBigInt = BigInt(yHex, 16)

      val scalaPubKeyEither = for
        x <- UInt256.from(xBigInt)
        y <- UInt256.from(yBigInt)
      yield PublicKey(x, y)

      scalaPubKeyEither.getOrElse {
        throw new Exception(ss"Wrong public key: (${xHex}, ${yHex})")
      }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def sign(
      keyPair: KeyPair,
      transactionHash: Array[Byte],
  ): Either[String, Signature] =
    val jsSig = keyPair.toJs.sign(transactionHash.toUint8Array)
    val sigObj: scala.scalajs.js.Object = scala.scalajs.js.Dynamic.literal(
      "r" -> jsSig.r.toStringBase(16),
      "s" -> jsSig.s.toStringBase(16),
    )
    val pub0: PublicKey = ec
      .recoverPubKey(transactionHash.toUint8Array, sigObj, 0, scala.scalajs.js.undefined)
      .asScala
    val pub1: PublicKey = ec
      .recoverPubKey(transactionHash.toUint8Array, sigObj, 1, scala.scalajs.js.undefined)
      .asScala
    val recoveryParamEither: Either[String, Int] =
      if pub0.toBigInt.equals(keyPair.publicKey.toBigInt) then 0.asRight[String]
      else if pub1.toBigInt.equals(keyPair.publicKey.toBigInt) then 1.asRight[String]
      else "Unable to determine recoveryParam".asLeft[Int]
    for
      recoveryParam <- recoveryParamEither
      v = 27 + recoveryParam
      r <- UInt256.from(BigInt(jsSig.r.toStringBase(16), 16)).left.map(_.msg)
      s <- UInt256.from(BigInt(jsSig.s.toStringBase(16), 16)).left.map(_.msg)
    yield Signature(v, r, s)

  extension (keyPair: KeyPair)
    def toJs: JsKeyPair =
      ec.keyFromPrivate(keyPair.privateKey.toBytes.toArray.toUint8Array)

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def recover(
      signature: Signature,
      hashArray: Array[Byte],
  ): Either[String, PublicKey] =
    val jsSig: scala.scalajs.js.Object = scala.scalajs.js.Dynamic.literal(
      "r" -> signature.r.toString(16),
      "s" -> signature.s.toString(16),
    )

    val pub: PublicKey = ec
      .recoverPubKey(hashArray.toUint8Array, jsSig, signature.v - 27, scala.scalajs.js.undefined)
      .asScala
    pub.asRight[String]
