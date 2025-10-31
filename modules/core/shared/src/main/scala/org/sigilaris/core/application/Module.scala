package org.sigilaris.core
package application

import cats.Monad
import scala.Tuple.++
import merkle.MerkleTrie

/** Path-bound state reducer for single modules.
  *
  * A StateReducer is a StateReducer0 bound to a specific Path. It knows exactly
  * where its tables are located in the trie.
  *
  * This is created during module mounting when a ModuleBlueprint is bound to a
  * path. It accepts any transaction type T <: Tx.
  *
  * Phase 5.5 update: Now explicitly models Owns and Needs like StateReducer0.
  *
  * @tparam F
  *   the effect type
  * @tparam Path
  *   the mount path tuple
  * @tparam Owns
  *   the owned schema tuple (tuple of Entry types)
  * @tparam Needs
  *   the needed schema tuple (tuple of Entry types)
  */
trait StateReducer[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple]:
  /** Apply a signed transaction to produce a result and events.
    *
    * ADR-0012: All transactions must be wrapped in Signed[T] to ensure
    * cryptographic signatures are present at compile time.
    *
    * The reducer is polymorphic over the transaction type T, requiring only
    * that T's read and write requirements are satisfied by this reducer's
    * combined schema.
    *
    * @tparam T
    *   the transaction type
    * @param signedTx
    *   the signed transaction to apply (ADR-0012)
    * @param requiresReads
    *   evidence that T's read requirements are in Owns ++ Needs
    * @param requiresWrites
    *   evidence that T's write requirements are in Owns ++ Needs
    * @return
    *   a stateful computation returning the result and list of events
    */
  def apply[T <: Tx](signedTx: Signed[T])(using
      requiresReads: Requires[signedTx.value.Reads, Owns ++ Needs],
      requiresWrites: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])]

/** Path-bound routed state reducer for composed modules.
  *
  * A RoutedStateReducer is a RoutedStateReducer0 bound to a specific Path. It
  * REQUIRES all transactions to implement ModuleRoutedTx for routing.
  *
  * This is created during module mounting when a ComposedBlueprint is bound to
  * a path. The type bound T <: Tx & ModuleRoutedTx enforces the routing
  * requirement at compile time.
  *
  * Phase 5.5 update: Now explicitly models Owns and Needs like
  * RoutedStateReducer0.
  *
  * @tparam F
  *   the effect type
  * @tparam Path
  *   the mount path tuple
  * @tparam Owns
  *   the owned schema tuple (tuple of Entry types)
  * @tparam Needs
  *   the needed schema tuple (tuple of Entry types)
  */
trait RoutedStateReducer[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple]:
  /** Apply a signed routed transaction to produce a result and events.
    *
    * ADR-0012: All transactions must be wrapped in Signed[T] to ensure
    * cryptographic signatures are present at compile time.
    *
    * The type bound T <: Tx & ModuleRoutedTx ensures that only transactions
    * implementing ModuleRoutedTx can be applied. This is enforced at compile
    * time.
    *
    * @tparam T
    *   the transaction type (must implement ModuleRoutedTx)
    * @param signedTx
    *   the signed transaction to apply (ADR-0012)
    * @param requiresReads
    *   evidence that T's read requirements are in Owns ++ Needs
    * @param requiresWrites
    *   evidence that T's write requirements are in Owns ++ Needs
    * @return
    *   a stateful computation returning the result and list of events
    */
  def apply[T <: Tx & ModuleRoutedTx](signedTx: Signed[T])(using
      requiresReads: Requires[signedTx.value.Reads, Owns ++ Needs],
      requiresWrites: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])]

