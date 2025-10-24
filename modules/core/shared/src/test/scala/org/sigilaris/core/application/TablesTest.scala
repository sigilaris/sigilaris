package org.sigilaris.core
package application

import cats.Id
import munit.FunSuite

import datatype.Utf8

class TablesTest extends FunSuite:

  test("Entry holds codec instances"):
    val userEntry = Entry["users", Utf8, Long]
    val postEntry = Entry["posts", Utf8, Long]

    // Verify codecs are accessible
    assert(userEntry.kCodec != null, "userEntry.kCodec should not be null")
    assert(userEntry.vCodec != null, "userEntry.vCodec should not be null")
    assert(postEntry.kCodec != null, "postEntry.kCodec should not be null")
    assert(postEntry.vCodec != null, "postEntry.vCodec should not be null")

  test("TableOf type compiles correctly"):
    // This is a compile-time check - if it compiles, the test passes
    summon[TableOf[Id, Entry["users", Utf8, Long]] <:< StateTable[Id]]
    summon[TableOf[Id, Entry["posts", Utf8, Long]] <:< StateTable[Id]]

  test("Tables type maps schema tuple to table tuple"):
    // Define a schema
    type Schema = (Entry["users", Utf8, Long], Entry["posts", Utf8, Long])

    // This should compile - Tables[Id, Schema] should be a tuple of tables
    type TableTuple = Tables[Id, Schema]

    // Verify it's a 2-element tuple type
    summon[TableTuple <:< Tuple]
    summon[Tuple.Size[TableTuple] =:= 2]
