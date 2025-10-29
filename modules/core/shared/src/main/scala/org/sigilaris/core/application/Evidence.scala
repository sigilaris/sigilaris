package org.sigilaris.core
package application

/** Evidence that all entries in Needs are present in Schema.
  *
  * This typeclass proves at compile-time that a subset relationship holds:
  * every table required by a transaction (Needs) must exist in the module's
  * schema (Schema).
  *
  * @tparam Needs
  *   the required tables (typically from Tx#Reads or Tx#Writes)
  * @tparam Schema
  *   the available tables in the module
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
  |  - Check that Entry names match between transaction and schema definitions""",
)
trait Requires[Needs <: Tuple, Schema <: Tuple]

object Requires:
  /** EmptyTuple requires nothing from any schema. */
  given emptyRequires[Schema <: Tuple]: Requires[EmptyTuple, Schema] =
    new Requires[EmptyTuple, Schema] {}

  /** Inductive case: if head H is in Schema and tail T is satisfied, then H *:
    * T is satisfied.
    */
  given consRequires[H, T <: Tuple, Schema <: Tuple](using
      headIn: Contains[H, Schema],
      tailRequires: Requires[T, Schema],
  ): Requires[H *: T, Schema] =
    new Requires[H *: T, Schema] {}

/** Evidence that element E is contained in tuple T.
  *
  * @tparam E
  *   the element type
  * @tparam T
  *   the tuple type
  */
trait Contains[E, T <: Tuple]

object Contains:
  /** The element is at the head of the tuple. */
  given head[E, Tail <: Tuple]: Contains[E, E *: Tail] =
    new Contains[E, E *: Tail] {}

  /** The element is in the tail of the tuple. */
  given tail[E, H, Tail <: Tuple](using
      Contains[E, Tail],
  ): Contains[E, H *: Tail] =
    new Contains[E, H *: Tail] {}

/** Evidence that all table names in Schema are unique.
  *
  * This ensures no two tables in a module have the same name, preventing prefix
  * collisions.
  *
  * @tparam Schema
  *   the schema tuple
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
  |  - Consider using different table names or mounting modules at different paths""",
)
trait UniqueNames[Schema <: Tuple]

object UniqueNames:
  /** EmptyTuple trivially has unique names. */
  given emptyUnique: UniqueNames[EmptyTuple] =
    new UniqueNames[EmptyTuple] {}

  /** Single entry trivially has unique names. */
  given singleUnique[Name <: String, K, V]
      : UniqueNames[EntryTuple[Name, K, V]] =
    new UniqueNames[EntryTuple[Name, K, V]] {}

  /** Inductive case: if head name is not in tail and tail is unique, then head
    * *: tail is unique.
    *
    * This implementation requires:
    *   1. The tail has unique names (recursive) 2. The head name does not
    *      appear in the tail (NameNotInSchema)
    */
  given consUnique[Name <: String, K, V, Tail <: Tuple](using
      tailUnique: UniqueNames[Tail],
      notInTail: NameNotInSchema[Name, Tail],
  ): UniqueNames[Entry[Name, K, V] *: Tail] =
    new UniqueNames[Entry[Name, K, V] *: Tail] {}

/** Evidence that a table name does not appear in a schema.
  *
  * This is used to enforce uniqueness of table names.
  *
  * @tparam Name
  *   the table name to check
  * @tparam Schema
  *   the schema to search
  */
@scala.annotation.implicitNotFound(
  """Table name "${Name}" already exists in the schema.
  |Schema: ${Schema}
  |
  |This is a duplicate table name, which would cause prefix collisions.
  |
  |To fix:
  |  - Use a different table name
  |  - Check if you're composing modules with conflicting table names""",
)
trait NameNotInSchema[Name <: String, Schema <: Tuple]

object NameNotInSchema:
  /** EmptyTuple does not contain any name. */
  given empty[Name <: String]: NameNotInSchema[Name, EmptyTuple] =
    new NameNotInSchema[Name, EmptyTuple] {}

  /** If the head name is different and tail doesn't contain the name, then the
    * name is not in the schema.
    *
    * We use DifferentNames to ensure the name doesn't match the head, and
    * recursively check the tail.
    */
  given cons[Name <: String, OtherName <: String, K, V, Tail <: Tuple](using
      namesDifferent: DifferentNames[Name, OtherName],
      notInTail: NameNotInSchema[Name, Tail],
  ): NameNotInSchema[Name, Entry[OtherName, K, V] *: Tail] =
    new NameNotInSchema[Name, Entry[OtherName, K, V] *: Tail] {}

/** Evidence that two table names are different.
  *
  * This is a type alias for NotGiven that provides semantic clarity in error
  * messages. While we cannot override NotGiven's @implicitNotFound message,
  * having "DifferentNames" in the error trace makes it clearer what constraint
  * failed.
  *
  * The actual validation happens through NotGiven, which will show: "But no
  * implicit values were found that match type scala.util.NotGiven[...]"
  *
  * This is still valuable because:
  *   1. Error messages show "DifferentNames[Name1, Name2]" in the trace 2. Code
  *      is more self-documenting (DifferentNames vs raw NotGiven) 3. Future
  *      Scala versions might allow better customization
  *
  * @tparam Name1
  *   the first table name
  * @tparam Name2
  *   the second table name
  */
