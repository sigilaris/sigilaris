package org.sigilaris.node.jvm.runtime

import cats.effect.kernel.Resource

final case class NodeRuntime[Services, Layout](
    services: Services,
    storage: StorageMode[Layout],
)

object NodeRuntime:
  def resource[F[_], Config, Services, Layout](
      config: Config,
      mode: StorageMode[Layout],
  )(using bootstrap: NodeRuntimeBootstrap[F, Config, Services, Layout]): Resource[F, NodeRuntime[Services, Layout]] =
    val servicesResource = mode match
      case StorageMode.InMemory =>
        bootstrap.inMemory(config)
      case StorageMode.Persistent(layout) =>
        bootstrap.persistent(config, layout)

    servicesResource.map(NodeRuntime(_, mode))
