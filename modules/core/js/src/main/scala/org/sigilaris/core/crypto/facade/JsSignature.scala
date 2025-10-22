package org.sigilaris.core
package crypto
package facade

import scala.scalajs.js

@js.native
trait JsSignature extends js.Object:
  val r: BN = js.native
  val s: BN = js.native
  val recoveryParam: js.UndefOr[Int | Double] = js.native


