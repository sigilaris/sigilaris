package org.sigilaris.core
package application
package accounts

/** Module-local brands for Accounts transactions to prevent cross-module mixing. */
opaque type AccountsEvent[+A] = A
object AccountsEvent:
  inline def apply[A](value: A): AccountsEvent[A] = value
  extension [A](event: AccountsEvent[A]) inline def value: A = event

opaque type AccountsResult[+A] = A
object AccountsResult:
  inline def apply[A](value: A): AccountsResult[A] = value
  extension [A](result: AccountsResult[A]) inline def value: A = result