/** State module (path-bound).
  *
  * A StateModule is a deployed module at a specific path. It combines:
  *   - Path: the deployment location
  *   - Owns: the set of owned tables (now with computed prefixes)
  *   - Needs: the set of needed external tables
  *   - Reducer: the transaction processing logic (now path-aware)
  *   - Transactions: the set of supported transaction types
  *   - TablesProvider: provider for external table dependencies
  *
  * StateModule is generic in the reducer type R, which can be either:
  *   - StateReducer[F, Path, Owns, Needs] for single modules (accepts any Tx)
  *   - RoutedStateReducer[F, Path, Owns, Needs] for composed modules (requires
  *     ModuleRoutedTx)
  *
  * This preserves compile-time type safety throughout the entire stack.
  *
  * Phase 5.5 update: Schema split into Owns/Needs, Deps replaced by
  * TablesProvider.
  *
  * @tparam F
  *   the effect type
  * @tparam Path
  *   the mount path tuple
  * @tparam Owns
  *   the owned schema tuple (tuple of Entry types)
  * @tparam Needs
  *   the needed schema tuple (tuple of Entry types)
  * @tparam Txs
  *   the transaction types tuple
  * @tparam R
  *   the reducer type (covariant)
  * @param tables
  *   the owned table instances (with prefixes bound to Path)
  * @param reducer
  *   the path-bound reducer
  * @param txs
  *   the transaction registry
  * @param tablesProvider
  *   the provider for external table dependencies
  * @param uniqueNames
  *   evidence that table names are unique within Owns
  * @param prefixFreePath
  *   evidence that all owned table prefixes are prefix-free
  */
final class StateModule[F[
    _,
], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, +R](
    val tables: Tables[F, Owns],
    val reducer: R,
    val txs: TxRegistry[Txs],
    val tablesProvider: TablesProvider[F, Needs],
)(using
    val uniqueNames: UniqueNames[Owns],
    val prefixFreePath: PrefixFreePath[Path, Owns],
)

