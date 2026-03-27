package org.sigilaris.node.jvm.runtime

trait NodeInitializer[F[_], -Services]:
  def initialize(services: Services): F[Unit]

object NodeInitializer:
  def apply[F[_], Services](f: Services => F[Unit]): NodeInitializer[F, Services] =
    new NodeInitializer[F, Services]:
      override def initialize(services: Services): F[Unit] =
        f(services)
