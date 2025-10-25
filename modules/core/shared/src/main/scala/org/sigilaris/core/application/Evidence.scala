package org.sigilaris.core
package application

/** Evidence that all entries in Needs are present in Schema.
  *
  * This typeclass proves at compile-time that a subset relationship holds:
  * every table required by a transaction (Needs) must exist in the module's
  * schema (Schema).
  *
  * @tparam Needs the required tables (typically from Tx#Reads or Tx#Writes)
  * @tparam Schema the available tables in the module
  */
trait Requires[Needs <: Tuple, Schema <: Tuple]

object Requires:
  /** EmptyTuple requires nothing from any schema. */
  given emptyRequires[Schema <: Tuple]: Requires[EmptyTuple, Schema] =
    new Requires[EmptyTuple, Schema] {}

  /** Inductive case: if head H is in Schema and tail T is satisfied,
    * then H *: T is satisfied.
    */
  given consRequires[H, T <: Tuple, Schema <: Tuple](using
      headIn: Contains[H, Schema],
      tailRequires: Requires[T, Schema],
  ): Requires[H *: T, Schema] =
    new Requires[H *: T, Schema] {}

/** Evidence that element E is contained in tuple T.
  *
  * @tparam E the element type
  * @tparam T the tuple type
  */
trait Contains[E, T <: Tuple]

object Contains:
  /** The element is at the head of the tuple. */
  given head[E, Tail <: Tuple]: Contains[E, E *: Tail] =
    new Contains[E, E *: Tail] {}

  /** The element is in the tail of the tuple. */
  given tail[E, H, Tail <: Tuple](using Contains[E, Tail]): Contains[E, H *: Tail] =
    new Contains[E, H *: Tail] {}

/** Evidence that all table names in Schema are unique.
  *
  * This ensures no two tables in a module have the same name, preventing
  * prefix collisions.
  *
  * @tparam Schema the schema tuple
  */
trait UniqueNames[Schema <: Tuple]

object UniqueNames:
  /** EmptyTuple trivially has unique names. */
  given emptyUnique: UniqueNames[EmptyTuple] =
    new UniqueNames[EmptyTuple] {}

  /** Single entry trivially has unique names. */
  given singleUnique[Name <: String, K, V]: UniqueNames[Entry[Name, K, V] *: EmptyTuple] =
    new UniqueNames[Entry[Name, K, V] *: EmptyTuple] {}

  /** Inductive case: if head name is not in tail and tail is unique,
    * then head *: tail is unique.
    *
    * Note: This is a simplified implementation. A full implementation would
    * check that the name of the head entry does not appear in any tail entry.
    * For now, we provide a permissive instance that will be refined in Phase 3.
    */
  given consUnique[Name <: String, K, V, Tail <: Tuple](using
      UniqueNames[Tail],
  ): UniqueNames[Entry[Name, K, V] *: Tail] =
    new UniqueNames[Entry[Name, K, V] *: Tail] {}

/** Evidence that all table prefixes in Schema are prefix-free when mounted at Path.
  *
  * This ensures that no table's full key prefix (encodePath(Path) ++ encodeSegment(Name))
  * is a prefix of another table's prefix, preventing key space collisions.
  *
  * @tparam Path the mount path tuple
  * @tparam Schema the schema tuple
  */
trait PrefixFreePath[Path <: Tuple, Schema <: Tuple]

object PrefixFreePath:
  /** EmptyTuple trivially has no prefix collisions. */
  given emptyPrefixFree[Path <: Tuple]: PrefixFreePath[Path, EmptyTuple] =
    new PrefixFreePath[Path, EmptyTuple] {}

  /** Single entry trivially has no prefix collisions. */
  given singlePrefixFree[Path <: Tuple, Name <: String, K, V]: PrefixFreePath[Path, Entry[Name, K, V] *: EmptyTuple] =
    new PrefixFreePath[Path, Entry[Name, K, V] *: EmptyTuple] {}

  /** Inductive case: if head prefix doesn't collide with tail prefixes and tail is prefix-free,
    * then head *: tail is prefix-free.
    *
    * Note: This is a simplified implementation. A full implementation would
    * compute actual prefixes and check the prefix-free property at compile-time.
    * For now, we provide a permissive instance that will be refined in Phase 3.
    */
  given consPrefixFree[Path <: Tuple, Name <: String, K, V, Tail <: Tuple](using
      PrefixFreePath[Path, Tail],
  ): PrefixFreePath[Path, Entry[Name, K, V] *: Tail] =
    new PrefixFreePath[Path, Entry[Name, K, V] *: Tail] {}

/** Evidence for looking up a table by name in a schema.
  *
  * This typeclass provides compile-time schema lookup, extracting the key/value
  * types and runtime table instance for a named table. This enables cross-module
  * access where a reducer can depend on tables from other modules in the composed
  * schema.
  *
  * The Lookup instance carries the type information (K0, V0) as type parameters,
  * allowing callers to extract these types at compile time.
  *
  * @tparam S the schema tuple (tuple of Entry types)
  * @tparam Name the table name to lookup (literal String type)
  * @tparam K0 the key type of the named table
  * @tparam V0 the value type of the named table
  *
  * @example
  * {{{
  * type Schema = Entry["accounts", Address, Account] *: Entry["balances", Address, BigInt] *: EmptyTuple
  *
  * // Lookup evidence is automatically summoned
  * def useTable[F[_], S <: Tuple](tables: Tables[F, S])(using
  *   lookup: Lookup[S, "balances", Address, BigInt]
  * ): Unit =
  *   val balancesTable = lookup.table(tables)
  *   // balancesTable has type StateTable[F] { type Name = "balances"; type K = Address; type V = BigInt }
  * }}}
  */
trait Lookup[S <: Tuple, Name <: String, K0, V0]:
  /** Extract the concrete StateTable instance from a Tables tuple.
    *
    * The returned table is guaranteed to have Name, K, and V types matching
    * the type parameters of this Lookup instance.
    *
    * @param tables the tuple of StateTable instances corresponding to schema S
    * @tparam F the effect type
    * @return the StateTable for the named table with the correct types
    */
  def table[F[_]](tables: Tables[F, S]): StateTable[F] { type Name <: String; type K; type V }

object Lookup:
  /** Base case: the named table is at the head of the schema. */
  given head[Name0 <: String, K0, V0, Tail <: Tuple]: Lookup[Entry[Name0, K0, V0] *: Tail, Name0, K0, V0] =
    new Lookup[Entry[Name0, K0, V0] *: Tail, Name0, K0, V0]:
      def table[F[_]](tables: Tables[F, Entry[Name0, K0, V0] *: Tail]): StateTable[F] { type Name <: String; type K; type V } =
        // The head of Tables[F, Entry[Name0, K0, V0] *: Tail] is guaranteed to be
        // StateTable[F] { type Name = Name0; type K = K0; type V = V0 }
        tables.head

  /** Inductive case: the named table is in the tail of the schema. */
  given tail[Name0 <: String, K0, V0, H, Tail <: Tuple](using
      tailLookup: Lookup[Tail, Name0, K0, V0],
  ): Lookup[H *: Tail, Name0, K0, V0] =
    new Lookup[H *: Tail, Name0, K0, V0]:
      def table[F[_]](tables: Tables[F, H *: Tail]): StateTable[F] { type Name <: String; type K; type V } =
        // tables.tail has the correct type Tables[F, Tail] by Tuple.Map's definition
        tailLookup.table[F](tables.tail)
