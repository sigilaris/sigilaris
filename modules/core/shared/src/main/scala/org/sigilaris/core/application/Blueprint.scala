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

/** Routed state reducer requiring ModuleRoutedTx.
  *
  * This is a wrapper that implements StateReducer0 but only works correctly
  * with transactions that implement ModuleRoutedTx. It is used in composed
  * blueprints where routing based on module path is required.
  *
  * The user is expected to only pass ModuleRoutedTx transactions to composed
  * blueprints. Non-routed transactions will fail at runtime with a cast exception,
  * which is acceptable since the API contract (composition) requires routing.
  *
  * @tparam F the effect type
  * @tparam Schema the schema tuple (tuple of Entry types)
  */
trait RoutedStateReducer0[F[_], Schema <: Tuple]:
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

/** Base trait for blueprints (path-independent).
  *
  * A blueprint is a module specification without a concrete deployment path.
  * The base trait is covariant in the reducer type, allowing both single-module
  * blueprints (StateReducer0) and composed blueprints (RoutedStateReducer0) to
  * be treated uniformly where appropriate.
  *
  * @tparam F the effect type
  * @tparam MName the module name (literal String type)
  * @tparam Schema the schema tuple (tuple of Entry types)
  * @tparam Txs the transaction types tuple
  * @tparam Deps the dependency types tuple
  * @tparam R the reducer type (covariant)
  */
sealed trait Blueprint[F[_], MName <: String, Schema <: Tuple, Txs <: Tuple, Deps <: Tuple, +R]:
  /** The Entry instances (runtime values that can create tables). */
  def schema: Schema

  /** The path-agnostic reducer. */
  def reducer0: R

  /** The transaction registry. */
  def txs: TxRegistry[Txs]

  /** The dependencies. */
  def deps: Deps

  /** Evidence that table names are unique within Schema. */
  def uniqueNames: UniqueNames[Schema]

/** Single-module blueprint (path-independent).
  *
  * A module blueprint is a specification for a single, self-contained module.
  * It uses StateReducer0, which can process any transaction type (T <: Tx)
  * that satisfies the schema requirements.
  *
  * Module blueprints are designed with the assumption that they don't know where
  * they will be deployed. The mounting process (Phase 2) binds a blueprint to a
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
  * @param reducer0 the path-agnostic reducer (accepts any Tx)
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
    extends Blueprint[F, MName, Schema, Txs, Deps, StateReducer0[F, Schema]]

/** Composed blueprint (path-independent).
  *
  * A composed blueprint is the result of combining two or more module blueprints.
  * It uses RoutedStateReducer0, which REQUIRES all transactions to implement
  * ModuleRoutedTx for routing purposes.
  *
  * The type system enforces this requirement at compile time: attempting to
  * apply a non-routed transaction to a ComposedBlueprint's reducer will be
  * a compile error.
  *
  * @tparam F the effect type
  * @tparam MName the module name (literal String type)
  * @tparam Schema the schema tuple (tuple of Entry types)
  * @tparam Txs the transaction types tuple
  * @tparam Deps the dependency types tuple
  * @param schema the Entry instances (runtime values)
  * @param reducer0 the routed reducer (requires ModuleRoutedTx)
  * @param txs the transaction registry
  * @param deps the dependencies
  * @param uniqueNames evidence that table names are unique within Schema
  */
final class ComposedBlueprint[F[_], MName <: String, Schema <: Tuple, Txs <: Tuple, Deps <: Tuple](
    val schema: Schema,
    val reducer0: RoutedStateReducer0[F, Schema],
    val txs: TxRegistry[Txs],
    val deps: Deps,
)(using val uniqueNames: UniqueNames[Schema])
    extends Blueprint[F, MName, Schema, Txs, Deps, RoutedStateReducer0[F, Schema]]

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

  /** Compose two blueprints into a single composed blueprint.
    *
    * This is the core operation for Phase 3: combining two independent module
    * blueprints into a single ComposedBlueprint with:
    *   - Unioned schemas (S1 ++ S2)
    *   - Unioned transaction sets (T1 ++ T2)
    *   - Concatenated dependencies (D1 ++ D2)
    *   - Merged reducer logic with module-based routing
    *
    * The composed blueprint requires evidence that the combined schema has unique
    * table names (UniqueNames[S1 ++ S2]).
    *
    * Reducer routing strategy:
    *   - Transactions MUST implement ModuleRoutedTx (enforced by RoutedStateReducer0 type)
    *   - The moduleId.path is module-relative (MName *: SubPath)
    *   - The reducer routes based on the first segment matching M1 or M2
    *   - Type safety is compile-time: non-routed transactions will not compile
    *
    * IMPORTANT: ModuleId is always module-relative. When a blueprint is mounted
    * at a path, the mount path is NOT prepended to transaction moduleIds.
    * Full paths (mountPath ++ moduleId.path) are only constructed at system
    * edges for telemetry or logging.
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
    * @return a ComposedBlueprint with RoutedStateReducer0
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
  ): ComposedBlueprint[F, MOut, S1 ++ S2, T1 ++ T2, D1 ++ D2] =
    // Union schemas using flat concatenation
    val combinedSchema: S1 ++ S2 = tupleConcat(a.schema, b.schema)

    // Capture module names for routing
    val m1Name: String = m1.value
    val m2Name: String = m2.value

    // Merge reducers with module-based routing
    // The composed reducer requires ModuleRoutedTx at compile time
    val routedReducer = new RoutedStateReducer0[F, S1 ++ S2]:
      // Specialized apply for routed transactions
      @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Any"))
      def apply[T <: Tx & ModuleRoutedTx](tx: T)(using
          requiresReads: Requires[tx.Reads, S1 ++ S2],
          requiresWrites: Requires[tx.Writes, S1 ++ S2],
      ): StoreF[F][(tx.Result, List[tx.Event])] =
        // Route based on module-relative path head
        // moduleId.path is always MName *: SubPath (never prepended with mount path)
        val pathHead = tx.moduleId.path.head.asInstanceOf[String]

        if pathHead === m1Name then
          a.reducer0.apply(tx)(using
            requiresReads.asInstanceOf[Requires[tx.Reads, S1]],
            requiresWrites.asInstanceOf[Requires[tx.Writes, S1]],
          )
        else if pathHead === m2Name then
          b.reducer0.apply(tx)(using
            requiresReads.asInstanceOf[Requires[tx.Reads, S2]],
            requiresWrites.asInstanceOf[Requires[tx.Writes, S2]],
          )
        else
          val msg1: String = m1Name
          val msg2: String = m2Name
          throw new IllegalArgumentException(
            s"TxRouteMissing: module '$pathHead' does not match '$msg1' or '$msg2'"
          )

    // Union transaction registries
    val combinedTxs: TxRegistry[T1 ++ T2] = a.txs.combine(b.txs)

    // Concatenate dependencies using flat concatenation
    val combinedDeps: D1 ++ D2 = tupleConcat(a.deps, b.deps)

    // Return ComposedBlueprint with RoutedStateReducer0 (no cast needed!)
    new ComposedBlueprint[F, MOut, S1 ++ S2, T1 ++ T2, D1 ++ D2](
      schema = combinedSchema,
      reducer0 = routedReducer,  // Type: RoutedStateReducer0[F, S1 ++ S2]
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