object StateModule:
  /** Mount a single-module blueprint at a specific path, creating a
    * StateModule.
    *
    * This is the key operation in Phase 2: it takes a path-independent
    * ModuleBlueprint and binds it to a concrete path, computing table prefixes
    * and creating usable table instances.
    *
    * The mounting process:
    *   1. Computes the prefix for each owned table: encodePath(Path) ++
    *      encodeSegment(TableName) 2. Creates fresh StateTable instances bound
    *      to these prefixes 3. Wraps the reducer (StateReducer0) to create a
    *      path-aware StateReducer
    *
    * Each mount produces a completely independent set of tables, ensuring that
    * mounting the same blueprint at different paths results in isolated
    * keyspaces.
    *
    * Phase 5.5 update: Mounts Owns tables, passes through TablesProvider for
    * Needs.
    *
    * Type inference: Only Path needs to be specified explicitly. All other
    * types (F, MName, Owns, Needs, Txs) are inferred from the blueprint
    * parameter.
    *
    * @tparam Path
    *   the mount path (only type parameter that needs explicit specification)
    * @param blueprint
    *   the module blueprint to mount
    * @param monad
    *   the Monad instance for F (used by SchemaMapper derivation)
    * @param prefixFreePath
    *   evidence that the path+owns combination is prefix-free
    * @param nodeStore
    *   the MerkleTrie node store (used by SchemaMapper derivation)
    * @param schemaMapper
    *   the schema mapper for instantiating owned tables
    * @return
    *   a mounted state module with path-bound tables
    */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def mount[Path <: Tuple](
      blueprint: ModuleBlueprint[?, ?, ?, ?, ?],
  )(using
      @annotation.unused monad: Monad[blueprint.EffectType],
      prefixFreePath: PrefixFreePath[Path, blueprint.OwnsType],
      @annotation.unused nodeStore: MerkleTrie.NodeStore[blueprint.EffectType],
      schemaMapper: SchemaMapper[blueprint.EffectType, Path, blueprint.OwnsType],
  ): StateModule[
    blueprint.EffectType,
    Path,
    blueprint.OwnsType,
    blueprint.NeedsType,
    blueprint.TxsType,
    StateReducer[
      blueprint.EffectType,
      Path,
      blueprint.OwnsType,
      blueprint.NeedsType,
    ],
  ] =
    type F[A]  = blueprint.EffectType[A]
    type MName = blueprint.ModuleName
    type Owns  = blueprint.OwnsType
    type Needs = blueprint.NeedsType
    type Txs   = blueprint.TxsType

    mountImpl[F, MName, Path, Owns, Needs, Txs]:
      blueprint.asInstanceOf[ModuleBlueprint[F, MName, Owns, Needs, Txs]]

  private def mountImpl[F[
      _,
  ], MName <: String, Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
      blueprint: ModuleBlueprint[F, MName, Owns, Needs, Txs],
  )(using
      @annotation.unused monad: Monad[F],
      prefixFreePath: PrefixFreePath[Path, Owns],
      @annotation.unused nodeStore: MerkleTrie.NodeStore[F],
      schemaMapper: SchemaMapper[F, Path, Owns],
  ): StateModule[F, Path, Owns, Needs, Txs, StateReducer[
    F,
    Path,
    Owns,
    Needs,
  ]] =
    // Instantiate fresh owned tables with path-specific prefixes from Entry instances
    // Note: monad and nodeStore are used implicitly by the SchemaMapper derivation
    val ownsTables: Tables[F, Owns] =
      SchemaInstantiation.instantiateTablesFromEntries[F, Path, Owns](
        blueprint.owns,
      )

    val pathBoundReducer = new StateReducer[F, Path, Owns, Needs]:
      def apply[T <: Tx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, Owns ++ Needs],
          requiresWrites: Requires[signedTx.value.Writes, Owns ++ Needs],
      ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])] =
        // Delegate to the path-agnostic reducer with owns tables and provider
        blueprint.reducer0.apply(signedTx)(using
          requiresReads,
          requiresWrites,
          ownsTables,
          blueprint.provider,
        )

    new StateModule[
      F,
      Path,
      Owns,
      Needs,
      Txs,
      StateReducer[F, Path, Owns, Needs],
    ](
      tables = ownsTables,
      reducer = pathBoundReducer,
      txs = blueprint.txs,
      tablesProvider = blueprint.provider,
    )(using blueprint.uniqueNames, prefixFreePath)

  /** Mount a composed blueprint at a specific path, creating a StateModule.
    *
    * This method handles ComposedBlueprint specifically, which uses
    * RoutedStateReducer0. The mounted module's reducer will only accept
    * transactions implementing ModuleRoutedTx.
    *
    * The key difference from mount() is that this returns a StateModule with a
    * RoutedStateReducer, preserving the compile-time type safety requirement
    * for ModuleRoutedTx throughout the entire stack.
    *
    * Phase 5.5 update: Mounts Owns tables, passes through TablesProvider for
    * Needs.
    *
    * Type inference: Only Path needs to be specified explicitly. All other
    * types (F, MName, Owns, Needs, Txs) are inferred from the blueprint
    * parameter.
    *
    * @tparam Path
    *   the mount path (only type parameter that needs explicit specification)
    * @param blueprint
    *   the composed blueprint to mount
    * @param monad
    *   the Monad instance for F
    * @param prefixFreePath
    *   evidence that the path+owns combination is prefix-free
    * @param nodeStore
    *   the MerkleTrie node store
    * @param schemaMapper
    *   the schema mapper for instantiating owned tables
    * @return
    *   a mounted state module with RoutedStateReducer (compile-time safe)
    */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def mountComposed[Path <: Tuple](
      blueprint: ComposedBlueprint[?, ?, ?, ?, ?],
  )(using
      @annotation.unused monad: Monad[blueprint.EffectType],
      prefixFreePath: PrefixFreePath[Path, blueprint.OwnsType],
      @annotation.unused nodeStore: MerkleTrie.NodeStore[blueprint.EffectType],
      schemaMapper: SchemaMapper[blueprint.EffectType, Path, blueprint.OwnsType],
  ): StateModule[
    blueprint.EffectType,
    Path,
    blueprint.OwnsType,
    blueprint.NeedsType,
    blueprint.TxsType,
    RoutedStateReducer[
      blueprint.EffectType,
      Path,
      blueprint.OwnsType,
      blueprint.NeedsType,
    ],
  ] =
    type F[A]  = blueprint.EffectType[A]
    type MName = blueprint.ModuleName
    type Owns  = blueprint.OwnsType
    type Needs = blueprint.NeedsType
    type Txs   = blueprint.TxsType

    mountComposedImpl[F, MName, Path, Owns, Needs, Txs]:
      blueprint.asInstanceOf[ComposedBlueprint[F, MName, Owns, Needs, Txs]]

  private def mountComposedImpl[F[
      _,
  ], MName <: String, Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
      blueprint: ComposedBlueprint[F, MName, Owns, Needs, Txs],
  )(using
      @annotation.unused monad: Monad[F],
      prefixFreePath: PrefixFreePath[Path, Owns],
      @annotation.unused nodeStore: MerkleTrie.NodeStore[F],
      schemaMapper: SchemaMapper[F, Path, Owns],
  ): StateModule[F, Path, Owns, Needs, Txs, RoutedStateReducer[
    F,
    Path,
    Owns,
    Needs,
  ]] =
    // Instantiate fresh owned tables with path-specific prefixes from Entry instances
    val ownsTables: Tables[F, Owns] =
      SchemaInstantiation.instantiateTablesFromEntries[F, Path, Owns]:
        blueprint.owns

    // Create a RoutedStateReducer (path-bound version of RoutedStateReducer0)
    // NO CAST NEEDED - type safety is preserved!
    val pathBoundReducer = new RoutedStateReducer[F, Path, Owns, Needs]:
      def apply[T <: Tx & ModuleRoutedTx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, Owns ++ Needs],
          requiresWrites: Requires[signedTx.value.Writes, Owns ++ Needs],
      ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])] =
        // Delegate to the path-agnostic routed reducer with owns tables and provider
        blueprint.reducer0.apply(signedTx)(using
          requiresReads,
          requiresWrites,
          ownsTables,
          blueprint.provider,
        )

    new StateModule[
      F,
      Path,
      Owns,
      Needs,
      Txs,
      RoutedStateReducer[F, Path, Owns, Needs],
    ](
      tables = ownsTables,
      reducer = pathBoundReducer,
      txs = blueprint.txs,
      tablesProvider = blueprint.provider,
    )(using blueprint.uniqueNames, prefixFreePath)

  /** Extend two modules mounted at the same path into a single module.
    *
    * This is the core operation for Phase 5: combining two StateModules that
    * are deployed at the same path into a unified module with:
    *   - Merged owned schemas (O1 ++ O2)
    *   - Merged transaction sets (T1 ++ T2)
    *   - Combined reducer logic (tries r1, then r2)
    *
    * The extend operation requires evidence that:
    *   - Combined owned schema has unique names (UniqueNames[O1 ++ O2])
    *   - Combined owned schema is prefix-free at the shared Path
    *     (PrefixFreePath[Path, O1 ++ O2])
    *
    * Reducer merging strategy:
    *   - Attempt to apply the transaction to r1
    *   - If r1 succeeds, return its result
    *   - If r1 fails, attempt r2
    *   - If both fail, return the last error
    *
    * This allows transactions to be processed by whichever module can handle
    * them.
    *
    * Phase 5.6 UPGRADE: Now supports modules with non-empty Needs. The
    * providers are merged using TablesProvider.merge, which requires that the
    * dependency schemas are disjoint (DisjointSchemas[N1, N2]). This prevents
    * ambiguous table lookups while allowing flexible composition of dependent
    * modules.
    *
    * @tparam F
    *   the effect type
    * @tparam Path
    *   the shared mount path
    * @tparam O1
    *   first owned schema tuple
    * @tparam N1
    *   first needs schema tuple
    * @tparam O2
    *   second owned schema tuple
    * @tparam N2
    *   second needs schema tuple
    * @tparam T1
    *   first transaction types tuple
    * @tparam T2
    *   second transaction types tuple
    * @tparam R1
    *   first reducer type
    * @tparam R2
    *   second reducer type
    * @param a
    *   the first module
    * @param b
    *   the second module
    * @param uniqueNames
    *   evidence that combined owned schema has unique names
    * @param prefixFreePath
    *   evidence that combined owned schema is prefix-free at Path
    * @param disjointNeeds
    *   evidence that dependency schemas are disjoint (Phase 5.6)
    * @return
    *   a merged state module at the same Path
    */
  def extend[F[_]
    : cats.Monad, Path <: Tuple, O1 <: Tuple, N1 <: Tuple, O2 <: Tuple, N2 <: Tuple, T1 <: Tuple, T2 <: Tuple, R1, R2](
      a: StateModule[F, Path, O1, N1, T1, R1],
      b: StateModule[F, Path, O2, N2, T2, R2],
  )(using
      uniqueNames: UniqueNames[O1 ++ O2],
      prefixFreePath: PrefixFreePath[Path, O1 ++ O2],
      disjointNeeds: TablesProvider.DisjointSchemas[N1, N2],
  ): StateModule[F, Path, O1 ++ O2, N1 ++ N2, T1 ++ T2, StateReducer[
    F,
    Path,
    O1 ++ O2,
    N1 ++ N2,
  ]] =
    // Merge owned tables using flat concatenation
    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    val mergedTables: Tables[F, O1 ++ O2] =
      (a.tables ++ b.tables).asInstanceOf[Tables[F, O1 ++ O2]]

    // Phase 5.6: Merge providers from both modules
    val mergedProvider: TablesProvider[F, N1 ++ N2] =
      TablesProvider.merge(a.tablesProvider, b.tablesProvider)(using
        disjointNeeds,
      )

    // Merge reducers - create a new reducer that delegates based on runtime type matching
    // This is a simplified Phase 5 implementation
    // A production system would use explicit transaction routing or a registry pattern
    val mergedReducer = mergeReducers[F, Path, O1, N1, O2, N2](
      a.reducer.asInstanceOf[StateReducer[F, Path, O1, N1]],
      b.reducer.asInstanceOf[StateReducer[F, Path, O2, N2]],
    )

    // Merge transaction registries
    val mergedTxs: TxRegistry[T1 ++ T2] = a.txs.combine(b.txs)

    new StateModule[
      F,
      Path,
      O1 ++ O2,
      N1 ++ N2,
      T1 ++ T2,
      StateReducer[F, Path, O1 ++ O2, N1 ++ N2],
    ](
      tables = mergedTables,
      reducer = mergedReducer,
      txs = mergedTxs,
      tablesProvider = mergedProvider, // Phase 5.6: Use merged provider
    )(using uniqueNames, prefixFreePath)

  /** Merge two reducers into a single reducer.
    *
    * The merged reducer attempts to apply transactions using a fallback
    * strategy:
    *   1. Try reducer r1 first 2. If r1 fails (returns Left), try reducer r2 3.
    *      If both fail, return r2's error 4. If r1 succeeds, return r1's result
    *      (even if events are empty)
    *
    * This fallback approach is safer than checking for empty events, as it
    * allows reducers to legitimately return no events (e.g., query-only
    * transactions).
    *
    * This is a Phase 5 MVP implementation. A production system would:
    *   - Use explicit transaction routing via ModuleRoutedTx
    *   - Maintain a reducer registry for efficient dispatch
    *   - Validate at compile time which reducer to use
    *
    * LIMITATION: Both reducers will be attempted for unhandled transactions,
    * which may cause duplicate work. Use explicit routing (ModuleRoutedTx) for
    * production systems.
    *
    * Phase 5.6 update: Reducers can have non-empty Needs (merged via
    * DisjointSchemas).
    *
    * @tparam F
    *   the effect type
    * @tparam Path
    *   the mount path
    * @tparam O1
    *   first owned schema tuple
    * @tparam N1
    *   first needs schema tuple
    * @tparam O2
    *   second owned schema tuple
    * @tparam N2
    *   second needs schema tuple
    * @param r1
    *   the first reducer
    * @param r2
    *   the second reducer
    * @return
    *   a merged reducer for schema (O1 ++ O2) with needs (N1 ++ N2)
    */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def mergeReducers[F[_]
    : cats.Monad, Path <: Tuple, O1 <: Tuple, N1 <: Tuple, O2 <: Tuple, N2 <: Tuple](
      r1: StateReducer[F, Path, O1, N1],
      r2: StateReducer[F, Path, O2, N2],
  ): StateReducer[F, Path, O1 ++ O2, N1 ++ N2] =
    new StateReducer[F, Path, O1 ++ O2, N1 ++ N2]:
      def apply[T <: Tx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, (O1 ++ O2) ++ (N1 ++ N2)],
          requiresWrites: Requires[signedTx.value.Writes, (O1 ++ O2) ++ (N1 ++ N2)],
      ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])] =
        // Try r1 first
        val r1Result = r1.apply(signedTx)(using
          requiresReads.asInstanceOf[Requires[signedTx.value.Reads, O1 ++ N1]],
          requiresWrites.asInstanceOf[Requires[signedTx.value.Writes, O1 ++ N1]],
        )

        // If r1 fails, try r2 as fallback
        import cats.data.StateT
        import cats.data.EitherT

        StateT: (s: StoreState) =>
          // r1Result.run(s) returns EitherT[F, Failure, (State, (Result, Events))]
          r1Result
            .run(s)
            .recoverWith: _ =>
              // r1 failed, try r2 as fallback
              r2.apply(signedTx)(using
                requiresReads.asInstanceOf[Requires[signedTx.value.Reads, O2 ++ N2]],
                requiresWrites.asInstanceOf[Requires[signedTx.value.Writes, O2 ++ N2]],
              ).run(s)

  /** Module factory for building modules at different paths.
    *
    * A ModuleFactory is a blueprint wrapper that can be instantiated at
    * different paths, enabling flexible assembly patterns.
    *
    * Phase 5.5 SAFETY: Only supports modules with Needs = EmptyTuple. This
    * means factories only work for self-contained modules:
    *   - No cross-module dependencies
    *   - Building the same module at multiple paths (sandboxing)
    *   - Creating module templates that can be customized per path
    *
    * For modules with external dependencies, use direct mount or
    * composeBlueprint.
    *
    * @tparam F
    *   the effect type
    * @tparam Owns
    *   the owned schema tuple
    * @tparam Txs
    *   the transaction types tuple
    */
  trait ModuleFactory[F[_], Owns <: Tuple, Txs <: Tuple]:
    /** Build a state module at the given path.
      *
      * @tparam Path
      *   the mount path
      * @param monad
      *   the Monad instance for F
      * @param prefixFreePath
      *   evidence that the path+owns combination is prefix-free
      * @param nodeStore
      *   the MerkleTrie node store
      * @param schemaMapper
      *   the schema mapper for instantiating owned tables
      * @return
      *   a state module mounted at Path (Needs = EmptyTuple)
      */
    def build[Path <: Tuple](using
        @annotation.unused monad: cats.Monad[F],
        prefixFreePath: PrefixFreePath[Path, Owns],
        @annotation.unused nodeStore: merkle.MerkleTrie.NodeStore[F],
        schemaMapper: SchemaMapper[F, Path, Owns],
    ): StateModule[F, Path, Owns, EmptyTuple, Txs, StateReducer[
      F,
      Path,
      Owns,
      EmptyTuple,
    ]]

  object ModuleFactory:
    /** Create a module factory from a blueprint.
      *
      * COMPILE-TIME SAFETY: Only blueprints with Needs = EmptyTuple are
      * accepted. This ensures that factories cannot be created from modules
      * that require external table dependencies (Phase 5.5 TablesProvider).
      *
      * Modules with external dependencies must be deployed using:
      *   - Direct mount: StateModule.mount(blueprint)
      *   - Composition: composeBlueprint(...) → mount (when both have Needs =
      *     EmptyTuple)
      *
      * @tparam F
      *   the effect type
      * @tparam MName
      *   the module name
      * @tparam Owns
      *   the owned schema tuple
      * @tparam Txs
      *   the transaction types tuple
      * @param blueprint
      *   the blueprint to wrap (must have Needs = EmptyTuple)
      * @return
      *   a module factory that can build the module at different paths
      */
    def fromBlueprint[F[_], MName <: String, Owns <: Tuple, Txs <: Tuple](
        blueprint: ModuleBlueprint[F, MName, Owns, EmptyTuple, Txs],
    ): ModuleFactory[F, Owns, Txs] =
      new ModuleFactory[F, Owns, Txs]:
        def build[Path <: Tuple](using
            @annotation.unused monad: cats.Monad[F],
            prefixFreePath: PrefixFreePath[Path, Owns],
            @annotation.unused nodeStore: merkle.MerkleTrie.NodeStore[F],
            schemaMapper: SchemaMapper[F, Path, Owns],
        ): StateModule[F, Path, Owns, EmptyTuple, Txs, StateReducer[
          F,
          Path,
          Owns,
          EmptyTuple,
        ]] =
          // Mount the blueprint - Needs is already EmptyTuple
          StateModule.mount[Path](blueprint)
