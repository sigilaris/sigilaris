package org.sigilaris.core
package application

import scala.Tuple.++

/** Provider for external table dependencies.
  *
  * TablesProvider is the canonical handle for supplying tables that a module
  * needs but does not own. The name "Provider" highlights what it supplies,
  * while blueprints declare what they need via the `Needs` type parameter.
  *
  * This is the core abstraction introduced in Phase 5.5 to replace nested
  * dependency tuples with a clean dependency injection model.
  *
  * Key design principles:
  *   - Provides is a tuple of Entry types (Entry[Name, K, V] *: ...)
  *   - At runtime, provides a Tables[F, Provides] instance
  *   - Blueprints declare Needs (also an Entry tuple) and receive a matching
  *     TablesProvider[F, Needs]
  *   - Compile-time evidence ensures type safety between Needs and Provides
  *
  * @tparam F
  *   the effect type
  * @tparam Provides
  *   the tuple of Entry types this provider supplies
  *
  * @example
  *   {{{
  * // A module that provides accounts and balances tables
  * val accountsProvider: TablesProvider[F, AccountsSchema] = ???
  *
  * // A module that needs accounts tables
  * type GroupNeeds = Entry["accounts", Address, Account] *: EmptyTuple
  * val groupBP = new ModuleBlueprint[F, "group", GroupOwns, GroupNeeds, ...](
  *   owns = groupEntries,
  *   reducer0 = ...,
  *   txs = ...,
  *   provider = accountsProvider  // Injected at construction
  * )
  *   }}}
  */
trait TablesProvider[F[_], Provides <: Tuple]:
  /** Get the provided tables.
    *
    * @return
    *   a tuple of StateTable instances matching the Provides schema
    */
  def tables: Tables[F, Provides]

  /** Narrow this provider to a subset of its schema.
    *
    * This is critical for Phase 5.6 provider composition. When a merged
    * provider (N1 ++ N2) is passed to a reducer expecting only N1, we must
    * actually project the tables tuple, not just cast the type.
    *
    * Without projection, pattern matching fails: val (accountsTable *:
    * EmptyTuple) = provider.tables // MatchError!
    *
    * @tparam Subset
    *   the subset schema to project to
    * @param projection
    *   evidence that Subset can be projected from Provides
    * @return
    *   a provider supplying only the Subset tables (actually projected)
    */
  def narrow[Subset <: Tuple](using
      projection: TablesProjection[F, Subset, Provides],
  ): TablesProvider[F, Subset] = new TablesProvider[F, Subset]:
    def tables: Tables[F, Subset] =
      projection.project(TablesProvider.this.tables)

/** Typeclass proving that Subset can be extracted from Source schema.
  *
  * TablesProjection[F, Subset, Source] proves that every entry in Subset exists
  * in Source with matching name, key, and value types. It provides a project
  * method that extracts the subset tables from a full tables tuple.
  *
  * This is the key abstraction for provider narrowing in Phase 5.6.
  *
  * @tparam F
  *   the effect type
  * @tparam Subset
  *   the subset schema to extract
  * @tparam Source
  *   the source schema to extract from
  */
trait TablesProjection[F[_], Subset <: Tuple, Source <: Tuple]:
  /** Project the source tables to the subset.
    *
    * @param sourceTables
    *   the full tables tuple
    * @return
    *   the projected subset tables
    */
  def project(sourceTables: Tables[F, Source]): Tables[F, Subset]

/** Low-priority implicits for TablesProjection to avoid ambiguity. */
private[application] trait TablesProjectionLowPriority:
  /** Base case: empty subset can be extracted from any source. */
  given emptyProjection[F[_], Source <: Tuple]
      : TablesProjection[F, EmptyTuple, Source] with
    def project(sourceTables: Tables[F, Source]): Tables[F, EmptyTuple] =
      EmptyTuple

  /** Inductive case: project a non-empty subset using Lookup.
    *
    * To project (Entry[Name, K, V] *: RestSubset) from Source:
    *   1. Use Lookup to find Entry[Name, K, V] in Source 2. Recursively project
    *      RestSubset from Source 3. Cons the results together
    */
  given consProjection[F[
      _,
  ], Name <: String, K, V, RestSubset <: Tuple, Source <: Tuple](using
      lookup: Lookup[Source, Name, K, V],
      restProjection: TablesProjection[F, RestSubset, Source],
  ): TablesProjection[F, Entry[Name, K, V] *: RestSubset, Source] with
    def project(
        sourceTables: Tables[F, Source],
    ): Tables[F, Entry[Name, K, V] *: RestSubset] =
      val headTable  = lookup.table(sourceTables)
      val restTables = restProjection.project(sourceTables)
      headTable *: restTables

