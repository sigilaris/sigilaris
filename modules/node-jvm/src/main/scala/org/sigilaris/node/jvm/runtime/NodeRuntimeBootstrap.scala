package org.sigilaris.node.jvm.runtime

import cats.effect.kernel.Resource

trait NodeRuntimeBootstrap[F[_], Config, Services, Layout]:
  def inMemory(config: Config): Resource[F, Services]
  def persistent(config: Config, layout: Layout): Resource[F, Services]
