package org.sigilaris.core
package application
package support

import scala.compiletime.summonInline

/** Utilities for deriving and using schema evidence without manual summon chains. */
object TablesAccessOps:
  /** Derive Lookup evidence without repeating summoning boilerplate. */
  transparent inline def deriveLookup[Schema <: Tuple, Name <: String, K, V]
      : Lookup[Schema, Name, K, V] = summonInline[Lookup[Schema, Name, K, V]]

  /** Derive Requires evidence without repeating summoning boilerplate. */
  transparent inline def deriveRequires[Needs <: Tuple, Schema <: Tuple]
      : Requires[Needs, Schema] = summonInline[Requires[Needs, Schema]]

  /** Table access extension for dependency providers. */
  extension[F[_], Schema <: Tuple](provider: TablesProvider[F, Schema])
    transparent inline def providedTable[Name <: String, K, V](using
        lookup: Lookup[Schema, Name, K, V],
    ) =
      lookup.table(provider.tables)