object TablesProjection extends TablesProjectionLowPriority:
  /** Identity case: a schema is trivially a subset of itself.
    *
    * This is highest priority and covers all identity cases including
    * EmptyTuple.
    */
  given identityProjection[F[_], S <: Tuple]: TablesProjection[F, S, S] with
    def project(sourceTables: Tables[F, S]): Tables[F, S] = sourceTables

  /** Prefix case: Non-empty S is a subset of S ++ T (left side of
    * concatenation).
    *
    * We require that S is non-empty (Head *: Tail) to avoid ambiguity with
    * identity when S = EmptyTuple.
    */
  given prefixProjection[F[_], Head, Tail <: Tuple, T <: Tuple](using
      sizeS: ValueOf[Tuple.Size[Head *: Tail]],
  ): TablesProjection[F, Head *: Tail, (Head *: Tail) ++ T] with
    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def project(
        sourceTables: Tables[F, (Head *: Tail) ++ T],
    ): Tables[F, Head *: Tail] =
      // SAFETY: (Head *: Tail) ++ T = concatenated tuple.
      // We take the first sizeS elements.
      sourceTables.take(sizeS.value).asInstanceOf[Tables[F, Head *: Tail]]

  /** Suffix case: Non-empty T is a subset of S ++ T (right side of
    * concatenation).
    *
    * We require that T is non-empty (Head *: Tail) to avoid ambiguity.
    */
  given suffixProjection[F[_], S <: Tuple, Head, Tail <: Tuple](using
      sizeS: ValueOf[Tuple.Size[S]],
  ): TablesProjection[F, Head *: Tail, S ++ (Head *: Tail)] with
    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def project(
        sourceTables: Tables[F, S ++ (Head *: Tail)],
    ): Tables[F, Head *: Tail] =
      // SAFETY: S ++ (Head *: Tail) = concatenated tuple.
      // We skip the first sizeS elements.
      sourceTables.drop(sizeS.value).asInstanceOf[Tables[F, Head *: Tail]]

