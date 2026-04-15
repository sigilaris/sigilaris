package org.sigilaris.node.jvm.runtime

/** Effectful initialization step executed after runtime bootstrap and before
  * serving.
  *
  * @tparam F
  *   effect type
  * @tparam Services
  *   the service bundle type (contravariant)
  */
trait NodeInitializer[F[_], -Services]:

  /** Performs one-time initialization using the provided services.
    *
    * @param services
    *   the bootstrapped service bundle
    * @return
    *   effect completing when initialization is done
    */
  def initialize(services: Services): F[Unit]

/** Companion for `NodeInitializer` providing a functional constructor. */
object NodeInitializer:

  /** Creates a `NodeInitializer` from a function.
    *
    * @tparam F
    *   effect type
    * @tparam Services
    *   the service bundle type
    * @param f
    *   initialization function
    * @return
    *   a node initializer wrapping `f`
    */
  def apply[F[_], Services](
      f: Services => F[Unit],
  ): NodeInitializer[F, Services] =
    new NodeInitializer[F, Services]:
      override def initialize(services: Services): F[Unit] =
        f(services)
