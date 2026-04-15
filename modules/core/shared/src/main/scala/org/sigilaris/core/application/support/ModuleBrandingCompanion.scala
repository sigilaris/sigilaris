package org.sigilaris.core.application.support

/** Shared helper for module-local opaque branding wrappers. */
trait ModuleBrandingCompanion:
  protected inline def wrap[A](value: A): A = value

  protected inline def unwrap[A](value: A): A = value
