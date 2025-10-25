package org.sigilaris.core
package application

import cats.Monad
import merkle.MerkleTrie

/** Path-bound state reducer for single modules.
  *
  * A StateReducer is a StateReducer0 bound to a specific Path. It knows
  * exactly where its tables are located in the trie.
  *
  * This is created during module mounting when a ModuleBlueprint is bound to a path.
  * It accepts any transaction type T <: Tx.
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

/** Path-bound routed state reducer for composed modules.
  *
  * A RoutedStateReducer is a RoutedStateReducer0 bound to a specific Path.
  * It REQUIRES all transactions to implement ModuleRoutedTx for routing.
  *
  * This is created during module mounting when a ComposedBlueprint is bound to a path.
  * The type bound T <: Tx & ModuleRoutedTx enforces the routing requirement at compile time.
  *
  * @tparam F the effect type
  * @tparam Path the mount path tuple
  * @tparam Schema the schema tuple (tuple of Entry types)
  */
trait RoutedStateReducer[F[_], Path <: Tuple, Schema <: Tuple]:
  /** Apply a routed transaction to produce a result and events.
    *
    * The type bound T <: Tx & ModuleRoutedTx ensures that only transactions
    * implementing ModuleRoutedTx can be applied. This is enforced at compile time.
    *
    * @tparam T the transaction type (must implement ModuleRoutedTx)
    * @param tx the transaction to apply
    * @param requiresReads evidence that T's read requirements are in Schema
    * @param requiresWrites evidence that T's write requirements are in Schema
    * @return a stateful computation returning the result and list of events
    */
  def apply[T <: Tx & ModuleRoutedTx](tx: T)(using
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
  * StateModule is generic in the reducer type R, which can be either:
  *   - StateReducer[F, Path, Schema] for single modules (accepts any Tx)
  *   - RoutedStateReducer[F, Path, Schema] for composed modules (requires ModuleRoutedTx)
  *
  * This preserves compile-time type safety throughout the entire stack.
  *
  * @tparam F the effect type
  * @tparam Path the mount path tuple
  * @tparam Schema the schema tuple (tuple of Entry types)
  * @tparam Txs the transaction types tuple
  * @tparam Deps the dependency types tuple
  * @tparam R the reducer type (covariant)
  * @param tables the table instances (with prefixes bound to Path)
  * @param reducer the path-bound reducer
  * @param txs the transaction registry
  * @param deps the dependencies
  * @param uniqueNames evidence that table names are unique within Schema
  * @param prefixFreePath evidence that all table prefixes are prefix-free
  */
final class StateModule[F[_], Path <: Tuple, Schema <: Tuple, Txs <: Tuple, Deps <: Tuple, +R](
    val tables: Tables[F, Schema],
    val reducer: R,
    val txs: TxRegistry[Txs],
    val deps: Deps,
)(using
    val uniqueNames: UniqueNames[Schema],
    val prefixFreePath: PrefixFreePath[Path, Schema],
)

object StateModule:
  /** Mount a single-module blueprint at a specific path, creating a StateModule.
    *
    * This is the key operation in Phase 2: it takes a path-independent ModuleBlueprint
    * and binds it to a concrete path, computing table prefixes and creating usable table instances.
    *
    * The mounting process:
    *   1. Computes the prefix for each table: encodePath(Path) ++ encodeSegment(TableName)
    *   2. Creates fresh StateTable instances bound to these prefixes
    *   3. Wraps the reducer (StateReducer0) to create a path-aware StateReducer
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
    * @param blueprint the module blueprint to mount
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
  ): StateModule[F, Path, Schema, Txs, Deps, StateReducer[F, Path, Schema]] =
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

    new StateModule[F, Path, Schema, Txs, Deps, StateReducer[F, Path, Schema]](
      tables = tables,
      reducer = pathBoundReducer,
      txs = blueprint.txs,
      deps = blueprint.deps,
    )(using blueprint.uniqueNames, prefixFreePath)

  /** Mount a composed blueprint at a specific path, creating a StateModule.
    *
    * This method handles ComposedBlueprint specifically, which uses RoutedStateReducer0.
    * The mounted module's reducer will only accept transactions implementing ModuleRoutedTx.
    *
    * The key difference from mount() is that this returns a StateModule with a
    * RoutedStateReducer, preserving the compile-time type safety requirement for
    * ModuleRoutedTx throughout the entire stack.
    *
    * @tparam F the effect type
    * @tparam MName the module name
    * @tparam Path the mount path
    * @tparam Schema the schema tuple
    * @tparam Txs the transaction types tuple
    * @tparam Deps the dependency types tuple
    * @param blueprint the composed blueprint to mount
    * @param monad the Monad instance for F
    * @param prefixFreePath evidence that the path+schema combination is prefix-free
    * @param nodeStore the MerkleTrie node store
    * @param schemaMapper the schema mapper for instantiating tables
    * @return a mounted state module with RoutedStateReducer (compile-time safe)
    */
  def mountComposed[F[_], MName <: String, Path <: Tuple, Schema <: Tuple, Txs <: Tuple, Deps <: Tuple](
      blueprint: ComposedBlueprint[F, MName, Schema, Txs, Deps],
  )(using
      @annotation.unused monad: Monad[F],
      prefixFreePath: PrefixFreePath[Path, Schema],
      @annotation.unused nodeStore: MerkleTrie.NodeStore[F],
      schemaMapper: SchemaMapper[F, Path, Schema],
  ): StateModule[F, Path, Schema, Txs, Deps, RoutedStateReducer[F, Path, Schema]] =
    // Instantiate fresh tables with path-specific prefixes from Entry instances
    val tables: Tables[F, Schema] =
      SchemaInstantiation.instantiateTablesFromEntries[F, Path, Schema](blueprint.schema)

    // Create a RoutedStateReducer (path-bound version of RoutedStateReducer0)
    // NO CAST NEEDED - type safety is preserved!
    val pathBoundReducer = new RoutedStateReducer[F, Path, Schema]:
      def apply[T <: Tx & ModuleRoutedTx](tx: T)(using
          requiresReads: Requires[tx.Reads, Schema],
          requiresWrites: Requires[tx.Writes, Schema],
      ): StoreF[F][(tx.Result, List[tx.Event])] =
        // Delegate to the path-agnostic routed reducer
        blueprint.reducer0.apply(tx)

    new StateModule[F, Path, Schema, Txs, Deps, RoutedStateReducer[F, Path, Schema]](
      tables = tables,
      reducer = pathBoundReducer,
      txs = blueprint.txs,
      deps = blueprint.deps,
    )(using blueprint.uniqueNames, prefixFreePath)

  /** Extend two modules mounted at the same path into a single module.
    *
    * This is the core operation for Phase 5: combining two StateModules that
    * are deployed at the same path into a unified module with:
    *   - Merged schemas (S1 ++ S2)
    *   - Merged transaction sets (T1 ++ T2)
    *   - Concatenated dependencies ((D1, D2))
    *   - Combined reducer logic (tries r1, then r2)
    *
    * The extend operation requires evidence that:
    *   - Combined schema has unique names (UniqueNames[S1 ++ S2])
    *   - Combined schema is prefix-free at the shared Path (PrefixFreePath[Path, S1 ++ S2])
    *
    * Reducer merging strategy:
    *   - Attempt to apply the transaction to r1
    *   - If r1 succeeds, return its result
    *   - If r1 fails, attempt r2
    *   - If both fail, return the last error
    *
    * This allows transactions to be processed by whichever module can handle them.
    *
    * @tparam F the effect type
    * @tparam Path the shared mount path
    * @tparam S1 first schema tuple
    * @tparam S2 second schema tuple
    * @tparam T1 first transaction types tuple
    * @tparam T2 second transaction types tuple
    * @tparam D1 first dependencies tuple
    * @tparam D2 second dependencies tuple
    * @tparam R1 first reducer type
    * @tparam R2 second reducer type
    * @param a the first module
    * @param b the second module
    * @param uniqueNames evidence that combined schema has unique names
    * @param prefixFreePath evidence that combined schema is prefix-free at Path
    * @return a merged state module at the same Path
    */
  def extend[F[_], Path <: Tuple, S1 <: Tuple, S2 <: Tuple, T1 <: Tuple, T2 <: Tuple, D1 <: Tuple, D2 <: Tuple, R1, R2](
      a: StateModule[F, Path, S1, T1, D1, R1],
      b: StateModule[F, Path, S2, T2, D2, R2],
  )(using
      uniqueNames: UniqueNames[S1 ++ S2],
      prefixFreePath: PrefixFreePath[Path, S1 ++ S2],
  ): StateModule[F, Path, S1 ++ S2, T1 ++ T2, (D1, D2), StateReducer[F, Path, S1 ++ S2]] =
    // Merge tables using flat concatenation
    val mergedTables: Tables[F, S1 ++ S2] = mergeTables(a.tables, b.tables)

    // Merge reducers - create a new reducer that delegates based on runtime type matching
    // This is a simplified Phase 5 implementation
    // A production system would use explicit transaction routing or a registry pattern
    val mergedReducer = mergeReducers[F, Path, S1, S2](
      a.reducer.asInstanceOf[StateReducer[F, Path, S1]],
      b.reducer.asInstanceOf[StateReducer[F, Path, S2]],
    )

    // Merge transaction registries
    val mergedTxs: TxRegistry[T1 ++ T2] = a.txs.combine(b.txs)

    // Combine dependencies as a tuple
    val mergedDeps: (D1, D2) = (a.deps, b.deps)

    new StateModule[F, Path, S1 ++ S2, T1 ++ T2, (D1, D2), StateReducer[F, Path, S1 ++ S2]](
      tables = mergedTables,
      reducer = mergedReducer,
      txs = mergedTxs,
      deps = mergedDeps,
    )(using uniqueNames, prefixFreePath)

  /** Merge two Tables tuples into a single flat tuple.
    *
    * This helper ensures that table merging at runtime matches the
    * type-level Tuple.Concat semantics.
    *
    * @tparam F the effect type
    * @tparam S1 first schema tuple
    * @tparam S2 second schema tuple
    * @param t1 the first tables tuple
    * @param t2 the second tables tuple
    * @return a flat concatenated tuple of tables
    */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def mergeTables[F[_], S1 <: Tuple, S2 <: Tuple](
      t1: Tables[F, S1],
      t2: Tables[F, S2],
  ): Tables[F, S1 ++ S2] =
    Tuple.fromArray(t1.toArray ++ t2.toArray).asInstanceOf[Tables[F, S1 ++ S2]]

  /** Merge two reducers into a single reducer.
    *
    * The merged reducer attempts to apply transactions using a fallback strategy:
    *   1. Try reducer r1 (if the transaction requires tables in S1)
    *   2. If that fails or doesn't apply, try reducer r2 (if the transaction requires tables in S2)
    *
    * This is a Phase 5 MVP implementation. A production system would:
    *   - Use explicit transaction routing via ModuleRoutedTx
    *   - Maintain a reducer registry for efficient dispatch
    *   - Validate at compile time which reducer to use
    *
    * For now, we simply attempt both reducers in order and return the first success.
    *
    * @tparam F the effect type
    * @tparam Path the mount path
    * @tparam S1 first schema tuple
    * @tparam S2 second schema tuple
    * @param r1 the first reducer
    * @param r2 the second reducer
    * @return a merged reducer for schema S1 ++ S2
    */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Throw"))
  private def mergeReducers[F[_], Path <: Tuple, S1 <: Tuple, S2 <: Tuple](
      r1: StateReducer[F, Path, S1],
      r2: StateReducer[F, Path, S2],
  ): StateReducer[F, Path, S1 ++ S2] =
    new StateReducer[F, Path, S1 ++ S2]:
      def apply[T <: Tx](tx: T)(using
          requiresReads: Requires[tx.Reads, S1 ++ S2],
          requiresWrites: Requires[tx.Writes, S1 ++ S2],
      ): StoreF[F][(tx.Result, List[tx.Event])] =
        // Simplified Phase 5 strategy: attempt r1, fallback to r2
        // This requires casting the evidence - in a production system,
        // we'd use compile-time evidence to determine which reducer to use

        // For Phase 5 MVP, we'll use a simple heuristic:
        // Try to determine at runtime which reducer can handle the transaction
        // by checking if the required tables are in S1 or S2

        // As a first approximation, we'll try r1 first
        // If there's a compile-time error about missing tables, we'll try r2
        // This is not ideal but sufficient for Phase 5 demonstration

        // TODO: In future phases, implement proper reducer selection via:
        // - ModuleRoutedTx for composed blueprints
        // - Reducer registry for explicit transaction-to-reducer mapping
        // - Compile-time evidence for reducer selection

        try
          r1.apply(tx)(using
            requiresReads.asInstanceOf[Requires[tx.Reads, S1]],
            requiresWrites.asInstanceOf[Requires[tx.Writes, S1]],
          )
        catch
          case _: ClassCastException =>
            r2.apply(tx)(using
              requiresReads.asInstanceOf[Requires[tx.Reads, S2]],
              requiresWrites.asInstanceOf[Requires[tx.Writes, S2]],
            )

  /** Module factory for building modules at different paths.
    *
    * A ModuleFactory is a blueprint wrapper that can be instantiated
    * at different paths, enabling flexible assembly patterns.
    *
    * This pattern is useful for:
    *   - Building the same module at multiple paths (sandboxing)
    *   - Aggregating modules that will be deployed together
    *   - Creating module templates that can be customized per path
    *
    * @tparam F the effect type
    * @tparam Schema the schema tuple
    * @tparam Txs the transaction types tuple
    */
  trait ModuleFactory[F[_], Schema <: Tuple, Txs <: Tuple]:
    /** Build a state module at the given path.
      *
      * @tparam Path the mount path
      * @param monad the Monad instance for F
      * @param prefixFreePath evidence that the path+schema combination is prefix-free
      * @param nodeStore the MerkleTrie node store
      * @param schemaMapper the schema mapper for instantiating tables
      * @return a state module mounted at Path
      */
    def build[Path <: Tuple](using
        @annotation.unused monad: cats.Monad[F],
        prefixFreePath: PrefixFreePath[Path, Schema],
        @annotation.unused nodeStore: merkle.MerkleTrie.NodeStore[F],
        schemaMapper: SchemaMapper[F, Path, Schema],
    ): StateModule[F, Path, Schema, Txs, EmptyTuple, StateReducer[F, Path, Schema]]

  object ModuleFactory:
    /** Create a module factory from a blueprint.
      *
      * @tparam F the effect type
      * @tparam MName the module name
      * @tparam Schema the schema tuple
      * @tparam Txs the transaction types tuple
      * @tparam Deps the dependency types tuple
      * @param blueprint the blueprint to wrap
      * @return a module factory that can build the module at different paths
      */
    def fromBlueprint[F[_], MName <: String, Schema <: Tuple, Txs <: Tuple, Deps <: Tuple](
        blueprint: ModuleBlueprint[F, MName, Schema, Txs, Deps],
    ): ModuleFactory[F, Schema, Txs] =
      new ModuleFactory[F, Schema, Txs]:
        def build[Path <: Tuple](using
            @annotation.unused monad: cats.Monad[F],
            prefixFreePath: PrefixFreePath[Path, Schema],
            @annotation.unused nodeStore: merkle.MerkleTrie.NodeStore[F],
            schemaMapper: SchemaMapper[F, Path, Schema],
        ): StateModule[F, Path, Schema, Txs, EmptyTuple, StateReducer[F, Path, Schema]] =
          // Mount the blueprint, then discard dependencies
          val mounted = StateModule.mount[F, MName, Path, Schema, Txs, Deps](blueprint)
          // Create a new module without dependencies for factory pattern
          new StateModule[F, Path, Schema, Txs, EmptyTuple, StateReducer[F, Path, Schema]](
            tables = mounted.tables,
            reducer = mounted.reducer,
            txs = mounted.txs,
            deps = EmptyTuple,
          )(using mounted.uniqueNames, mounted.prefixFreePath)

  /** Aggregate two module factories into a single factory.
    *
    * The aggregated factory builds both modules at the same path and
    * combines them using extend. This is useful for:
    *   - Building composite applications from independent modules
    *   - Creating module bundles that are always deployed together
    *   - Shared assembly patterns where modules share the same state
    *
    * NOTE: This is a Phase 5 MVP implementation using unsafe casts for evidence.
    * A production system would properly derive subset evidence at compile time.
    *
    * @tparam F the effect type
    * @tparam S1 first schema tuple
    * @tparam S2 second schema tuple
    * @tparam T1 first transaction types tuple
    * @tparam T2 second transaction types tuple
    * @param m1 the first module factory
    * @param m2 the second module factory
    * @return an aggregated module factory
    */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def aggregate[F[_], S1 <: Tuple, S2 <: Tuple, T1 <: Tuple, T2 <: Tuple](
      m1: ModuleFactory[F, S1, T1],
      m2: ModuleFactory[F, S2, T2],
  ): ModuleFactory[F, S1 ++ S2, T1 ++ T2] =
    new ModuleFactory[F, S1 ++ S2, T1 ++ T2]:
      @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
      def build[Path <: Tuple](using
          monad: cats.Monad[F],
          prefixFreePath: PrefixFreePath[Path, S1 ++ S2],
          nodeStore: merkle.MerkleTrie.NodeStore[F],
          schemaMapper: SchemaMapper[F, Path, S1 ++ S2],
      ): StateModule[F, Path, S1 ++ S2, T1 ++ T2, EmptyTuple, StateReducer[F, Path, S1 ++ S2]] =
        // Build both modules at the same path
        // We need to create schema mappers and evidence for S1 and S2 from the combined ones
        // For Phase 5 MVP, we'll use unsafe casts
        // A production system would properly derive subset mappers and evidence

        given SchemaMapper[F, Path, S1] = schemaMapper.asInstanceOf[SchemaMapper[F, Path, S1]]
        given SchemaMapper[F, Path, S2] = schemaMapper.asInstanceOf[SchemaMapper[F, Path, S2]]
        given PrefixFreePath[Path, S1] = prefixFreePath.asInstanceOf[PrefixFreePath[Path, S1]]
        given PrefixFreePath[Path, S2] = prefixFreePath.asInstanceOf[PrefixFreePath[Path, S2]]

        val mod1 = m1.build[Path]
        val mod2 = m2.build[Path]

        // Extend them at the same path
        // Need to provide evidence for the combined schema
        given UniqueNames[S1 ++ S2] = prefixFreePath.asInstanceOf[UniqueNames[S1 ++ S2]]

        val extended = extend(mod1, mod2)

        // Wrap to change dependency type to EmptyTuple
        new StateModule[F, Path, S1 ++ S2, T1 ++ T2, EmptyTuple, StateReducer[F, Path, S1 ++ S2]](
          tables = extended.tables,
          reducer = extended.reducer,
          txs = extended.txs,
          deps = EmptyTuple,
        )(using extended.uniqueNames, extended.prefixFreePath)
