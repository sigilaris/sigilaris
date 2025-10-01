package org.sigilaris.core.failure

import scala.util.control.NoStackTrace

sealed trait SigilarisFailure extends NoStackTrace:
  def msg: String

final case class DecodeFailure(msg: String) extends SigilarisFailure
