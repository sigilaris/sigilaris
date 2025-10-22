package org.sigilaris.core
package crypto
package facade

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Uint8Array
import scala.annotation.unused

@js.native
@JSImport("elliptic", "ec")
class EC(@unused curve: String) extends js.Object:
  def genKeyPair(): JsKeyPair                     = js.native
  def keyFromPrivate(priv: Uint8Array): JsKeyPair = js.native
  def recoverPubKey(
      msg: Uint8Array,
      signature: js.Object,
      recoveryParam: Int,
      enc: js.UndefOr[String],
  ): BasePoint = js.native

object EC:
  def byName(name: String): EC = new EC(name)

  def secp256k1: EC = byName("secp256k1")