type DifferentNames[Name1 <: String, Name2 <: String] =
  scala.util.NotGiven[Name1 =:= Name2]

/** Evidence that all table prefixes in Schema are prefix-free when mounted at
  * Path.
  *
  * This ensures that no table's full key prefix (encodePath(Path) ++
  * encodeSegment(Name)) is a prefix of another table's prefix, preventing key
  * space collisions.
  *
  * @tparam Path
  *   the mount path tuple
  * @tparam Schema
  *   the schema tuple
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
  |  - Check that path + table name combinations are unique""",
)
trait PrefixFreePath[Path <: Tuple, Schema <: Tuple]

object PrefixFreePath:
  /** EmptyTuple trivially has no prefix collisions. */
  given emptyPrefixFree[Path <: Tuple]: PrefixFreePath[Path, EmptyTuple] =
    new PrefixFreePath[Path, EmptyTuple] {}

  /** Single entry trivially has no prefix collisions. */
  given singlePrefixFree[Path <: Tuple, Name <: String, K, V]
      : PrefixFreePath[Path, EntryTuple[Name, K, V]] =
    new PrefixFreePath[Path, EntryTuple[Name, K, V]] {}

  /** Inductive case: if head prefix doesn't collide with tail prefixes and tail
    * is prefix-free, then head *: tail is prefix-free.
    *
    * This implementation requires UniqueNames evidence, which ensures that
    * table names are distinct. With length-prefix encoding, distinct names
    * guarantee prefix-free prefixes.
    */
  given consPrefixFree[
      Path <: Tuple,
      Name <: String,
      K,
      V,
      Tail <: Tuple,
      Schema <: Tuple,
  ](using
      tailPrefixFree: PrefixFreePath[Path, Tail],
      uniqueNames: UniqueNames[Entry[Name, K, V] *: Tail],
  ): PrefixFreePath[Path, Entry[Name, K, V] *: Tail] =
    new PrefixFreePath[Path, Entry[Name, K, V] *: Tail] {}

/** Evidence for looking up a table by name in a schema.
  *
  * This typeclass provides compile-time schema lookup, extracting the key/value
  * types and runtime table instance for a named table. This enables
  * cross-module access where a reducer can depend on tables from other modules
  * in the composed schema.
  *
  * The Lookup instance carries the type information (K0, V0) as type
  * parameters, and the table method returns a StateTable with those exact types
  * preserved, enabling compile-time type-safe access with branded keys.
  *
  * @tparam S
  *   the schema tuple (tuple of Entry types)
  * @tparam Name
  *   the table name to lookup (literal String type)
  * @tparam K0
  *   the key type of the named table
  * @tparam V0
  *   the value type of the named table
  *
  * @example
  *   {{{
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
  *   }}}
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
  |  - Check the Lookup type parameters match the Entry definition""",
)
trait Lookup[S <: Tuple, Name0 <: String, K0, V0]:
  /** Extract the concrete StateTable instance from a Tables tuple.
    *
    * The returned table has its Name, K, and V types precisely matching the
    * type parameters of this Lookup instance, enabling compile-time type-safe
    * operations with branded keys.
    *
    * @param tables
    *   the tuple of StateTable instances corresponding to schema S
    * @tparam F
    *   the effect type
    * @return
    *   the StateTable for the named table with exact types Name=Name0, K=K0,
    *   V=V0
    */
  def table[F[_]](
      tables: Tables[F, S],
  ): StateTable[F] { type Name = Name0; type K = K0; type V = V0 }

object Lookup:
  /** Base case: the named table is at the head of the schema. */
  given head[Name0 <: String, K0, V0, Tail <: Tuple]
      : Lookup[Entry[Name0, K0, V0] *: Tail, Name0, K0, V0] =
    new Lookup[Entry[Name0, K0, V0] *: Tail, Name0, K0, V0]:
      def table[F[_]](
          tables: Tables[F, Entry[Name0, K0, V0] *: Tail],
      ): StateTable[F] { type Name = Name0; type K = K0; type V = V0 } =
        // The head of Tables[F, Entry[Name0, K0, V0] *: Tail] is guaranteed to be
        // StateTable[F] { type Name = Name0; type K = K0; type V = V0 }
        // by the definition of Tables and TableOf
        tables.head

  /** Inductive case: the named table is in the tail of the schema. */
  given tail[Name0 <: String, K0, V0, H, Tail <: Tuple](using
      tailLookup: Lookup[Tail, Name0, K0, V0],
  ): Lookup[H *: Tail, Name0, K0, V0] =
    new Lookup[H *: Tail, Name0, K0, V0]:
      def table[F[_]](
          tables: Tables[F, H *: Tail],
      ): StateTable[F] { type Name = Name0; type K = K0; type V = V0 } =
        // Recursively lookup in tail - the type is preserved through the recursive call
        tailLookup.table[F](tables.tail)
