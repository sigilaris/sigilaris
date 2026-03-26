package org.sigilaris.core
package crypto
package facade

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Uint8Array

@js.native
@JSImport("js-sha3", "keccak256")
object Keccak256 extends js.Object:
  def update(data: Uint8Array): KeccakInstance = js.native
  def arrayBuffer(data: Uint8Array): js.typedarray.ArrayBuffer = js.native


