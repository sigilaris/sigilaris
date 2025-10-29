package org.sigilaris.core
package application

/** Type alias for a single-element string tuple.
  *
  * Simplifies common pattern `"name" *: EmptyTuple` to `Path["name"]`.
  *
  * @tparam S
  *   the string literal type
  */
type Path[S <: String] = S *: EmptyTuple

/** Type alias for a two-element string tuple.
  *
  * Simplifies common pattern `"s1" *: "s2" *: EmptyTuple` to `Path2["s1",
  * "s2"]`.
  *
  * @tparam S1
  *   the first string literal type
  * @tparam S2
  *   the second string literal type
  */
type Path2[S1 <: String, S2 <: String] = S1 *: S2 *: EmptyTuple

/** Type alias for a single-entry schema tuple.
  *
  * Simplifies common pattern `Entry["name", K, V] *: EmptyTuple` to
  * `EntryTuple["name", K, V]`.
  *
  * @tparam N
  *   the table name
  * @tparam K
  *   the key type
  * @tparam V
  *   the value type
  */
type EntryTuple[N <: String, K, V] = Entry[N, K, V] *: EmptyTuple

/** Type-level mapping from an Entry to its corresponding StateTable type.
  *
  * This match type converts schema entries (Entry[Name, K, V]) into their
  * runtime StateTable implementations with matching type parameters.
  *
  * @tparam F
  *   the effect type
  * @tparam E
  *   the entry type
  */
type TableOf[F[_], E] = E match
  case Entry[name, k, v] =>
    StateTable[F] { type Name = name; type K = k; type V = v }

/** Type-level mapping from a schema tuple to a tuple of StateTables.
  *
  * Applies TableOf to each entry in the schema tuple, producing a tuple of
  * corresponding StateTable instances.
  *
  * Example:
  * {{{
  *   type Schema = (Entry["users", String, User], Entry["posts", String, Post])
  *   type Result = Tables[IO, Schema]
  *   // Result = (StateTable[IO]{Name="users", K=String, V=User},
  *   //           StateTable[IO]{Name="posts", K=String, V=Post})
  * }}}
  *
  * @tparam F
  *   the effect type
  * @tparam Schema
  *   the schema tuple (tuple of Entry types)
  */
type Tables[F[_], Schema <: Tuple] = Tuple.Map[Schema, [E] =>> TableOf[F, E]]
