package org.sigilaris.tools

import ujson.*
import java.nio.file.{Files, Paths}
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import java.time.ZonedDateTime
import scala.jdk.CollectionConverters.*

object BenchGuard:
  final case class Cfg(
      baseline: Option[String],
      result: String,
      tag: Option[String],
      enableGc: Boolean,
      include: Option[String],
      opsDropPct: Double,
      bytesIncrPct: Double,
      gcTimeIncrPct: Double,
  )

  private def arg(args: List[String], key: String): Option[String] =
    args.sliding(2).collectFirst { case List(k, v) if k == key => v }

  private def flag(args: List[String], key: String): Boolean =
    args.contains(key)

  private def envD(name: String, default: Double): Double =
    sys.env.get(name).flatMap(s => s.toDoubleOption).getOrElse(default)

  private def usage(): String =
    """
      |Usage: sbt "tools/run --result target/jmh-result.json [--baseline <path>] [--tag <tag>] [--gc]"
      |
      |Env thresholds:
      |  OPS_DROP_PCT (default 2)
      |  BYTES_INCR_PCT (default 5)
      |  GC_TIME_INCR_PCT (default 5)
      |""".stripMargin

  def main(raw: Array[String]): Unit =
    val args = raw.toList
    if !args.contains("--result") then
      Console.err.println(usage())
      sys.exit(2)

    val cfg = Cfg(
      baseline = arg(args, "--baseline"),
      result = arg(args, "--result").get,
      tag = arg(args, "--tag"),
      enableGc = flag(args, "--gc"),
      include = arg(args, "--include"),
      opsDropPct = envD("OPS_DROP_PCT", 2.0),
      bytesIncrPct = envD("BYTES_INCR_PCT", 5.0),
      gcTimeIncrPct = envD("GC_TIME_INCR_PCT", 5.0),
    )

    val repoRoot = Paths.get(".")
    val reports  = repoRoot.resolve("benchmarks").resolve("reports")
    Files.createDirectories(reports)

    val baseline = cfg.baseline.orElse(discoverBaseline(reports)).getOrElse:
      Console.err.println:
        "No baseline found. Provide --baseline or place a baseline file under benchmarks/reports/."
      sys.exit(2)

    val archived = archive(cfg, reports)
    val ok       = compare(baseline, archived, cfg)
    if ok then
      println("\nOK: All benchmarks within thresholds.")
      sys.exit(0)
    else sys.exit(1)

  private def discoverBaseline(reports: java.nio.file.Path): Option[String] =
    val files = Option(Files.list(reports))
      .map(_.iterator().asScala.toList)
      .getOrElse(Nil)
    val baseline = files
      .filter: p =>
        val n = p.getFileName.toString
        n.contains("baseline") && n.endsWith("_jmh.json")
      .sortBy(p => -Files.getLastModifiedTime(p).toMillis)
    baseline.headOption
      .map(_.toString)
      .orElse:
        val latest = files
          .filter(p => p.getFileName.toString.endsWith("_jmh.json"))
          .sortBy(p => -Files.getLastModifiedTime(p).toMillis)
        latest.headOption.map(_.toString)

  private def archive(cfg: Cfg, reports: java.nio.file.Path): String =
    def sanitize(component: String): String =
      // Replace any path separators or unsafe chars with '-'
      component.replaceAll("[^A-Za-z0-9._-]", "-")

    val utc = ZonedDateTime
      .now(ZoneOffset.UTC)
      .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'"))
    val branchRaw = sys.process
      .Process(Seq("git", "rev-parse", "--abbrev-ref", "HEAD"))
      .!!
    val branch = branchRaw.trimOption.map(sanitize).getOrElse("unknown")
    val shaRaw = sys.process
      .Process(Seq("git", "rev-parse", "--short", "HEAD"))
      .!!
    val sha = shaRaw.trimOption.map(sanitize).getOrElse("unknown")
    val tagPart = cfg.tag.filter(_.nonEmpty).map(t => s"_${sanitize(t)}").getOrElse("")
    val suffix  = if cfg.enableGc then "_jmh-gc.json" else "_jmh.json"
    val dest    = reports.resolve(s"${utc}_${branch}_${sha}${tagPart}${suffix}")
    Files.copy(Paths.get(cfg.result), dest)
    println(s"> Archived to ${dest}")
    dest.toString

  private def parse(path: String): Value =
    val bytes = Files.readAllBytes(Paths.get(path))
    ujson.read(bytes)

  private def index(json: Value): Map[String, Value] =
    json.arr.iterator
      .flatMap: v =>
        val name = v.obj.get("benchmark").map(_.str)
        name.map(_ -> v)
      .toMap

  private def pctChange(n: Double, b: Double): Double =
    if b == 0 then Double.PositiveInfinity else (n - b) / b * 100.0

  private def sec(v: Value, key: String): Option[Double] =
    v.obj
      .get("secondaryMetrics")
      .flatMap(_.obj.get(key))
      .flatMap(_.obj.get("score"))
      .map(_.num)

  private def compare(
      baselinePath: String,
      resultPath: String,
      cfg: Cfg,
  ): Boolean =
    val bAll = index(parse(baselinePath))
    val nAll = index(parse(resultPath))
    def primaryScore(v: Value): Double =
      v.obj
        .get("primaryMetric")
        .flatMap(_.obj.get("score"))
        .map(_.num)
        .getOrElse(Double.NaN)
    val filter: String => Boolean = cfg.include match
      case Some(s) if s.nonEmpty => (n: String) => n.contains(s)
      case _                     => (_: String) => true
    val bIdx = bAll.view.filterKeys(filter).toMap
    val nIdx = nAll

    val header = List(
      "Benchmark",
      "ops/s(base)",
      "ops/s(new)",
      "Δops/s%",
      "bytes/op(base)",
      "bytes/op(new)",
      "Δbytes%",
      "gc.time(base)",
      "gc.time(new)",
      "Δgc.time%",
    )
    println(header.mkString("\t"))

    var ok = true
    bIdx.foreach:
      case (name, b) =>
        val nOpt = nIdx.get(name)
        nOpt match
          case None =>
            println:
              s"${name}\tMISSING\tMISSING\tMISSING\tMISSING\tMISSING\tMISSING\tMISSING\tMISSING\tMISSING"
            ok = false
          case Some(n) =>
            val bOps = primaryScore(b)
            val nOps = primaryScore(n)
            val dOps = pctChange(nOps, bOps)

            val bBytes = sec(b, "gc.alloc.rate.norm")
            val nBytes = sec(n, "gc.alloc.rate.norm")
            val dBytes = (for (bb <- bBytes; nb <- nBytes)
              yield pctChange(nb, bb)).getOrElse(Double.NaN)

            val bGc = sec(b, "gc.time")
            val nGc = sec(n, "gc.time")
            val dGc = (for (bg <- bGc; ng <- nGc)
              yield pctChange(ng, bg)).getOrElse(Double.NaN)

            println:
              List(
                name,
                f"${bOps}%.3f",
                f"${nOps}%.3f",
                f"${dOps}%.2f",
                bBytes.map(v => f"${v}%.3f").getOrElse("NaN"),
                nBytes.map(v => f"${v}%.3f").getOrElse("NaN"),
                if dBytes.isNaN then "NaN" else f"${dBytes}%.2f",
                bGc.map(v => f"${v}%.3f").getOrElse("NaN"),
                nGc.map(v => f"${v}%.3f").getOrElse("NaN"),
                if dGc.isNaN then "NaN" else f"${dGc}%.2f",
              ).mkString("\t")

            if !dOps.isNaN && dOps < -math.abs(cfg.opsDropPct) then ok = false
            if !dBytes.isNaN && dBytes > math.abs(cfg.bytesIncrPct) then
              ok = false
            if !dGc.isNaN && dGc > math.abs(cfg.gcTimeIncrPct) then ok = false
    ok

extension (p: String)
  def trimOption: Option[String] =
    val s = p.trim
    if s.nonEmpty then Some(s) else None
