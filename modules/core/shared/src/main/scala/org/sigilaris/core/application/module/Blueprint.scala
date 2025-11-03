/** Exports for blueprint types (path-independent module specifications).
  *
  * Blueprints define module structure without concrete deployment paths:
  *   - [[org.sigilaris.core.application.module.blueprint.Blueprint]] - base trait
  *   - [[org.sigilaris.core.application.module.blueprint.ModuleBlueprint]] - single module
  *   - [[org.sigilaris.core.application.module.blueprint.ComposedBlueprint]] - composed modules
  *   - [[org.sigilaris.core.application.module.blueprint.StateReducer0]] - path-agnostic reducer
  *   - [[org.sigilaris.core.application.module.blueprint.RoutedStateReducer0]] - routed reducer
  *
  * @see [[org.sigilaris.core.application.module.runtime]] for path-bound versions
  */
package org.sigilaris.core.application.module

export org.sigilaris.core.application.module.blueprint.Blueprint
export org.sigilaris.core.application.module.blueprint.ModuleBlueprint
export org.sigilaris.core.application.module.blueprint.ComposedBlueprint
export org.sigilaris.core.application.module.blueprint.StateReducer0
export org.sigilaris.core.application.module.blueprint.RoutedStateReducer0
