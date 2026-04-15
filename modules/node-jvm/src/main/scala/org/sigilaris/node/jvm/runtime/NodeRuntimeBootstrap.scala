package org.sigilaris.node.jvm.runtime

import cats.effect.kernel.Resource

/** Strategy for bootstrapping node services in either in-memory or persistent
  * mode.
  *
  * @tparam F
  *   effect type
  * @tparam Config
  *   configuration type
  * @tparam Services
  *   the service bundle type to produce
  * @tparam Layout
  *   the persistent storage layout type
  */
trait NodeRuntimeBootstrap[F[_], Config, Services, Layout]:

  /** Bootstraps services using a purely in-memory backend.
    *
    * @param config
    *   application configuration
    * @return
    *   a resource managing the in-memory service lifecycle
    */
  def inMemory(config: Config): Resource[F, Services]

  /** Bootstraps services using a persistent storage backend.
    *
    * @param config
    *   application configuration
    * @param layout
    *   the storage layout descriptor
    * @return
    *   a resource managing the persistent service lifecycle
    */
  def persistent(config: Config, layout: Layout): Resource[F, Services]
