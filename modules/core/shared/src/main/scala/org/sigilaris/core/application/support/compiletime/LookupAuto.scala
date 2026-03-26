package org.sigilaris.core.application.support.compiletime

import scala.compiletime.summonFrom
import scala.quoted.*

/** Inline derivation helper for [[Lookup]].
  *
  * Mirrors `summonInline` to preserve existing behaviour while surfacing a
  * descriptive compile-time error when the evidence cannot be derived.
  */
object LookupAuto:
  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  transparent inline def derive[Schema <: Tuple, Name <: String, K, V]
      : Lookup[Schema, Name, K, V] =
    summonFrom {
      case lookup: Lookup[Schema, Name, K, V] => lookup
      case _ =>
        fail[Schema, Name, K, V]
    }

  private inline def fail[Schema <: Tuple, Name <: String, K, V]: Nothing =
    ${ failImpl[Schema, Name, K, V] }

  @SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.ImplicitParameter"))
  private def failImpl[Schema <: Tuple, Name <: String, K, V](using
      Quotes,
      Type[Schema],
      Type[Name],
      Type[K],
      Type[V],
  ): Nothing =
    import quotes.reflect.report

    val schemaStr = EvidencePretty.schemaString[Schema]
    val nameStr   = EvidencePretty.stringLiteral[Name]
    val keyStr    = EvidencePretty.typeString[K]
    val valueStr  = EvidencePretty.typeString[V]

    report.errorAndAbort(
      s"""Lookup derivation failed.
         |Schema: ${schemaStr}
         |Missing entry: Entry[\"${nameStr}\", ${keyStr}, ${valueStr}]
         |
         |Ensure that the dependency module exposing table \"${nameStr}\" has been mounted
         |and that its key/value types match the expected codecs.""".stripMargin,
    )
