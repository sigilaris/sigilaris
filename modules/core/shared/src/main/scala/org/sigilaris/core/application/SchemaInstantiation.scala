package org.sigilaris.core
package application

import cats.Monad
import scodec.bits.ByteVector
import merkle.MerkleTrie

/** Typeclass for instantiating tables from Entry instances.
  *
  * This uses the typeclass derivation pattern to avoid inline match issues with
  * abstract type parameters. Given instances are automatically derived for
  * EmptyTuple and cons cases at compile time.
  *
  * @tparam F
  *   the effect type
  * @tparam Path
  *   the mount path tuple
  * @tparam Schema
  *   the schema tuple type
  */
trait SchemaMapper[F[_], Path <: Tuple, Schema <: Tuple]:
  /** Instantiate tables from a schema tuple of Entry instances.
    *
    * @param schema
    *   the runtime tuple of Entry instances
    * @return
    *   a tuple of StateTable instances
    */
  def instantiate(schema: Schema): Tables[F, Schema]

object SchemaMapper:
  /** Base case: empty schema produces no tables. */
  given empty[F[_], Path <: Tuple]: SchemaMapper[F, Path, EmptyTuple] with
    def instantiate(schema: EmptyTuple): Tables[F, EmptyTuple] =
      EmptyTuple

  /** Inductive case: process head Entry and recurse on tail.
    *
    * For each Entry[N, K, V] in the schema:
    *   1. Compute the table prefix from Path and table name N 2. Create a
    *      StateTable using Entry.createTable 3. Recursively process the tail 4.
    *      Cons the results together
    */
  given cons[F[_]: Monad, Path <: Tuple, N <: String, K, V, Tail <: Tuple](using
      tailMapper: SchemaMapper[F, Path, Tail],
      nodeStore: MerkleTrie.NodeStore[F],
      pathEncoder: PathEncoder[Path],
  ): SchemaMapper[F, Path, Entry[N, K, V] *: Tail] with
    def instantiate(
        schema: Entry[N, K, V] *: Tail,
    ): Tables[F, Entry[N, K, V] *: Tail] =
      val head               = schema.head
      val tail               = schema.tail
      val prefix: ByteVector = tablePrefixRuntime[Path](head.tableName)
      val table              = head.createTable[F](prefix)
      val restTables         = tailMapper.instantiate(tail)
      table *: restTables

/** Helper object for convenient access to schema instantiation. */
object SchemaInstantiation:
  /** Instantiate tables from a schema tuple of Entry instances.
    *
    * This is a convenience method that delegates to the SchemaMapper typeclass.
    *
    * Note: The Monad[F] and NodeStore[F] constraints are not explicitly listed
    * here because they are required by the SchemaMapper instance itself.
    *
    * @tparam F
    *   the effect type
    * @tparam Path
    *   the mount path tuple
    * @tparam Schema
    *   the schema tuple type
    * @param schema
    *   the runtime tuple of Entry instances
    * @param mapper
    *   the SchemaMapper instance (automatically derived)
    * @return
    *   a tuple of StateTable instances
    */
  def instantiateTablesFromEntries[F[_], Path <: Tuple, Schema <: Tuple](
      schema: Schema,
  )(using
      SchemaMapper[F, Path, Schema],
  ): Tables[F, Schema] =
    summon[SchemaMapper[F, Path, Schema]].instantiate(schema)
