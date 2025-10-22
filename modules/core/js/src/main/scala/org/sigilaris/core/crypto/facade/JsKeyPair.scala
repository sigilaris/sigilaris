package org.sigilaris.core
package crypto
package facade

import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

@js.native
trait JsKeyPair extends js.Object:
  def getPrivate(): BN = js.native
  def getPublic(): BasePoint = js.native
  def sign(msg: Uint8Array): JsSignature = js.native


