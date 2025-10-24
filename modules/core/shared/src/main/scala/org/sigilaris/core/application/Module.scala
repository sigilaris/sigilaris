package org.sigilaris.core
package application

import cats.Monad
import merkle.MerkleTrie

/** Path-bound state reducer.
  *
  * A StateReducer is a StateReducer0 bound to a specific Path. It knows
  * exactly where its tables are located in the trie.
  *
  * This is created during module mounting when a blueprint is bound to a path.
  *
  * @tparam F the effect type
  * @tparam Path the mount path tuple
  * @tparam Schema the schema tuple (tuple of Entry types)
  */
trait StateReducer[F[_], Path <: Tuple, Schema <: Tuple]:
  /** Apply a transaction to produce a result and events.
    *
    * The reducer is polymorphic over the transaction type T, requiring only
    * that T's read and write requirements are satisfied by this reducer's Schema.
    *
    * @tparam T the transaction type
    * @param tx the transaction to apply
    * @param requiresReads evidence that T's read requirements are in Schema
    * @param requiresWrites evidence that T's write requirements are in Schema
    * @return a stateful computation returning the result and list of events
    */
  def apply[T <: Tx](tx: T)(using
      requiresReads: Requires[tx.Reads, Schema],
      requiresWrites: Requires[tx.Writes, Schema],
  ): StoreF[F][(tx.Result, List[tx.Event])]

/** State module (path-bound).
  *
  * A StateModule is a deployed module at a specific path. It combines:
  *   - Path: the deployment location
  *   - Schema: the set of tables (now with computed prefixes)
  *   - Reducer: the transaction processing logic (now path-aware)
  *   - Transactions: the set of supported transaction types
  *   - Dependencies: other modules this module depends on
  *
  * StateModule is created by mounting a ModuleBlueprint at a specific Path.
  *
  * @tparam F the effect type
  * @tparam Path the mount path tuple
  * @tparam Schema the schema tuple (tuple of Entry types)
  * @tparam Txs the transaction types tuple
  * @tparam Deps the dependency types tuple
  * @param tables the table instances (with prefixes bound to Path)
  * @param reducer the path-bound reducer
  * @param txs the transaction registry
  * @param deps the dependencies
  * @param uniqueNames evidence that table names are unique within Schema
  * @param prefixFreePath evidence that all table prefixes are prefix-free
  */
final class StateModule[F[_], Path <: Tuple, Schema <: Tuple, Txs <: Tuple, Deps <: Tuple](
    val tables: Tables[F, Schema],
    val reducer: StateReducer[F, Path, Schema],
    val txs: TxRegistry[Txs],
    val deps: Deps,
)(using
    val uniqueNames: UniqueNames[Schema],
    val prefixFreePath: PrefixFreePath[Path, Schema],
)

object StateModule:
  /** Mount a blueprint at a specific path, creating a StateModule.
    *
    * This is the key operation in Phase 2: it takes a path-independent blueprint
    * and binds it to a concrete path, computing table prefixes and creating
    * usable table instances.
    *
    * The mounting process:
    *   1. Computes the prefix for each table: encodePath(Path) ++ encodeSegment(TableName)
    *   2. Creates fresh StateTable instances bound to these prefixes
    *   3. Wraps the StateReducer0 to create a path-aware StateReducer
    *
    * Each mount produces a completely independent set of tables, ensuring that
    * mounting the same blueprint at different paths results in isolated keyspaces.
    *
    * @tparam F the effect type
    * @tparam MName the module name
    * @tparam Path the mount path
    * @tparam Schema the schema tuple
    * @tparam Txs the transaction types tuple
    * @tparam Deps the dependency types tuple
    * @param blueprint the blueprint to mount
    * @param monad the Monad instance for F (used by SchemaMapper derivation)
    * @param prefixFreePath evidence that the path+schema combination is prefix-free
    * @param nodeStore the MerkleTrie node store (used by SchemaMapper derivation)
    * @param schemaMapper the schema mapper for instantiating tables
    * @return a mounted state module with path-bound tables
    */
  def mount[F[_], MName <: String, Path <: Tuple, Schema <: Tuple, Txs <: Tuple, Deps <: Tuple](
      blueprint: ModuleBlueprint[F, MName, Schema, Txs, Deps],
  )(using
      @annotation.unused monad: Monad[F],
      prefixFreePath: PrefixFreePath[Path, Schema],
      @annotation.unused nodeStore: MerkleTrie.NodeStore[F],
      schemaMapper: SchemaMapper[F, Path, Schema],
  ): StateModule[F, Path, Schema, Txs, Deps] =
    // Instantiate fresh tables with path-specific prefixes from Entry instances
    // Note: monad and nodeStore are used implicitly by the SchemaMapper derivation
    val tables: Tables[F, Schema] =
      SchemaInstantiation.instantiateTablesFromEntries[F, Path, Schema](blueprint.schema)

    val pathBoundReducer = new StateReducer[F, Path, Schema]:
      def apply[T <: Tx](tx: T)(using
          requiresReads: Requires[tx.Reads, Schema],
          requiresWrites: Requires[tx.Writes, Schema],
      ): StoreF[F][(tx.Result, List[tx.Event])] =
        // Delegate to the path-agnostic reducer
        blueprint.reducer0.apply(tx)

    new StateModule[F, Path, Schema, Txs, Deps](
      tables = tables,
      reducer = pathBoundReducer,
      txs = blueprint.txs,
      deps = blueprint.deps,
    )(using blueprint.uniqueNames, prefixFreePath)
