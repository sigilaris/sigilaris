package org.sigilaris.core.assembly

import scala.annotation.tailrec
import scala.compiletime.constValue
import scala.quoted.*

import org.sigilaris.core.codec.byte.ByteCodec
import org.sigilaris.core.application.state.Entry

/** String interpolator helpers for declaring [[org.sigilaris.core.application.state.Entry]] values.
  *
  * Provides the `entry` interpolator for creating Entry instances with compile-time
  * string literal extraction:
  *
  * {{{
  * val usersEntry = entry"users"[String, User]
  * // Equivalent to: Entry["users", String, User]
  * }}}
  *
  * The interpolator ensures the table name is a literal string (not an expression),
  * preserving type-level information for schema validation.
  *
  * @see [[org.sigilaris.core.application.state.Entry]]
  */
object EntrySyntax:
  final class EntryBuilder[Name <: String]:
    inline def apply[K: ByteCodec, V: ByteCodec]: Entry[Name, K, V] =
      new Entry[Name, K, V](constValue[Name])

  private inline def builder[Name <: String]: EntryBuilder[Name] = new EntryBuilder[Name]

  extension (inline sc: StringContext)
    transparent inline def entry(inline args: Any*): EntryBuilder[? <: String] =
      ${ entryImpl('sc, 'args) }

  @SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.ImplicitParameter"))
  private def entryImpl(scExpr: Expr[StringContext], argsExpr: Expr[Seq[Any]])(using Quotes): Expr[EntryBuilder[? <: String]] =
    import quotes.reflect.{Apply, ConstantType, Inlined, Literal, Repeated, StringConstant, Term, Typed, report}

    import quotes.reflect.*

    @tailrec
    def fromArg(term: Term): Option[String] = term match
      case Typed(t, _)      => fromArg(t)
      case Repeated(ts, _)  => ts.collectFirst { case Literal(StringConstant(lit)) => lit }
      case Literal(StringConstant(lit)) => Some(lit)
      case _                => None

    @tailrec
    def literalFrom(term: Term): Option[String] = term match
      case Inlined(_, _, inner) => literalFrom(inner)
      case Apply(_, args)       => args.view.flatMap(fromArg).headOption
      case _                    => None

    argsExpr match
      case Varargs(Seq()) =>
        literalFrom(scExpr.asTerm) match
          case Some(lit) =>
            ConstantType(StringConstant(lit)).asType match
              case '[name] => '{ builder[name & String] }
          case None =>
            report.errorAndAbort("entry interpolator requires a literal string", scExpr)
      case _ =>
        report.errorAndAbort("entry interpolator does not support expressions", scExpr)
