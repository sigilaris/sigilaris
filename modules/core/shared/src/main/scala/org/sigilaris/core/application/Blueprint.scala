package org.sigilaris.core
package application

import cats.syntax.eq.*

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
  /** Concatenate two tuples into a flat result.
    *
    * This helper ensures that tuple concatenation at runtime matches the
    * type-level Tuple.Concat semantics. Without this, naive pairing like
    * `(a, b)` creates nested tuples that break table lookups and evidence.
    *
    * @tparam A the first tuple type
    * @tparam B the second tuple type
    * @param a the first tuple
    * @param b the second tuple
    * @return a flat concatenated tuple of type A ++ B
    */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def tupleConcat[A <: Tuple, B <: Tuple](a: A, b: B): Tuple.Concat[A, B] =
    Tuple.fromArray(a.toArray ++ b.toArray).asInstanceOf[Tuple.Concat[A, B]]

  /** Compose two blueprints into a single blueprint.
    *
    * This is the core operation for Phase 3: combining two independent module
    * blueprints into a single blueprint with:
    *   - Unioned schemas (S1 ++ S2)
    *   - Unioned transaction sets (T1 ++ T2)
    *   - Concatenated dependencies (D1 ++ D2)
    *   - Merged reducer logic with module-based routing
    *
    * The composed blueprint requires evidence that the combined schema has unique
    * table names (UniqueNames[S1 ++ S2]).
    *
    * Reducer routing strategy:
    *   - Transactions must implement ModuleRoutedTx to carry moduleId
    *   - The reducer routes based on the first segment of moduleId.path
    *   - If the path matches M1, route to a.reducer0
    *   - If the path matches M2, route to b.reducer0
    *   - Otherwise, fail with "TxRouteMissing"
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
    * @param m1 the compile-time value of M1 (for runtime comparison)
    * @param m2 the compile-time value of M2 (for runtime comparison)
    * @return a composed blueprint with unioned schemas and transactions
    */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Throw"))
  def composeBlueprint[F[_], MOut <: String,
    M1 <: String, S1 <: Tuple, T1 <: Tuple, D1 <: Tuple,
    M2 <: String, S2 <: Tuple, T2 <: Tuple, D2 <: Tuple,
  ](
    a: ModuleBlueprint[F, M1, S1, T1, D1],
    b: ModuleBlueprint[F, M2, S2, T2, D2],
  )(using
    uniqueNames: UniqueNames[S1 ++ S2],
    m1: ValueOf[M1],
    m2: ValueOf[M2],
  ): ModuleBlueprint[F, MOut, S1 ++ S2, T1 ++ T2, D1 ++ D2] =
    // Union schemas using flat concatenation
    val combinedSchema: S1 ++ S2 = tupleConcat(a.schema, b.schema)

    // Merge reducers with module-based routing
    val mergedReducer = new StateReducer0[F, S1 ++ S2]:
      @SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.ToString"))
      def apply[T <: Tx](tx: T)(using
          requiresReads: Requires[tx.Reads, S1 ++ S2],
          requiresWrites: Requires[tx.Writes, S1 ++ S2],
      ): StoreF[F][(tx.Result, List[tx.Event])] =
        // Route based on module path
        tx match
          case routed: ModuleRoutedTx =>
            val pathHead: Any = routed.moduleId.path.head
            pathHead match
              case head: String if head === m1.value =>
                a.reducer0.apply(tx)(using
                  requiresReads.asInstanceOf[Requires[tx.Reads, S1]],
                  requiresWrites.asInstanceOf[Requires[tx.Writes, S1]],
                )
              case head: String if head === m2.value =>
                b.reducer0.apply(tx)(using
                  requiresReads.asInstanceOf[Requires[tx.Reads, S2]],
                  requiresWrites.asInstanceOf[Requires[tx.Writes, S2]],
                )
              case _ =>
                throw new IllegalArgumentException(
                  s"TxRouteMissing: transaction path does not match ${m1.value} or ${m2.value}"
                )
          case nonRouted =>
            throw new IllegalArgumentException(
              s"Transaction ${nonRouted.getClass.getName} must implement ModuleRoutedTx for routing"
            )

    // Union transaction registries
    val combinedTxs: TxRegistry[T1 ++ T2] = a.txs.combine(b.txs)

    // Concatenate dependencies using flat concatenation
    val combinedDeps: D1 ++ D2 = tupleConcat(a.deps, b.deps)

    new ModuleBlueprint[F, MOut, S1 ++ S2, T1 ++ T2, D1 ++ D2](
      schema = combinedSchema,
      reducer0 = mergedReducer,
      txs = combinedTxs,
      deps = combinedDeps,
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
