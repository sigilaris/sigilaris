package org.sigilaris.core
package application

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
  def empty[F[_]]: TablesProvider[F, EmptyTuple] = new TablesProvider[F, EmptyTuple]:
    def tables: Tables[F, EmptyTuple] = EmptyTuple

  /** Create a provider from a mounted module's tables.
    *
    * This helper derives a TablesProvider from a mounted StateModule, enabling
    * one module to supply tables to another module's dependencies.
    *
    * This is useful for wiring dependencies when composing modules:
    *   1. Mount the provider module (e.g., Accounts)
    *   2. Extract a TablesProvider from it
    *   3. Pass the provider to dependent modules (e.g., Group, Token)
    *
    * IMPORTANT: The provider captures a reference to the module's tables at
    * the time of creation. If the module is extended or modified later, the
    * provider will not reflect those changes.
    *
    * LIMITATION (Phase 5.5): This creates a provider for the FULL schema.
    * Use `narrow` to project to a subset if the dependent module only needs
    * some of the tables. This prevents breaking changes when adding tables
    * to the provider module.
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
  def fromModule[F[_], Path <: Tuple, Schema <: Tuple, Txs <: Tuple, Deps <: Tuple, R](
      module: StateModule[F, Path, Schema, Txs, Deps, R],
  ): TablesProvider[F, Schema] = new TablesProvider[F, Schema]:
    def tables: Tables[F, Schema] = module.tables

  /** Narrow a provider to a subset of its tables.
    *
    * This extension method allows projecting a TablesProvider[F, Full] to
    * a TablesProvider[F, Subset] where Subset ⊆ Full.
    *
    * This is critical for avoiding breaking changes: dependent modules should
    * declare exactly which tables they need (Needs tuple), and use `narrow`
    * to extract only those tables from the provider module. Adding new tables
    * to the provider module won't break dependent modules.
    *
    * The Requires[Subset, Full] evidence ensures compile-time safety: you
    * can only narrow to a subset that actually exists in the full schema.
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
  extension [F[_], Full <: Tuple](provider: TablesProvider[F, Full])
    def narrow[Subset <: Tuple](using
        subset: Requires[Subset, Full],
        projection: TablesProjection[F, Subset, Full],
    ): TablesProvider[F, Subset] = new TablesProvider[F, Subset]:
      def tables: Tables[F, Subset] = projection.project(provider.tables)

  /** Type class for projecting a subset of tables from a full schema.
    *
    * This provides the runtime mechanism to extract Subset tables from
    * Full tables, guided by compile-time Requires evidence.
    *
    * Implementation uses Lookup evidence to extract each table from the
    * full schema based on table name and types.
    *
    * @tparam F
    *   the effect type
    * @tparam Subset
    *   the subset schema tuple
    * @tparam Full
    *   the full schema tuple
    */
  trait TablesProjection[F[_], Subset <: Tuple, Full <: Tuple]:
    /** Project subset tables from full tables.
      *
      * @param fullTables
      *   the full tables tuple
      * @return
      *   the subset tables tuple
      */
    def project(fullTables: Tables[F, Full]): Tables[F, Subset]

  object TablesProjection:
    /** Base case: projecting EmptyTuple from any schema yields EmptyTuple. */
    given emptyProjection[F[_], Full <: Tuple]: TablesProjection[F, EmptyTuple, Full] with
      def project(fullTables: Tables[F, Full]): Tables[F, EmptyTuple] = EmptyTuple

    /** Inductive case: project head entry and recurse on tail.
      *
      * For a subset Entry[Name, K, V] *: Tail, we:
      *   1. Use Lookup to extract the table for Entry[Name, K, V] from full schema
      *   2. Recursively project the Tail subset
      *   3. Cons them together
      */
    given consProjection[F[_], Name <: String, K, V, Tail <: Tuple, Full <: Tuple](using
        headLookup: Lookup[Full, Name, K, V],
        tailProjection: TablesProjection[F, Tail, Full],
    ): TablesProjection[F, Entry[Name, K, V] *: Tail, Full] with
      def project(fullTables: Tables[F, Full]): Tables[F, Entry[Name, K, V] *: Tail] =
        // Lookup.table returns StateTable[F] { type Name = Name; type K = K; type V = V }
        // which is exactly what we need for TableOf[F, Entry[Name, K, V]]
        val headTable = headLookup.table(fullTables)
        val tailTables = tailProjection.project(fullTables)
        headTable *: tailTables
