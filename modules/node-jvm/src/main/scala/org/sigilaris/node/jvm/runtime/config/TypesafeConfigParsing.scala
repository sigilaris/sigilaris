package org.sigilaris.node.jvm.runtime.config

import java.time.Duration

import scala.jdk.CollectionConverters.*

import cats.syntax.all.*

import com.typesafe.config.Config

import org.sigilaris.core.util.SafeStringInterp.*

/** Alias-aware Typesafe Config parsing helpers.
  *
  * The scaffold keeps raw config parsing separate from domain-level validation
  * so higher layers can reuse the same input models for property and regression
  * tests.
  */
object TypesafeConfigParsing:
  final case class ConfigAliases(primary: String, alternates: List[String]):
    def all: List[String] = primary :: alternates

  object ConfigAliases:
    def apply(primary: String, alternates: String*): ConfigAliases =
      new ConfigAliases(primary = primary, alternates = alternates.toList)

  trait ConfigReader[A]:
    def read(config: Config, path: String): Either[String, A]

  object ConfigReader:
    val string: ConfigReader[String] = (config, path) =>
      Either.catchNonFatal(config.getString(path)).leftMap(_.getMessage)

    val stringList: ConfigReader[List[String]] = (config, path) =>
      Either
        .catchNonFatal(config.getStringList(path).asScala.toList)
        .leftMap(_.getMessage)

    val configSection: ConfigReader[Config] = (config, path) =>
      Either.catchNonFatal(config.getConfig(path)).leftMap(_.getMessage)

    val configList: ConfigReader[List[Config]] = (config, path) =>
      Either
        .catchNonFatal(config.getConfigList(path).asScala.toList)
        .leftMap(_.getMessage)

    val int: ConfigReader[Int] = (config, path) =>
      Either.catchNonFatal(config.getInt(path)).leftMap(_.getMessage)

    val long: ConfigReader[Long] = (config, path) =>
      Either.catchNonFatal(config.getLong(path)).leftMap(_.getMessage)

    val boolean: ConfigReader[Boolean] = (config, path) =>
      Either.catchNonFatal(config.getBoolean(path)).leftMap(_.getMessage)

    val durationMillis: ConfigReader[Duration] = (config, path) =>
      long.read(config, path).map(Duration.ofMillis)

    val stringMap: ConfigReader[Map[String, String]] = (config, path) =>
      configSection.read(config, path).flatMap: section =>
        section.root().entrySet().asScala.toList
          .traverse: entry =>
            Either
              .catchNonFatal(section.getString(entry.getKey))
              .leftMap(_.getMessage)
              .map(entry.getKey -> _)
          .map(_.toMap)

  final case class ConfigField[A](
      aliases: ConfigAliases,
      reader: ConfigReader[A],
  ):
    def required(config: Config): Either[String, A] =
      resolvePath(config, aliases)
        .toRight(ss"missing required config key: ${aliases.primary}")
        .flatMap(reader.read(config, _))

    def optional(config: Config): Either[String, Option[A]] =
      resolvePath(config, aliases) match
        case Some(path) => reader.read(config, path).map(_.some)
        case None       => none[A].asRight[String]

    def optionalOrDefault(
        config: Config,
        default: => A,
    ): Either[String, A] =
      optional(config).map(_.getOrElse(default))

  def requiredSection(
      config: Config,
      path: String,
  ): Either[String, Config] =
    Either
      .cond(
        config.hasPath(path),
        config.getConfig(path),
        ss"missing config path: ${path}",
      )

  private def resolvePath(
      config: Config,
      aliases: ConfigAliases,
  ): Option[String] =
    aliases.all.find(config.hasPath)
