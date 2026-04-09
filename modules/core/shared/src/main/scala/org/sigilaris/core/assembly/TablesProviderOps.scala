package org.sigilaris.core.assembly

/** Convenience extensions for deriving table providers from mounted modules.
  *
  * These helpers bridge the gap between module mounting (which yields a
  * [[org.sigilaris.core.application.module.runtime.StateModule]] exposing its
  * owned tables) and downstream modules that depend on those tables via
  * [[org.sigilaris.core.application.module.provider.TablesProvider]].
  *
  * They simply forward to `TablesProvider.fromModule`, but live in the assembly
  * package so that higher level assembly DSLs can import a single package and
  * gain access to the standard helpers.
  *
  * @see
  *   [[org.sigilaris.core.application.module.provider.TablesProvider.fromModule]]
  */
object TablesProviderOps:

  /** Extension providing `toTablesProvider` on any mounted [[org.sigilaris.core.application.module.runtime.StateModule]].
    *
    * @tparam F
    *   the effect type
    * @tparam Path
    *   the module's mount path tuple
    * @tparam Owns
    *   the module's owned schema tuple
    * @tparam Needs
    *   the module's dependency schema tuple
    * @tparam Txs
    *   the module's transaction types tuple
    * @tparam R
    *   the module's reducer type
    */
  extension [F[
      _,
  ], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, R](
      module: org.sigilaris.core.application.module.runtime.StateModule[F, Path, Owns, Needs, Txs, R]
  )
    /** Derive a `TablesProvider` for the module's owned schema.
      *
      * This is the canonical way to expose a mounted module's tables to other
      * modules that list them in `Needs`. The provider captures the module's
      * live tables, so the caller should treat it as an immutable capability
      * and avoid leaking it beyond the intended assembly scope.
      *
      * @return
      *   a [[org.sigilaris.core.application.module.provider.TablesProvider]] exposing the module's owned tables
      */
    def toTablesProvider
        : org.sigilaris.core.application.module.provider.TablesProvider[
          F,
          Owns,
        ] =
      org.sigilaris.core.application.module.provider.TablesProvider
        .fromModule(module)
