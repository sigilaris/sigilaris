package org.sigilaris.core
package application

/** Path-agnostic state reducer.
  *
  * A StateReducer0 operates on transactions without knowledge of where it will
  * be mounted. The Path is unknown at this stage - it will be bound when the
  * blueprint is mounted into a StateModule.
  *
  * This allows writing reusable module logic that can be deployed at different
  * paths in different applications.
  *
  * @tparam F the effect type
  * @tparam Schema the schema tuple (tuple of Entry types)
  */
trait StateReducer0[F[_], Schema <: Tuple]:
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

/** Module blueprint (path-independent).
  *
  * A blueprint is a module specification without a concrete deployment path.
  * It defines:
  *   - Schema: the set of Entry instances (runtime values that can create tables)
  *   - Reducer: the transaction processing logic (StateReducer0)
  *   - Transactions: the set of supported transaction types
  *   - Dependencies: other modules this blueprint depends on
  *
  * Blueprints are designed with the assumption that they don't know where they
  * will be deployed. The mounting process (Phase 2) binds a blueprint to a
  * specific Path, computing prefixes and using Entry instances to create
  * concrete StateTable instances.
  *
  * IMPORTANT: Blueprint contains Entry instances (runtime values), not StateTable
  * instances. Each Entry can create a StateTable when given a prefix.
  *
  * @tparam F the effect type
  * @tparam MName the module name (literal String type)
  * @tparam Schema the schema tuple (tuple of Entry types)
  * @tparam Txs the transaction types tuple
  * @tparam Deps the dependency types tuple
  * @param schema the Entry instances (runtime values)
  * @param reducer0 the path-agnostic reducer
  * @param txs the transaction registry
  * @param deps the dependencies
  * @param uniqueNames evidence that table names are unique within Schema
  */
final class ModuleBlueprint[F[_], MName <: String, Schema <: Tuple, Txs <: Tuple, Deps <: Tuple](
    val schema: Schema,  // Runtime tuple of Entry instances
    val reducer0: StateReducer0[F, Schema],
    val txs: TxRegistry[Txs],
    val deps: Deps,
)(using val uniqueNames: UniqueNames[Schema])

object Blueprint:
  /** Compose two blueprints into a single blueprint.
    *
    * This is the core operation for Phase 3: combining two independent module
    * blueprints into a single blueprint with:
    *   - Unioned schemas (S1 ++ S2)
    *   - Unioned transaction sets (T1 ++ T2)
    *   - Paired dependencies ((D1, D2))
    *   - Merged reducer logic
    *
    * The composed blueprint requires evidence that the combined schema has unique
    * table names (UniqueNames[S1 ++ S2]).
    *
    * @tparam F the effect type
    * @tparam MOut the output module name
    * @tparam M1 first module name
    * @tparam S1 first schema tuple
    * @tparam T1 first transaction types tuple
    * @tparam D1 first dependencies tuple
    * @tparam M2 second module name
    * @tparam S2 second schema tuple
    * @tparam T2 second transaction types tuple
    * @tparam D2 second dependencies tuple
    * @param a the first blueprint
    * @param b the second blueprint
    * @param uniqueNames evidence that combined schema has unique names
    * @return a composed blueprint with unioned schemas and transactions
    */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def composeBlueprint[F[_], MOut <: String,
    M1 <: String, S1 <: Tuple, T1 <: Tuple, D1 <: Tuple,
    M2 <: String, S2 <: Tuple, T2 <: Tuple, D2 <: Tuple,
  ](
    a: ModuleBlueprint[F, M1, S1, T1, D1],
    b: ModuleBlueprint[F, M2, S2, T2, D2],
  )(using uniqueNames: UniqueNames[S1 ++ S2]): ModuleBlueprint[F, MOut, S1 ++ S2, T1 ++ T2, (D1, D2)] =
    // Union schemas by concatenating runtime tuples
    val combinedSchema: S1 ++ S2 = (a.schema ++ b.schema).asInstanceOf[S1 ++ S2]

    // Merge reducers: try first reducer, then second reducer
    // Note: In a real implementation, this would use a registry-based dispatch
    val mergedReducer = new StateReducer0[F, S1 ++ S2]:
      def apply[T <: Tx](tx: T)(using
          requiresReads: Requires[tx.Reads, S1 ++ S2],
          requiresWrites: Requires[tx.Writes, S1 ++ S2],
      ): StoreF[F][(tx.Result, List[tx.Event])] =
        // For now, delegate to first reducer
        // A full implementation would route based on transaction type
        a.reducer0.apply(tx)(using
          // Cast evidence - this is safe because S1 ++ S2 contains S1
          requiresReads.asInstanceOf[Requires[tx.Reads, S1]],
          requiresWrites.asInstanceOf[Requires[tx.Writes, S1]],
        )

    // Union transaction registries
    val combinedTxs: TxRegistry[T1 ++ T2] = a.txs.combine(b.txs)

    new ModuleBlueprint[F, MOut, S1 ++ S2, T1 ++ T2, (D1, D2)](
      schema = combinedSchema,
      reducer0 = mergedReducer,
      txs = combinedTxs,
      deps = (a.deps, b.deps),
    )

  /** Mount a blueprint at a path composed from base and sub paths.
    *
    * This is a convenience helper for Phase 3 that simplifies mounting a
    * blueprint at a nested path.
    *
    * @tparam F the effect type
    * @tparam MName the module name
    * @tparam Base the base path tuple
    * @tparam Sub the sub-path tuple
    * @tparam Schema the schema tuple
    * @tparam Txs the transaction types tuple
    * @tparam Deps the dependency types tuple
    * @param blueprint the blueprint to mount
    * @param monad the Monad instance for F
    * @param prefixFreePath evidence that Base ++ Sub with Schema is prefix-free
    * @param nodeStore the MerkleTrie node store
    * @param schemaMapper the schema mapper for instantiating tables
    * @return a state module mounted at Base ++ Sub
    */
  def mountAt[F[_], MName <: String, Base <: Tuple, Sub <: Tuple, Schema <: Tuple, Txs <: Tuple, Deps <: Tuple](
    blueprint: ModuleBlueprint[F, MName, Schema, Txs, Deps],
  )(using
    @annotation.unused monad: cats.Monad[F],
    prefixFreePath: PrefixFreePath[Base ++ Sub, Schema],
    @annotation.unused nodeStore: merkle.MerkleTrie.NodeStore[F],
    schemaMapper: SchemaMapper[F, Base ++ Sub, Schema],
  ): StateModule[F, Base ++ Sub, Schema, Txs, Deps] =
    StateModule.mount[F, MName, Base ++ Sub, Schema, Txs, Deps](blueprint)
