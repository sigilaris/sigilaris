package org.sigilaris.core.application

/** Module system for blockchain application architecture.
  *
  * This package provides the core abstractions for building modular blockchain applications
  * with compile-time schema validation, type-safe table access, and automatic dependency injection.
  *
  * = Architecture Overview =
  *
  * The module system has two main phases:
  *   1. '''Blueprint Phase''' - Path-independent module specifications
  *   2. '''Runtime Phase''' - Path-bound modules with instantiated tables
  *
  * = Blueprint Types (Path-Independent) =
  *
  * '''[[org.sigilaris.core.application.module.blueprint.Blueprint]]''' - Base trait for all blueprints
  *   - Covariant in reducer type
  *   - Contains Entry instances (not StateTable instances)
  *   - Declares Owns and Needs schemas
  *
  * '''[[org.sigilaris.core.application.module.blueprint.ModuleBlueprint]]''' - Single module specification
  *   - Uses [[org.sigilaris.core.application.module.blueprint.StateReducer0]] (accepts any Tx)
  *   - Self-contained with owned tables and external dependencies
  *   - Can be mounted at any path
  *
  * '''[[org.sigilaris.core.application.module.blueprint.ComposedBlueprint]]''' - Combined modules
  *   - Uses [[org.sigilaris.core.application.module.blueprint.RoutedStateReducer0]] (requires ModuleRoutedTx)
  *   - Routes transactions based on moduleId.path
  *   - Created via [[org.sigilaris.core.application.module.blueprint.Blueprint.composeBlueprint]]
  *
  * = Runtime Types (Path-Bound) =
  *
  * '''[[org.sigilaris.core.application.module.runtime.StateModule]]''' - Deployed module
  *   - Generic in reducer type (StateReducer or RoutedStateReducer)
  *   - Contains instantiated StateTable instances with computed prefixes
  *   - Bound to specific Path at mount time
  *
  * '''[[org.sigilaris.core.application.module.runtime.StateReducer]]''' - Single module reducer
  *   - Path-bound version of StateReducer0
  *   - Accepts any transaction type T <: Tx
  *
  * '''[[org.sigilaris.core.application.module.runtime.RoutedStateReducer]]''' - Composed module reducer
  *   - Path-bound version of RoutedStateReducer0
  *   - Requires ModuleRoutedTx for routing
  *
  * = Provider System =
  *
  * '''[[org.sigilaris.core.application.module.provider.TablesProvider]]''' - Dependency injection
  *   - Supplies tables that a module needs but does not own
  *   - Enables cross-module table access
  *   - Created from mounted modules via `TablesProvider.fromModule`
  *
  * '''[[org.sigilaris.core.application.module.provider.TablesProjection]]''' - Schema subsetting
  *   - Projects a subset of tables from a larger schema
  *   - Enables provider narrowing for composed modules
  *   - Automatic derivation for common cases
  *
  * = Schema Instantiation =
  *
  * '''[[org.sigilaris.core.application.module.blueprint.SchemaMapper]]''' - Typeclass for table creation
  *   - Converts Entry[Name, K, V] to StateTable[F] instances
  *   - Computes table prefixes from Path and Name
  *   - Automatic derivation for tuple schemas
  *
  * '''[[org.sigilaris.core.application.module.blueprint.SchemaInstantiation]]''' - Convenience helpers
  *   - Wraps SchemaMapper for easier usage
  *   - Used internally during module mounting
  *
  * = Usage Examples =
  *
  * @example Creating a simple module blueprint:
  * ```scala
  * import org.sigilaris.core.application.module.*
  * import org.sigilaris.core.application.module.blueprint.*
  * import org.sigilaris.core.application.state.Entry
  * import org.sigilaris.core.assembly.EntrySyntax.*
  *
  * // Define schema
  * val accountsEntry = entry"accounts"[String, Account]
  * val balancesEntry = entry"balances"[String, BigInt]
  * val schema = accountsEntry *: balancesEntry *: EmptyTuple
  *
  * // Create reducer
  * val reducer = new StateReducer0[IO, MySchema, EmptyTuple]:
  *   def apply[T <: Tx](signedTx: Signed[T])(using ...): StoreF[IO][...] = ???
  *
  * // Create blueprint
  * val blueprint = new ModuleBlueprint[IO, "myModule", MySchema, EmptyTuple, MyTxs](
  *   owns = schema,
  *   reducer0 = reducer,
  *   txs = TxRegistry.empty,
  *   provider = TablesProvider.empty
  * )
  * ```
  *
  * @example Mounting a blueprint:
  * ```scala
  * import org.sigilaris.core.assembly.BlueprintDsl.*
  *
  * // Mount at single-segment path
  * val module = mount("accounts" -> accountsBlueprint)
  *
  * // Mount at multi-segment path
  * val module2 = mountAtPath(("app", "v1") -> accountsBlueprint)
  *
  * // Extract provider for dependent modules
  * val provider = module.toTablesProvider
  * ```
  *
  * @example Composing blueprints:
  * ```scala
  * import org.sigilaris.core.application.module.blueprint.Blueprint
  *
  * // Compose two modules
  * val composed = Blueprint.composeBlueprint[IO, "app"](
  *   accountsBlueprint,
  *   balancesBlueprint
  * )
  *
  * // Mount composed blueprint
  * val composedModule = mountComposed("myapp" -> composed)
  * ```
  *
  * @example Using providers for dependencies:
  * ```scala
  * // Module A provides tables
  * val moduleA = mount("accounts" -> accountsBlueprint)
  * val providerA = moduleA.toTablesProvider
  *
  * // Module B depends on A's tables
  * val blueprintB = new ModuleBlueprint[IO, "balances", SchemaB, SchemaA, TxsB](
  *   owns = schemaB,
  *   reducer0 = reducerB,
  *   txs = txRegistryB,
  *   provider = providerA  // Inject dependency
  * )
  *
  * val moduleB = mount("balances" -> blueprintB)
  * ```
  *
  * = Key Concepts =
  *
  * '''Owns vs Needs:'''
  *   - Owns: Tables this module creates and manages
  *   - Needs: Tables this module reads from other modules
  *   - Combined schema (Owns ++ Needs) is what transactions operate over
  *
  * '''Path Independence:'''
  *   - Blueprints don't know where they'll be deployed
  *   - Same blueprint can be mounted at different paths
  *   - Prefixes are computed at mount time from Path + Name
  *
  * '''Type Safety:'''
  *   - Compile-time schema validation via [[org.sigilaris.core.application.support.compiletime.Requires]]
  *   - Compile-time uniqueness checks via [[org.sigilaris.core.application.support.compiletime.UniqueNames]]
  *   - Compile-time prefix-free validation via [[org.sigilaris.core.application.support.compiletime.PrefixFreePath]]
  *
  * @see [[org.sigilaris.core.application.state]] for table and schema types
  * @see [[org.sigilaris.core.application.transactions]] for transaction types
  * @see [[org.sigilaris.core.assembly]] for high-level DSL
  */
package object module:
  // Blueprint types (path-independent)
  export org.sigilaris.core.application.module.blueprint.Blueprint
  export org.sigilaris.core.application.module.blueprint.ModuleBlueprint
  export org.sigilaris.core.application.module.blueprint.ComposedBlueprint
  export org.sigilaris.core.application.module.blueprint.StateReducer0
  export org.sigilaris.core.application.module.blueprint.RoutedStateReducer0
  
  // Schema instantiation
  export org.sigilaris.core.application.module.blueprint.SchemaInstantiation
  export org.sigilaris.core.application.module.blueprint.SchemaMapper
  
  // Runtime types (path-bound)
  export org.sigilaris.core.application.module.runtime.StateModule
  export org.sigilaris.core.application.module.runtime.StateReducer
  export org.sigilaris.core.application.module.runtime.RoutedStateReducer
  
  // Provider system
  export org.sigilaris.core.application.module.provider.TablesProvider
  export org.sigilaris.core.application.module.provider.TablesProjection
