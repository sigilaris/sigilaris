package org.sigilaris.node.jvm.runtime

import cats.effect.kernel.Resource

/** Handle holding the bootstrapped services and the active storage mode.
  *
  * @tparam Services
  *   the service bundle type
  * @tparam Layout
  *   the persistent storage layout type
  * @param services
  *   the bootstrapped service bundle
  * @param storage
  *   the storage mode used by this runtime
  */
final case class NodeRuntime[Services, Layout](
    services: Services,
    storage: StorageMode[Layout],
)

/** Companion for `NodeRuntime` providing resource-based construction. */
object NodeRuntime:

  /** Creates a `NodeRuntime` resource by delegating to the appropriate
    * `NodeRuntimeBootstrap` strategy based on the storage mode.
    *
    * @tparam F
    *   effect type
    * @tparam Config
    *   configuration type
    * @tparam Services
    *   the service bundle type
    * @tparam Layout
    *   the persistent storage layout type
    * @param config
    *   application configuration
    * @param mode
    *   the desired storage mode
    * @param bootstrap
    *   implicit bootstrap strategy
    * @return
    *   a resource managing the runtime lifecycle
    */
  def resource[F[_], Config, Services, Layout](
      config: Config,
      mode: StorageMode[Layout],
  )(using
      bootstrap: NodeRuntimeBootstrap[F, Config, Services, Layout],
  ): Resource[F, NodeRuntime[Services, Layout]] =
    val servicesResource = mode match
      case StorageMode.InMemory =>
        bootstrap.inMemory(config)
      case StorageMode.Persistent(layout) =>
        bootstrap.persistent(config, layout)

    servicesResource.map(NodeRuntime(_, mode))
