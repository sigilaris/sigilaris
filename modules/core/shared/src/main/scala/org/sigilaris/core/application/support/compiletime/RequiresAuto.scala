package org.sigilaris.core.application.support.compiletime

import scala.compiletime.summonFrom
import scala.quoted.*

/** Inline derivation helper for [[Requires]]. */
object RequiresAuto:
  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  transparent inline def derive[Needs <: Tuple, Schema <: Tuple]
      : Requires[Needs, Schema] =
    summonFrom {
      case evidence: Requires[Needs, Schema] => evidence
      case _ =>
        fail[Needs, Schema]
    }

  private inline def fail[Needs <: Tuple, Schema <: Tuple]: Nothing =
    ${ failImpl[Needs, Schema] }

  @SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.ImplicitParameter"))
  private def failImpl[Needs <: Tuple, Schema <: Tuple](using
      Quotes,
      Type[Needs],
      Type[Schema],
  ): Nothing =
    import quotes.reflect.report

    val needsStr  = EvidencePretty.schemaString[Needs]
    val schemaStr = EvidencePretty.schemaString[Schema]

    report.errorAndAbort(
      s"""Requires derivation failed.
         |Needs: ${needsStr}
         |Schema: ${schemaStr}
         |
         |Ensure every required table exists in the composed schema and that dependency
         |modules are mounted before wiring the current blueprint.""".stripMargin,
    )
