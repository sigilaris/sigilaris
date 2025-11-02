package org.sigilaris.core.application.module.provider

import scala.Tuple.++

import org.sigilaris.core.application.module.runtime.StateModule
import org.sigilaris.core.application.state.{Entry, Tables}
import org.sigilaris.core.application.support.compiletime.{DifferentNames, Lookup}

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

private[module] trait TablesProjectionLowPriority:
  given emptyProjection[F[_], Source <: Tuple]
      : TablesProjection[F, EmptyTuple, Source] with
    def project(sourceTables: Tables[F, Source]): Tables[F, EmptyTuple] =
      EmptyTuple

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
  given identityProjection[F[_], S <: Tuple]: TablesProjection[F, S, S] with
    def project(sourceTables: Tables[F, S]): Tables[F, S] = sourceTables

  given prefixProjection[F[_], Head, Tail <: Tuple, T <: Tuple](using
      sizeS: ValueOf[Tuple.Size[Head *: Tail]],
  ): TablesProjection[F, Head *: Tail, (Head *: Tail) ++ T] with
    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def project(
        sourceTables: Tables[F, (Head *: Tail) ++ T],
    ): Tables[F, Head *: Tail] =
      sourceTables.take(sizeS.value).asInstanceOf[Tables[F, Head *: Tail]]

  given suffixProjection[F[_], S <: Tuple, Head, Tail <: Tuple](using
      sizeS: ValueOf[Tuple.Size[S]],
  ): TablesProjection[F, Head *: Tail, S ++ (Head *: Tail)] with
    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def project(
        sourceTables: Tables[F, S ++ (Head *: Tail)],
    ): Tables[F, Head *: Tail] =
      sourceTables.drop(sizeS.value).asInstanceOf[Tables[F, Head *: Tail]]

object TablesProvider:
  def empty[F[_]]: TablesProvider[F, EmptyTuple] =
    new TablesProvider[F, EmptyTuple]:
      def tables: Tables[F, EmptyTuple] = EmptyTuple

  def fromModule[F[
      _,
  ], Path <: Tuple, Schema <: Tuple, Needs <: Tuple, Txs <: Tuple, R](
      module: StateModule[F, Path, Schema, Needs, Txs, R],
  ): TablesProvider[F, Schema] = new TablesProvider[F, Schema]:
    def tables: Tables[F, Schema] = module.tables

  @scala.annotation.implicitNotFound(
"""Cannot merge provider schemas: overlapping tables detected.
Left schema: ${S1}
Right schema: ${S2}

Each Entry name must be unique across merged providers. Rename the tables or
ensure dependent modules expose disjoint schemas before composing providers."""
  )
  trait DisjointSchemas[S1 <: Tuple, S2 <: Tuple]

  object DisjointSchemas:
    given emptyEmpty: DisjointSchemas[EmptyTuple, EmptyTuple] =
      new DisjointSchemas[EmptyTuple, EmptyTuple] {}

    given emptyLeft[S <: Tuple]: DisjointSchemas[EmptyTuple, S] =
      new DisjointSchemas[EmptyTuple, S] {}

    given emptyRight[S <: Tuple]: DisjointSchemas[S, EmptyTuple] =
      new DisjointSchemas[S, EmptyTuple] {}

    given consDisjoint[Name <: String, K, V, T1 <: Tuple, S2 <: Tuple](using
        notInS2: NotInSchema[Entry[Name, K, V], S2],
        tailDisjoint: DisjointSchemas[T1, S2],
    ): DisjointSchemas[Entry[Name, K, V] *: T1, S2] =
      new DisjointSchemas[Entry[Name, K, V] *: T1, S2] {}

  @scala.annotation.implicitNotFound(
"""Table ${E} already exists in schema ${S}.

Dependency schemas must not redefine the same table name. Remove the duplicate
entry or refactor the module layout so that each table name is provided by a
single module."""
  )
  trait NotInSchema[E <: Entry[?, ?, ?], S <: Tuple]

  object NotInSchema:
    given notInEmpty[E <: Entry[?, ?, ?]]: NotInSchema[E, EmptyTuple] =
      new NotInSchema[E, EmptyTuple] {}

    given notInCons[N1 <: String, K1, V1, N2 <: String, K2, V2, Tail <: Tuple](
        using
        namesDiffer: DifferentNames[N1, N2],
        notInTail: NotInSchema[Entry[N1, K1, V1], Tail],
    ): NotInSchema[Entry[N1, K1, V1], Entry[N2, K2, V2] *: Tail] =
      new NotInSchema[Entry[N1, K1, V1], Entry[N2, K2, V2] *: Tail] {}

  def merge[F[_], P1 <: Tuple, P2 <: Tuple](
      p1: TablesProvider[F, P1],
      p2: TablesProvider[F, P2],
  )(using
      disjoint: DisjointSchemas[P1, P2],
  ): TablesProvider[F, P1 ++ P2] = new TablesProvider[F, P1 ++ P2]:
    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def tables: Tables[F, P1 ++ P2] =
      (p1.tables ++ p2.tables).asInstanceOf[Tables[F, P1 ++ P2]]
