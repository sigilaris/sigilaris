package org.sigilaris.core.application.support.compiletime

import munit.FunSuite

import org.sigilaris.core.application.state.Entry
import org.sigilaris.core.datatype.Utf8

class LookupAutoSuite extends FunSuite:
  private type TestSchema =
    Entry["foo", Utf8, Utf8] *:
      Entry["bar", Utf8, Utf8] *:
      EmptyTuple

  test("derive summons existing lookup evidence"):
    val lookup = LookupAuto.derive[TestSchema, "foo", Utf8, Utf8]
    assert(lookup ne null)

  test("derive emits descriptive error when table missing"):
    val errors = compileErrors(
      "LookupAuto.derive[TestSchema, \"baz\", Utf8, Utf8]"
    )
    assert(clue(errors).contains("Lookup derivation failed."))
    assert(
      clue(errors).contains(
        "Missing entry: Entry[\"baz\", org.sigilaris.core.datatype.Utf8$package.Utf8, org.sigilaris.core.datatype.Utf8$package.Utf8]"
      )
    )
