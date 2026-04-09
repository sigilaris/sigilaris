package org.sigilaris.node.jvm.runtime

import cats.effect.kernel.Resource

object NodeExecution:
  def resource[F[_], Services, Layout, A](
      runtime: Resource[F, NodeRuntime[Services, Layout]],
      initializer: NodeInitializer[F, Services],
      serve: Services => Resource[F, A],
  ): Resource[F, A] =
    runtime.flatMap: handle =>
      Resource
        .eval(initializer.initialize(handle.services))
        .flatMap(_ => serve(handle.services))
