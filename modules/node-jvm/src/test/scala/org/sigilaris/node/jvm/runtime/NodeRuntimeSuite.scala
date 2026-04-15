package org.sigilaris.node.jvm.runtime

import cats.effect.IO
import cats.effect.kernel.Resource
import munit.CatsEffectSuite

import org.sigilaris.node.jvm.storage.SingleValueStore
import org.sigilaris.node.jvm.storage.memory.InMemoryStores

final class NodeRuntimeSuite extends CatsEffectSuite:

  final case class TestConfig(name: String)
  final case class TestLayout(path: String)
  final case class TestServices(
      state: SingleValueStore[IO, String],
      label: String,
  )

  given NodeRuntimeBootstrap[IO, TestConfig, TestServices, TestLayout] with
    override def inMemory(config: TestConfig): Resource[IO, TestServices] =
      InMemoryStores
        .singleValue[IO, String]
        .map(store => TestServices(store, s"in-memory:${config.name}"))

    override def persistent(
        config: TestConfig,
        layout: TestLayout,
    ): Resource[IO, TestServices] =
      InMemoryStores
        .singleValue[IO, String]
        .map(store =>
          TestServices(store, s"persistent:${config.name}:${layout.path}"),
        )

  test("StorageMode.fromArgs selects in-memory when the flag is present"):
    IO:
      val selected =
        StorageMode.fromArgs(List("--in-memory"), TestLayout("data"))
      assertEquals(selected, StorageMode.InMemory)

  test("StorageMode.fromArgs selects persistent when the flag is absent"):
    IO:
      val selected = StorageMode.fromArgs(List("--other"), TestLayout("disk"))
      assertEquals(selected, StorageMode.Persistent(TestLayout("disk")))

  test("StorageMode.fromArgs supports a custom in-memory flag"):
    IO:
      val selected = StorageMode.fromArgs(
        args = List("--ram"),
        persistentLayout = TestLayout("disk"),
        inMemoryFlag = "--ram",
      )
      assertEquals(selected, StorageMode.InMemory)

  test(
    "NodeRuntime.resource selects the persistent bootstrap for persistent mode",
  ):
    val mode = StorageMode.Persistent(TestLayout("disk"))
    NodeRuntime
      .resource[IO, TestConfig, TestServices, TestLayout](
        TestConfig("alpha"),
        mode,
      )
      .use: runtime =>
        IO:
          assertEquals(runtime.storage, mode)
          assertEquals(runtime.services.label, "persistent:alpha:disk")

  test("NodeExecution runs the initializer before the server resource"):
    val runtime = NodeRuntime
      .resource[IO, TestConfig, TestServices, TestLayout](
        TestConfig("beta"),
        StorageMode.InMemory,
      )

    val initializer =
      NodeInitializer[IO, TestServices](_.state.put("initialized"))

    NodeExecution
      .resource(
        runtime,
        initializer,
        services =>
          Resource.eval:
            services.state
              .get()
              .value
              .map: state =>
                assertEquals(state, Right(Some("initialized")))
                services.label,
      )
      .use: label =>
        IO:
          assertEquals(label, "in-memory:beta")
