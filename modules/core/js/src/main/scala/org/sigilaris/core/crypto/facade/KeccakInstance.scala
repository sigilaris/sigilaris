package org.sigilaris.core
package crypto
package facade

import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

@js.native
trait KeccakInstance extends js.Object:
  def update(data: Uint8Array): KeccakInstance = js.native
  def hex(): String = js.native


