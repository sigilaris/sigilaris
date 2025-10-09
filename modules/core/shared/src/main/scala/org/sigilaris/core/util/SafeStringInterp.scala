package org.sigilaris.core.util

/** String-only interpolator that mirrors `StringContext.s` while avoiding
  * WartRemover's `Any` wart. By restricting arguments to `String*`, it
  * preserves the readability of string interpolation without relying on `Any`.
  *
  * @example
  *   {{{
  * import org.sigilaris.core.util.SafeStringInterp.*
  * val hex: String = "deadbeef"
  * val msg: String = ss"non empty remainder: ${hex}"
  *   }}}
  */
object SafeStringInterp:
  extension (inline sc: StringContext)
    /** Interpolates `String` arguments using the underlying `StringContext`.
      *
      * @param args
      *   string values to be inserted into the context
      * @return
      *   the interpolated string
      */
    inline def ss(inline args: String*): String = sc.s(args*)
