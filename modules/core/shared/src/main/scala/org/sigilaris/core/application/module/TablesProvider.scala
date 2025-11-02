package org.sigilaris.core.application.module

import scala.Tuple

type TablesProvider[F[_], Provides <: Tuple] =
  org.sigilaris.core.application.module.provider.TablesProvider[F, Provides]

type TablesProjection[F[_], Subset <: Tuple, Source <: Tuple] =
  org.sigilaris.core.application.module.provider.TablesProjection[F, Subset, Source]

val TablesProvider: org.sigilaris.core.application.module.provider.TablesProvider.type =
  org.sigilaris.core.application.module.provider.TablesProvider

val TablesProjection: org.sigilaris.core.application.module.provider.TablesProjection.type =
  org.sigilaris.core.application.module.provider.TablesProjection
