package org.sigilaris.core
package crypto
package facade

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Uint8Array

@js.native
trait EC extends js.Object:
  def genKeyPair(): JsKeyPair                     = js.native
  def keyFromPrivate(priv: Uint8Array): JsKeyPair = js.native
  def recoverPubKey(
      msg: Uint8Array,
      signature: js.Object,
      recoveryParam: Int,
      enc: js.UndefOr[String],
  ): BasePoint = js.native

@js.native
@JSImport("elliptic", JSImport.Namespace)
object Elliptic extends js.Object:
  val ec: js.Dynamic = js.native

object EC:
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def byName(name: String): EC =
    js.Dynamic.newInstance(Elliptic.ec)(name).asInstanceOf[EC]

  def secp256k1: EC = byName("secp256k1")
