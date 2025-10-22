package org.sigilaris.core
package crypto

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Uint8Array
import scala.annotation.unused

@js.native
@JSImport("elliptic", "ec")
class EC(@unused curve: String) extends js.Object:
  def genKeyPair(): JsKeyPair = js.native
  def keyFromPrivate(priv: Uint8Array): JsKeyPair = js.native
  def recoverPubKey(
      msg: Uint8Array,
      signature: js.Object,
      recoveryParam: Int,
      enc: js.UndefOr[String],
  ): BasePoint = js.native

@js.native
trait JsKeyPair extends js.Object:
  def getPrivate(): BN = js.native
  def getPublic(): BasePoint = js.native
  def sign(msg: Uint8Array): JsSignature = js.native

@js.native
trait BN extends js.Object:
  @JSName("toString")
  def toStringBase(radix: Int): String = js.native

@js.native
trait BasePoint extends js.Object:
  def getX(): BN = js.native
  def getY(): BN = js.native

@js.native
trait JsSignature extends js.Object:
  val r: BN = js.native
  val s: BN = js.native
  val recoveryParam: js.UndefOr[Int | Double] = js.native

@js.native
@js.native
@JSImport("js-sha3", "keccak256")
@js.native
object Keccak256 extends js.Object:
  def update(data: Uint8Array): KeccakInstance = js.native
  def arrayBuffer(data: Uint8Array): js.typedarray.ArrayBuffer = js.native

@js.native
trait KeccakInstance extends js.Object:
  def update(data: Uint8Array): KeccakInstance = js.native
  def hex(): String = js.native


