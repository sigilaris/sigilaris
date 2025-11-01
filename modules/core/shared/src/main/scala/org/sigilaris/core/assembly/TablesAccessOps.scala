package org.sigilaris.core.assembly

/** Utilities for deriving and using schema evidence without manual summon chains. */
object TablesAccessOps:
  /** Derive Lookup evidence without repeating summoning boilerplate. */
  transparent inline def deriveLookup[Schema <: Tuple, Name <: String, K, V]
      : org.sigilaris.core.application.support.Lookup[Schema, Name, K, V] =
    org.sigilaris.core.application.support.LookupAuto
      .derive[Schema, Name, K, V]

  /** Derive Requires evidence without repeating summoning boilerplate. */
  transparent inline def deriveRequires[Needs <: Tuple, Schema <: Tuple]
      : org.sigilaris.core.application.support.Requires[Needs, Schema] =
    org.sigilaris.core.application.support.RequiresAuto
      .derive[Needs, Schema]

  /** Table access extension for dependency providers. */
  extension[F[_], Schema <: Tuple](
      provider: org.sigilaris.core.application.module.TablesProvider[F, Schema]
  )
    transparent inline def providedTable[Name <: String, K, V](using
        lookup: org.sigilaris.core.application.support.Lookup[Schema, Name, K, V],
    ) =
      lookup.table(provider.tables)
