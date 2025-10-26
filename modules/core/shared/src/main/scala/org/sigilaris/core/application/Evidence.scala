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
@scala.annotation.implicitNotFound(
  """Cannot prove that all required tables are in the schema.
  |Required tables: ${Needs}
  |Available schema: ${Schema}
  |
  |Possible causes:
  |  - Transaction requires a table that doesn't exist in the module
  |  - Table names don't match exactly (check spelling and case)
  |  - Module hasn't been composed with the required dependency module
  |
  |To fix:
  |  - Ensure all tables in Reads/Writes are defined in the module's schema
  |  - If using cross-module dependencies, compose modules with extend or composeBlueprint
  |  - Check that Entry names match between transaction and schema definitions"""
)
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
@scala.annotation.implicitNotFound(
  """Cannot prove that all table names in the schema are unique.
  |Schema: ${Schema}
  |
  |Possible causes:
  |  - Two or more tables have the same name
  |  - Module composition includes duplicate table names from different modules
  |
  |To fix:
  |  - Ensure each table in the schema has a unique name
  |  - When composing modules, check for name conflicts
  |  - Consider using different table names or mounting modules at different paths"""
)
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
@scala.annotation.implicitNotFound(
  """Cannot prove that all table prefixes are prefix-free at the given path.
  |Path: ${Path}
  |Schema: ${Schema}
  |
  |Possible causes:
  |  - One table's prefix is a prefix of another table's prefix
  |  - Path segments or table names create prefix collisions
  |  - Module composition creates overlapping key spaces
  |
  |To fix:
  |  - Choose table names that don't create prefix relationships
  |  - Mount modules at different paths to avoid collisions
  |  - Check that path + table name combinations are unique"""
)
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
  * and the table method returns a StateTable with those exact types preserved,
  * enabling compile-time type-safe access with branded keys.
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
  *   val key = balancesTable.brand(Address(123))  // Compiles: K is known to be Address
  *   balancesTable.get(key)  // Type-safe access
  * }}}
  */
@scala.annotation.implicitNotFound(
  """Cannot find table "${Name0}" with key type ${K0} and value type ${V0} in schema.
  |Schema: ${S}
  |Looking for: Entry["${Name0}", ${K0}, ${V0}]
  |
  |Possible causes:
  |  - Table "${Name0}" doesn't exist in the schema
  |  - Table exists but with different key/value types
  |  - Table name doesn't match exactly (check spelling and case)
  |  - Module hasn't been composed with the module containing this table
  |
  |To fix:
  |  - Check that the table is defined in the module's schema
  |  - Verify the key and value types match exactly
  |  - If the table is in a dependency module, ensure modules are composed with extend
  |  - Check the Lookup type parameters match the Entry definition"""
)
trait Lookup[S <: Tuple, Name0 <: String, K0, V0]:
  /** Extract the concrete StateTable instance from a Tables tuple.
    *
    * The returned table has its Name, K, and V types precisely matching
    * the type parameters of this Lookup instance, enabling compile-time
    * type-safe operations with branded keys.
    *
    * @param tables the tuple of StateTable instances corresponding to schema S
    * @tparam F the effect type
    * @return the StateTable for the named table with exact types Name=Name0, K=K0, V=V0
    */
  def table[F[_]](tables: Tables[F, S]): StateTable[F] { type Name = Name0; type K = K0; type V = V0 }

object Lookup:
  /** Base case: the named table is at the head of the schema. */
  given head[Name0 <: String, K0, V0, Tail <: Tuple]: Lookup[Entry[Name0, K0, V0] *: Tail, Name0, K0, V0] =
    new Lookup[Entry[Name0, K0, V0] *: Tail, Name0, K0, V0]:
      def table[F[_]](tables: Tables[F, Entry[Name0, K0, V0] *: Tail]): StateTable[F] { type Name = Name0; type K = K0; type V = V0 } =
        // The head of Tables[F, Entry[Name0, K0, V0] *: Tail] is guaranteed to be
        // StateTable[F] { type Name = Name0; type K = K0; type V = V0 }
        // by the definition of Tables and TableOf
        tables.head

  /** Inductive case: the named table is in the tail of the schema. */
  given tail[Name0 <: String, K0, V0, H, Tail <: Tuple](using
      tailLookup: Lookup[Tail, Name0, K0, V0],
  ): Lookup[H *: Tail, Name0, K0, V0] =
    new Lookup[H *: Tail, Name0, K0, V0]:
      def table[F[_]](tables: Tables[F, H *: Tail]): StateTable[F] { type Name = Name0; type K = K0; type V = V0 } =
        // Recursively lookup in tail - the type is preserved through the recursive call
        tailLookup.table[F](tables.tail)
