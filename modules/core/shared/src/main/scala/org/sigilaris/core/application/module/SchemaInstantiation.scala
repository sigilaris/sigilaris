/** Exports for schema instantiation machinery.
  *
  * Provides typeclass-based table instantiation from Entry tuples:
  *   - [[org.sigilaris.core.application.module.blueprint.SchemaMapper]] - typeclass for table creation
  *   - [[org.sigilaris.core.application.module.blueprint.SchemaInstantiation]] - convenience helpers
  *
  * Used internally during module mounting to convert Entry[Name, K, V] into StateTable[F] instances
  * with computed byte prefixes.
  */
package org.sigilaris.core.application.module

export org.sigilaris.core.application.module.blueprint.SchemaInstantiation
export org.sigilaris.core.application.module.blueprint.SchemaMapper
