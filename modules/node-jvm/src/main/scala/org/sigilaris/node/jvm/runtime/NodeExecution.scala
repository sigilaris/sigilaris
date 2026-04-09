package org.sigilaris.node.jvm.runtime

import cats.effect.kernel.Resource

/** Entry point for composing node runtime lifecycle: bootstrap, initialize, and
  * serve.
  */
object NodeExecution:

  /** Builds a resource that bootstraps the runtime, runs initialization, and
    * then serves using the provided function.
    *
    * @tparam F
    *   effect type
    * @tparam Services
    *   the service bundle type provided by the runtime
    * @tparam Layout
    *   the persistent storage layout type
    * @tparam A
    *   the result type of the serving resource
    * @param runtime
    *   resource that produces a `NodeRuntime`
    * @param initializer
    *   initialization logic to run before serving
    * @param serve
    *   function that creates the serving resource from services
    * @return
    *   a resource managing the full node lifecycle
    */
  def resource[F[_], Services, Layout, A](
      runtime: Resource[F, NodeRuntime[Services, Layout]],
      initializer: NodeInitializer[F, Services],
      serve: Services => Resource[F, A],
  ): Resource[F, A] =
    runtime.flatMap: handle =>
      Resource
        .eval(initializer.initialize(handle.services))
        .flatMap(_ => serve(handle.services))
