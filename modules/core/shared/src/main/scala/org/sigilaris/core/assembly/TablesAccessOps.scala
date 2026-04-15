package org.sigilaris.core.assembly

/** Utilities for deriving and using schema evidence without manual summon
  * chains.
  *
  * Provides convenient methods for:
  *   - Deriving [[org.sigilaris.core.application.support.compiletime.Lookup]]
  *     evidence
  *   - Deriving [[org.sigilaris.core.application.support.compiletime.Requires]]
  *     evidence
  *   - Accessing tables from providers with automatic evidence derivation
  *
  * These helpers reduce boilerplate when working with type-level schema
  * constraints.
  *
  * @see
  *   [[org.sigilaris.core.application.support.compiletime.LookupAuto]]
  * @see
  *   [[org.sigilaris.core.application.support.compiletime.RequiresAuto]]
  */
object TablesAccessOps:
  /** Derive [[org.sigilaris.core.application.support.compiletime.Lookup]] evidence without repeating summoning boilerplate.
    *
    * @tparam Schema
    *   the schema tuple to search
    * @tparam Name
    *   the entry name to look up
    * @tparam K
    *   the expected key type
    * @tparam V
    *   the expected value type
    * @return
    *   a [[org.sigilaris.core.application.support.compiletime.Lookup]] witness for the specified entry
    */
  transparent inline def deriveLookup[Schema <: Tuple, Name <: String, K, V]
      : org.sigilaris.core.application.support.compiletime.Lookup[
        Schema,
        Name,
        K,
        V,
      ] =
    org.sigilaris.core.application.support.compiletime.LookupAuto
      .derive[Schema, Name, K, V]

  /** Derive [[org.sigilaris.core.application.support.compiletime.Requires]] evidence without repeating summoning boilerplate.
    *
    * @tparam Needs
    *   the tuple of entries that a module requires
    * @tparam Schema
    *   the schema tuple that must satisfy those requirements
    * @return
    *   a [[org.sigilaris.core.application.support.compiletime.Requires]] witness proving `Schema` covers `Needs`
    */
  transparent inline def deriveRequires[Needs <: Tuple, Schema <: Tuple]
      : org.sigilaris.core.application.support.compiletime.Requires[
        Needs,
        Schema,
      ] =
    org.sigilaris.core.application.support.compiletime.RequiresAuto
      .derive[Needs, Schema]

  /** Table access extension for dependency providers.
    *
    * @tparam F
    *   the effect type
    * @tparam Schema
    *   the provider's schema tuple
    */
  extension [F[_], Schema <: Tuple](
      provider: org.sigilaris.core.application.module.provider.TablesProvider[F, Schema]
  )

    /** Retrieve a specific table from this provider using implicit [[org.sigilaris.core.application.support.compiletime.Lookup]] evidence.
      *
      * @tparam Name
      *   the entry name to look up
      * @tparam K
      *   the key type
      * @tparam V
      *   the value type
      * @return
      *   the table instance corresponding to the named entry
      */
    transparent inline def providedTable[Name <: String, K, V](using
        lookup: org.sigilaris.core.application.support.compiletime.Lookup[
          Schema,
          Name,
          K,
          V,
        ],
    ) =
      lookup.table(provider.tables)
