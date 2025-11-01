package org.sigilaris.core.assembly

import scala.compiletime.summonInline

/** Utilities for deriving and using schema evidence without manual summon chains. */
object TablesAccessOps:
  /** Derive Lookup evidence without repeating summoning boilerplate. */
  transparent inline def deriveLookup[Schema <: Tuple, Name <: String, K, V]
      : org.sigilaris.core.application.support.Lookup[Schema, Name, K, V] =
    summonInline[org.sigilaris.core.application.support.Lookup[Schema, Name, K, V]]

  /** Derive Requires evidence without repeating summoning boilerplate. */
  transparent inline def deriveRequires[Needs <: Tuple, Schema <: Tuple]
      : org.sigilaris.core.application.support.Requires[Needs, Schema] =
    summonInline[org.sigilaris.core.application.support.Requires[Needs, Schema]]

  /** Table access extension for dependency providers. */
  extension[F[_], Schema <: Tuple](
      provider: org.sigilaris.core.application.module.TablesProvider[F, Schema]
  )
    transparent inline def providedTable[Name <: String, K, V](using
        lookup: org.sigilaris.core.application.support.Lookup[Schema, Name, K, V],
    ) =
      lookup.table(provider.tables)
