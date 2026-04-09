package org.sigilaris.core.application.feature.group.domain

/** Branded event wrapper for the Groups module, preventing cross-module event mixing.
  *
  * @tparam A the underlying event type
  */
opaque type GroupsEvent[+A] = A

/** Companion for [[GroupsEvent]], providing construction and extraction. */
object GroupsEvent:
  /** Wraps a value as a groups-branded event.
    *
    * @tparam A the underlying event type
    * @param value the event payload
    * @return the branded event
    */
  inline def apply[A](value: A): GroupsEvent[A]            = value

  extension [A](event: GroupsEvent[A])
    /** Extracts the underlying event payload.
      *
      * @return the unwrapped event value
      */
    inline def value: A = event

/** Branded result wrapper for the Groups module, preventing cross-module result mixing.
  *
  * @tparam A the underlying result type
  */
opaque type GroupsResult[+A] = A

/** Companion for [[GroupsResult]], providing construction and extraction. */
object GroupsResult:
  /** Wraps a value as a groups-branded result.
    *
    * @tparam A the underlying result type
    * @param value the result payload
    * @return the branded result
    */
  inline def apply[A](value: A): GroupsResult[A]             = value

  extension [A](result: GroupsResult[A])
    /** Extracts the underlying result payload.
      *
      * @return the unwrapped result value
      */
    inline def value: A = result