object TablesProvider:
  /** Empty provider for modules with no external dependencies.
    *
    * This is the default provider used when Needs = EmptyTuple. It provides
    * backward compatibility with existing Phase 5 code that doesn't use
    * external dependencies.
    *
    * @tparam F
    *   the effect type
    * @return
    *   a provider that supplies no tables
    *
    * @example
    *   {{{
    * // Self-contained module with no dependencies
    * val accountsBP = new ModuleBlueprint[F, "accounts", AccountsOwns, EmptyTuple, ...](
    *   owns = accountsEntries,
    *   reducer0 = accountsReducer,
    *   txs = accountsTxRegistry,
    *   provider = TablesProvider.empty[F]  // No external dependencies
    * )
    *   }}}
    */
  def empty[F[_]]: TablesProvider[F, EmptyTuple] =
    new TablesProvider[F, EmptyTuple]:
      def tables: Tables[F, EmptyTuple] = EmptyTuple

  /** Create a provider from a mounted module's tables.
    *
    * This helper derives a TablesProvider from a mounted StateModule, enabling
    * one module to supply tables to another module's dependencies.
    *
    * This is useful for wiring dependencies when composing modules:
    *   1. Mount the provider module (e.g., Accounts) 2. Extract a
    *      TablesProvider from it 3. Pass the provider to dependent modules
    *      (e.g., Group, Token)
    *
    * IMPORTANT: The provider captures a reference to the module's tables at the
    * time of creation. If the module is extended or modified later, the
    * provider will not reflect those changes.
    *
    * LIMITATION (Phase 5.5): This creates a provider for the FULL schema. Use
    * `narrow` to project to a subset if the dependent module only needs some of
    * the tables. This prevents breaking changes when adding tables to the
    * provider module.
    *
    * @tparam F
    *   the effect type
    * @tparam Path
    *   the mount path
    * @tparam Schema
    *   the schema tuple
    * @tparam Txs
    *   the transaction types
    * @tparam Deps
    *   the dependencies
    * @tparam R
    *   the reducer type
    * @param module
    *   the mounted module to extract tables from
    * @return
    *   a provider that supplies this module's tables
    *
    * @example
    *   {{{
    * // Mount accounts module
    * val accountsModule = StateModule.mount[("app", "accounts")](accountsBP)
    *
    * // Create provider from mounted module (full schema)
    * val accountsProviderFull = TablesProvider.fromModule(accountsModule)
    *
    * // Narrow to subset for dependent module
    * type GroupNeeds = Entry["accounts", Address, Account] *: EmptyTuple
    * val accountsProviderNarrow = accountsProviderFull.narrow[GroupNeeds]
    *
    * // Use narrowed provider in dependent module
    * val groupBP = new ModuleBlueprint[F, "group", GroupOwns, GroupNeeds, ...](
    *   owns = groupEntries,
    *   reducer0 = groupReducer,
    *   txs = groupTxRegistry,
    *   provider = accountsProviderNarrow  // Inject only needed tables
    * )
    *   }}}
    */
  def fromModule[F[
      _,
  ], Path <: Tuple, Schema <: Tuple, Txs <: Tuple, Deps <: Tuple, R](
      module: StateModule[F, Path, Schema, Txs, Deps, R],
  ): TablesProvider[F, Schema] = new TablesProvider[F, Schema]:
    def tables: Tables[F, Schema] = module.tables

  /** Narrow a provider to a subset of its tables.
    *
    * This extension method allows projecting a TablesProvider[F, Full] to a
    * TablesProvider[F, Subset] where Subset ⊆ Full.
    *
    * This is critical for avoiding breaking changes: dependent modules should
    * declare exactly which tables they need (Needs tuple), and use `narrow` to
    * extract only those tables from the provider module. Adding new tables to
    * the provider module won't break dependent modules.
    *
    * The Requires[Subset, Full] evidence ensures compile-time safety: you can
    * only narrow to a subset that actually exists in the full schema.
    *
    * @tparam F
    *   the effect type
    * @tparam Full
    *   the full schema tuple
    * @param provider
    *   the provider with full schema
    *
    * @example
    *   {{{
    * // Accounts module provides: accounts, balances, metadata
    * type AccountsSchema =
    *   Entry["accounts", Address, Account] *:
    *   Entry["balances", Address, BigNat] *:
    *   Entry["metadata", Address, Metadata] *:
    *   EmptyTuple
    *
    * val accountsProvider: TablesProvider[F, AccountsSchema] = ???
    *
    * // Group only needs accounts table
    * type GroupNeeds = Entry["accounts", Address, Account] *: EmptyTuple
    * val groupProvider = accountsProvider.narrow[GroupNeeds]
    *
    * // Token needs accounts and balances
    * type TokenNeeds =
    *   Entry["accounts", Address, Account] *:
    *   Entry["balances", Address, BigNat] *:
    *   EmptyTuple
    * val tokenProvider = accountsProvider.narrow[TokenNeeds]
    *
    * // Now adding metadata to AccountsSchema won't break Group or Token!
    *   }}}
    */

  /** Evidence that two schemas are disjoint (no overlapping entries).
    *
    * This is critical for Phase 5.6: when composing modules with non-empty
    * Needs, we must prove that their dependency schemas don't conflict. Two
    * schemas are disjoint if they have no Entry with the same Name/K/V types.
    *
    * This prevents ambiguous table lookups and ensures that merged providers
    * maintain type safety.
    *
    * @tparam S1
    *   the first schema tuple
    * @tparam S2
    *   the second schema tuple
    *
    * @example
    *   {{{
    * // These schemas are disjoint - no overlap
    * type S1 = Entry["accounts", Address, Account] *: EmptyTuple
    * type S2 = Entry["balances", Address, BigNat] *: EmptyTuple
    * summon[DisjointSchemas[S1, S2]]  // compiles
    *
    * // These schemas overlap - same name and types
    * type S3 = Entry["accounts", Address, Account] *: EmptyTuple
    * type S4 = Entry["accounts", Address, Account] *: EmptyTuple
    * summon[DisjointSchemas[S3, S4]]  // does not compile
    *   }}}
    */
  trait DisjointSchemas[S1 <: Tuple, S2 <: Tuple]

  object DisjointSchemas:
    /** Base case: EmptyTuple is disjoint from EmptyTuple (most specific). */
    given emptyEmpty: DisjointSchemas[EmptyTuple, EmptyTuple] =
      new DisjointSchemas[EmptyTuple, EmptyTuple] {}

    /** Base case: EmptyTuple is disjoint from any non-empty schema. */
    given emptyLeft[S <: Tuple]: DisjointSchemas[EmptyTuple, S] =
      new DisjointSchemas[EmptyTuple, S] {}

    /** Base case: Any non-empty schema is disjoint from EmptyTuple. */
    given emptyRight[S <: Tuple]: DisjointSchemas[S, EmptyTuple] =
      new DisjointSchemas[S, EmptyTuple] {}

    /** Inductive case: (H *: T1) is disjoint from S2 if:
      *   1. H is not in S2 (NotInSchema[H, S2]) 2. T1 is disjoint from S2
      *      (DisjointSchemas[T1, S2])
      */
    given consDisjoint[Name <: String, K, V, T1 <: Tuple, S2 <: Tuple](using
        notInS2: NotInSchema[Entry[Name, K, V], S2],
        tailDisjoint: DisjointSchemas[T1, S2],
    ): DisjointSchemas[Entry[Name, K, V] *: T1, S2] =
      new DisjointSchemas[Entry[Name, K, V] *: T1, S2] {}

  /** Evidence that an Entry is not present in a schema.
    *
    * This is used by DisjointSchemas to check overlap at the entry level. An
    * entry Entry[Name, K, V] is not in a schema if:
    *   - The schema is EmptyTuple, OR
    *   - The table name doesn't match AND the entry is not in the tail
    *
    * For Phase 5.6, we check table name uniqueness only. Two entries with the
    * same name are considered overlapping regardless of K/V types. This is
    * conservative but safe for preventing ambiguous lookups.
    *
    * @tparam E
    *   the entry to check for
    * @tparam S
    *   the schema to check in
    */
  trait NotInSchema[E <: Entry[?, ?, ?], S <: Tuple]

  object NotInSchema:
    /** Base case: any entry is not in EmptyTuple. */
    given notInEmpty[E <: Entry[?, ?, ?]]: NotInSchema[E, EmptyTuple] =
      new NotInSchema[E, EmptyTuple] {}

    /** Inductive case: Entry[N1, K1, V1] is not in (Entry[N2, K2, V2] *: Tail)
      * if:
      *   - Names differ (checked via DifferentNames / NotGiven), AND
      *   - The entry is not in Tail
      *
      * We use DifferentNames (which is NotGiven[N1 =:= N2]) to prove at compile
      * time that the table names are different.
      */
    given notInCons[N1 <: String, K1, V1, N2 <: String, K2, V2, Tail <: Tuple](
        using
        namesDiffer: DifferentNames[N1, N2],
        notInTail: NotInSchema[Entry[N1, K1, V1], Tail],
    ): NotInSchema[Entry[N1, K1, V1], Entry[N2, K2, V2] *: Tail] =
      new NotInSchema[Entry[N1, K1, V1], Entry[N2, K2, V2] *: Tail] {}

  /** Merge two disjoint providers into a single provider.
    *
    * This is the core operation for Phase 5.6: combining providers when
    * composing modules with non-empty Needs. The schemas must be disjoint (no
    * overlapping entries) to ensure unambiguous table lookups.
    *
    * The merged provider:
    *   - Provides tables from both P1 and P2
    *   - Can be narrowed to any subset of P1 ++ P2
    *   - Maintains type safety through DisjointSchemas evidence
    *
    * @tparam F
    *   the effect type
    * @tparam P1
    *   the first provider's schema
    * @tparam P2
    *   the second provider's schema
    * @param p1
    *   the first provider
    * @param p2
    *   the second provider
    * @param disjoint
    *   evidence that P1 and P2 are disjoint
    * @return
    *   a provider supplying P1 ++ P2
    *
    * @example
    *   {{{
    * // Accounts module provides accounts and balances
    * val accountsProvider: TablesProvider[F, AccountsSchema] = ???
    *
    * // Group module provides groups and members
    * val groupProvider: TablesProvider[F, GroupSchema] = ???
    *
    * // Merge providers (compiles only if schemas are disjoint)
    * val mergedProvider = TablesProvider.merge(accountsProvider, groupProvider)
    * // Type: TablesProvider[F, AccountsSchema ++ GroupSchema]
    *
    * // Can now be used by modules needing tables from both
    * val tokenBP = new ModuleBlueprint[F, "token", TokenOwns, AccountsSchema ++ GroupSchema, ...](
    *   owns = tokenEntries,
    *   reducer0 = tokenReducer,
    *   txs = tokenTxRegistry,
    *   provider = mergedProvider,
    * )
    *   }}}
    */
  def merge[F[_], P1 <: Tuple, P2 <: Tuple](
      p1: TablesProvider[F, P1],
      p2: TablesProvider[F, P2],
  )(using
      disjoint: DisjointSchemas[P1, P2],
  ): TablesProvider[F, P1 ++ P2] = new TablesProvider[F, P1 ++ P2]:
    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def tables: Tables[F, P1 ++ P2] =
      // SAFETY: Tuple concatenation at runtime matches type-level Tuple.Concat.
      // DisjointSchemas evidence ensures no ambiguity in table lookups.
      (p1.tables ++ p2.tables).asInstanceOf[Tables[F, P1 ++ P2]]
