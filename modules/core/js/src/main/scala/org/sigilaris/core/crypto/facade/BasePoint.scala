package org.sigilaris.core
package crypto
package facade

import scala.scalajs.js

@js.native
trait BasePoint extends js.Object:
  def getX(): BN = js.native
  def getY(): BN = js.native


