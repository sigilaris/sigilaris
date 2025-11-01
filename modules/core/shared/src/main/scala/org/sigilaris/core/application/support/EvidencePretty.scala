package org.sigilaris.core.application.support

import scala.quoted.*

import org.sigilaris.core.application.domain.Entry

/** Internal pretty-printers used by evidence derivation helpers. */
private[support] object EvidencePretty:
  inline def schema[S <: Tuple]: String = ${ schemaImpl[S] }

  inline def typeName[T]: String = ${ typeImpl[T] }

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.Any",
      "org.wartremover.warts.Equals",
      "org.wartremover.warts.Recursion",
      "org.wartremover.warts.ImplicitParameter",
    ),
  )
  def schemaString[S <: Tuple](using Quotes, Type[S]): String =
    import quotes.reflect.*

    def loop(tpe: TypeRepr): String =
      if tpe =:= TypeRepr.of[EmptyTuple] then "EmptyTuple"
      else
        tpe match
          case AppliedType(tuple, List(head, tail)) if tuple =:= TypeRepr.of[*:] =>
            val headStr = head match
              case AppliedType(entry, args) if entry =:= TypeRepr.of[Entry[?, ?, ?]] =>
                val nameRepr  = args(0)
                val keyRepr   = args(1)
                val valueRepr = args(2)
                val nameStr = nameRepr match
                  case ConstantType(StringConstant(str)) => str
                  case other                             => other.show
                val keyStr   = keyRepr.show
                val valueStr = valueRepr.show
                s"Entry[\"${nameStr}\", ${keyStr}, ${valueStr}]"
              case other => other.show
            val tailStr: String = loop(tail)
            if tailStr.equals("EmptyTuple") then headStr else s"${headStr} *: ${tailStr}"
          case other => other.show

    loop(TypeRepr.of[S].dealias)

  @SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
  def typeString[T](using Quotes, Type[T]): String =
    import quotes.reflect.*
    TypeRepr.of[T].dealias.show

  @SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
  def stringLiteral[N <: String](using Quotes, Type[N]): String =
    import quotes.reflect.*
    Type.valueOfConstant[N] match
      case Some(value) => value
      case None        => TypeRepr.of[N].show

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.Any",
      "org.wartremover.warts.Equals",
      "org.wartremover.warts.Recursion",
      "org.wartremover.warts.ImplicitParameter",
    ),
  )
  private def schemaImpl[S <: Tuple](using Quotes, Type[S]): Expr[String] =
    Expr(schemaString[S])

  @SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
  private def typeImpl[T: Type](using Quotes): Expr[String] =
    Expr(typeString[T])
