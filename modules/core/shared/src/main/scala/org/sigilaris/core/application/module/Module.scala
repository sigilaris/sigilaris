package org.sigilaris.core.application.module

import scala.Tuple

type StateReducer[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple] =
  org.sigilaris.core.application.module.runtime.StateReducer[F, Path, Owns, Needs]

type RoutedStateReducer[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple] =
  org.sigilaris.core.application.module.runtime.RoutedStateReducer[F, Path, Owns, Needs]

type StateModule[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, R] =
  org.sigilaris.core.application.module.runtime.StateModule[F, Path, Owns, Needs, Txs, R]

val StateModule: org.sigilaris.core.application.module.runtime.StateModule.type =
  org.sigilaris.core.application.module.runtime.StateModule
