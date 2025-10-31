package org.sigilaris.core
package application
package group

/** Module-local brands for Groups transactions to prevent cross-module mixing. */
opaque type GroupsEvent[+A] = A
object GroupsEvent:
  inline def apply[A](value: A): GroupsEvent[A] = value
  extension [A](event: GroupsEvent[A]) inline def value: A = event

opaque type GroupsResult[+A] = A
object GroupsResult:
  inline def apply[A](value: A): GroupsResult[A] = value
  extension [A](result: GroupsResult[A]) inline def value: A = result
