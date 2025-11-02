package org.sigilaris.core.application.support.compiletime

import munit.FunSuite

import org.sigilaris.core.application.state.Entry
import org.sigilaris.core.datatype.Utf8

class RequiresAutoSuite extends FunSuite:
  private type Schema =
    Entry["foo", Utf8, Utf8] *:
      Entry["bar", Utf8, Utf8] *:
      EmptyTuple

  private type NeedsOk = Entry["foo", Utf8, Utf8] *: EmptyTuple
  test("derive summons requires evidence when subset matches"):
    val evidence = RequiresAuto.derive[NeedsOk, Schema]
    assert(evidence ne null)

  test("derive emits descriptive error when requirement absent"):
    val errors = compileErrors(
      "RequiresAuto.derive[Entry[\"baz\", Utf8, Utf8] *: EmptyTuple, Schema]"
    )
    assert(clue(errors).contains("Requires derivation failed."))
    assert(
      clue(errors).contains(
        "Needs: org.sigilaris.core.application.state.Entry[\"baz\", org.sigilaris.core.datatype.Utf8$package.Utf8, org.sigilaris.core.datatype.Utf8$package.Utf8]"
      )
    )
    assert(
      clue(errors).contains(
        "Schema: org.sigilaris.core.application.state.Entry[\"foo\", org.sigilaris.core.datatype.Utf8$package.Utf8, org.sigilaris.core.datatype.Utf8$package.Utf8] *: org.sigilaris.core.application.state.Entry[\"bar\", org.sigilaris.core.datatype.Utf8$package.Utf8, org.sigilaris.core.datatype.Utf8$package.Utf8]"
      )
    )
