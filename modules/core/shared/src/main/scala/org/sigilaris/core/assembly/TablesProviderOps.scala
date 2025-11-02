package org.sigilaris.core.assembly

/** Convenience extensions for deriving table providers from mounted modules.
  *
  * These helpers bridge the gap between module mounting (which yields a
  * `StateModule` exposing its owned tables) and downstream modules that depend
  * on those tables via `TablesProvider`. They simply forward to
  * `TablesProvider.fromModule`, but live in the support package so that higher
  * level assembly DSLs can import a single package and gain access to the
  * standard helpers.
  */
object TablesProviderOps:
  extension[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, R](
      module: org.sigilaris.core.application.module.runtime.StateModule[F, Path, Owns, Needs, Txs, R]
  )
    /** Derive a `TablesProvider` for the module's owned schema.
      *
      * This is the canonical way to expose a mounted module's tables to other
      * modules that list them in `Needs`. The provider captures the module's
      * live tables, so the caller should treat it as an immutable capability
      * and avoid leaking it beyond the intended assembly scope.
      */
    def toTablesProvider: org.sigilaris.core.application.module.provider.TablesProvider[F, Owns] =
      org.sigilaris.core.application.module.provider.TablesProvider.fromModule(module)
