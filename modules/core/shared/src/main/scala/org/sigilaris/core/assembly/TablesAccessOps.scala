package org.sigilaris.core.assembly

/** Utilities for deriving and using schema evidence without manual summon chains.
  *
  * Provides convenient methods for:
  *   - Deriving [[org.sigilaris.core.application.support.compiletime.Lookup]] evidence
  *   - Deriving [[org.sigilaris.core.application.support.compiletime.Requires]] evidence
  *   - Accessing tables from providers with automatic evidence derivation
  *
  * These helpers reduce boilerplate when working with type-level schema constraints.
  *
  * @see [[org.sigilaris.core.application.support.compiletime.LookupAuto]]
  * @see [[org.sigilaris.core.application.support.compiletime.RequiresAuto]]
  */
object TablesAccessOps:
  /** Derive Lookup evidence without repeating summoning boilerplate. */
  transparent inline def deriveLookup[Schema <: Tuple, Name <: String, K, V]
      : org.sigilaris.core.application.support.compiletime.Lookup[Schema, Name, K, V] =
    org.sigilaris.core.application.support.compiletime.LookupAuto
      .derive[Schema, Name, K, V]

  /** Derive Requires evidence without repeating summoning boilerplate. */
  transparent inline def deriveRequires[Needs <: Tuple, Schema <: Tuple]
      : org.sigilaris.core.application.support.compiletime.Requires[Needs, Schema] =
    org.sigilaris.core.application.support.compiletime.RequiresAuto
      .derive[Needs, Schema]

  /** Table access extension for dependency providers. */
  extension[F[_], Schema <: Tuple](
      provider: org.sigilaris.core.application.module.provider.TablesProvider[F, Schema]
  )
    transparent inline def providedTable[Name <: String, K, V](using
        lookup: org.sigilaris.core.application.support.compiletime.Lookup[Schema, Name, K, V],
    ) =
      lookup.table(provider.tables)
