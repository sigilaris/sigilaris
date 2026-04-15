package org.sigilaris.core.application.feature.accounts.domain

import org.sigilaris.core.application.support.ModuleBrandingCompanion

/** Branded event wrapper for the Accounts module, preventing cross-module event mixing.
  *
  * @tparam A the underlying event type
  */
opaque type AccountsEvent[+A] = A

/** Companion for [[AccountsEvent]], providing construction and extraction. */
object AccountsEvent extends ModuleBrandingCompanion:
  /** Wraps a value as an accounts-branded event.
    *
    * @tparam A the underlying event type
    * @param value the event payload
    * @return the branded event
    */
  inline def apply[A](value: A): AccountsEvent[A] = wrap(value)

  extension [A](event: AccountsEvent[A])
    /** Extracts the underlying event payload.
      *
      * @return the unwrapped event value
      */
    inline def value: A = unwrap(event)

/** Branded result wrapper for the Accounts module, preventing cross-module result mixing.
  *
  * @tparam A the underlying result type
  */
opaque type AccountsResult[+A] = A

/** Companion for [[AccountsResult]], providing construction and extraction. */
object AccountsResult extends ModuleBrandingCompanion:
  /** Wraps a value as an accounts-branded result.
    *
    * @tparam A the underlying result type
    * @param value the result payload
    * @return the branded result
    */
  inline def apply[A](value: A): AccountsResult[A] = wrap(value)

  extension [A](result: AccountsResult[A])
    /** Extracts the underlying result payload.
      *
      * @return the unwrapped result value
      */
    inline def value: A = unwrap(result)
