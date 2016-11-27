package jupyter.flink

import java.io.File

import org.apache.flink.yarn.YarnClusterClient

import scala.collection.JavaConverters._
import scala.util.control.NonFatal
import java.lang.{Integer => JInt}
import java.math.BigInteger
import java.nio.file.{Files, StandardCopyOption}
import java.security.MessageDigest

import ammonite.repl.RuntimeAPI
import coursier.Cache
import coursier.cli.spark.Assembly
import coursier.cli.{CommonOptions, Helper}
import org.apache.flink.configuration.{ConfigConstants, GlobalConfiguration}

import scalaz.\/-

object FlinkYarn {

  def flinkVersion = "1.1.3" // TODO Would there be any way to get that from flink itself? Else guess it from resources under META-INF.

  def confDir: File = {

    val path =
      sys.env.get(ConfigConstants.ENV_FLINK_CONF_DIR)
        .orElse(sys.props.get(ConfigConstants.ENV_FLINK_CONF_DIR))
        .getOrElse(
          sys.error(s"${ConfigConstants.ENV_FLINK_CONF_DIR} environment variable or property not set")
        )

    val dir = new File(path)

    if (!dir.exists())
      sys.error(s"Flink configuration directory $path not found")

    dir
  }

  def loadConfig() = {

    // similar things in org.apache.flink.client.CliFrontend

    val confDir0 = confDir

    GlobalConfiguration.loadConfiguration(confDir0.getAbsolutePath)
    sys.props(ConfigConstants.ENV_FLINK_CONF_DIR) = confDir0.getAbsolutePath

    GlobalConfiguration.getConfiguration
  }


  // TODO Move the various flink-dist-related stuff to coursier, and factor the common parts with
  // spark assembly generation.

  def flinkDistDependencies(
    sbv: String,
    flinkVersion: String
  ) = Seq(
    "flink-core",
    "flink-java",
    s"flink-scala_$sbv",
    s"flink-runtime_$sbv",
    s"flink-runtime-web_$sbv",
    s"flink-optimizer_$sbv",
    s"flink-clients_$sbv",
    s"flink-avro_$sbv",
    s"flink-streaming-java_$sbv",
    s"flink-streaming-scala_$sbv",
    s"flink-python_$sbv",
    s"flink-scala-shell_$sbv",
    "flink-metrics-jmx",
    s"flink-yarn_$sbv"
  ).map(mod => s"org.apache.flink:$mod:$flinkVersion")

  def flinkDistExcludes = Seq(
    "org.apache.flink:flink-examples-batch",
    "org.apache.flink:flink-examples-streaming",
    "org.apache.flink:flink-python",
    "org.slf4j:slf4j-log4j12",
    "log4j:log4j"
  )

  def flinkAssembly(
    flinkVersion: String = flinkVersion,
    scalaBinaryVersion: String = scala.util.Properties.versionNumberString.split('.').take(2).mkString("."),
    extraDependencies: Seq[String] = Nil,
    options: CommonOptions = CommonOptions(),
    artifactTypes: Set[String] = Set("jar"),
    checksumSeed: Array[Byte] = "v1".getBytes("UTF-8")
  ) = {

    val base = flinkDistDependencies(scalaBinaryVersion, flinkVersion)
    val helper = new Helper(
      options.copy(
        checksum = List("SHA-1"), // required for the calculation of `checksums` below
        exclude = options.exclude ++ flinkDistExcludes
      ),
      extraDependencies ++ base
    )

    val artifacts = helper.artifacts(sources = false, javadoc = false, artifactTypes = artifactTypes)
    val jars = helper.fetch(sources = false, javadoc = false, artifactTypes = artifactTypes)

    val checksums = artifacts.map { a =>
      val f = a.checksumUrls.get("SHA-1") match {
        case Some(url) =>
          Cache.localFile(url, helper.cache, a.authentication.map(_.user))
        case None =>
          throw new Exception(s"SHA-1 file not found for ${a.url}")
      }

      val sumOpt = Cache.parseChecksum(
        new String(Files.readAllBytes(f.toPath), "UTF-8")
      )

      sumOpt match {
        case Some(sum) =>
          val s = sum.toString(16)
          "0" * (40 - s.length) + s
        case None =>
          throw new Exception(s"Cannot read SHA-1 sum from $f")
      }
    }


    val md = MessageDigest.getInstance("SHA-1")

    md.update(checksumSeed)

    for (c <- checksums.sorted) {
      val b = c.getBytes("UTF-8")
      md.update(b, 0, b.length)
    }

    val digest = md.digest()
    val calculatedSum = new BigInteger(1, digest)
    val s = calculatedSum.toString(16)

    val sum = "0" * (40 - s.length) + s

    val destPath = Seq(
      sys.props("user.home"),
      ".coursier",
      "flink-dists",
      s"scala_${scalaBinaryVersion}_flink_$flinkVersion",
      sum,
      "flink-dist.jar"
    ).mkString("/")

    val dest = new File(destPath)

    def success = Right((dest, jars))

    if (dest.exists())
      success
    else
      Cache.withLockFor(helper.cache, dest) {
        dest.getParentFile.mkdirs()
        val tmpDest = new File(dest.getParentFile, s".${dest.getName}.part")
        // FIXME Acquire lock on tmpDest
        Assembly.make(jars, tmpDest, Assembly.assemblyRules)
        Files.move(tmpDest.toPath, dest.toPath, StandardCopyOption.ATOMIC_MOVE)
        \/-((dest, jars))
      }.leftMap(_.describe).toEither
  }

  def apply(
    taskManagerCount: Int,
    jobManagerMemory: JInt = null,
    taskManagerMemory: JInt = null,
    taskManagerSlots: JInt = null,
    queue: String = null,
    dynamicProperties: Seq[String] = Nil,
    detachedMode: Boolean = false,
    name: String = null,
    zkNamespace: String = null,
    config: org.apache.flink.configuration.Configuration = loadConfig(),
    extraDistDependencies: Seq[String] = Nil
  )(implicit runtimeApi: RuntimeAPI): YarnClusterClient = {

    val shipFiles = JupyterFlinkRemoteEnvironment.keepJars(runtimeApi.sess.classpath()) :+
      runtimeApi.sess.sessionJarFile()

    val flinkDistJar = flinkAssembly(extraDependencies = extraDistDependencies) match {
      case Left(err) =>
        sys.error(s"Generating Flink dist: $err")
      case Right((dist, _)) =>
        dist
    }

    val client = create(
      taskManagerCount,
      flinkDistJar,
      shipFiles,
      jobManagerMemory,
      taskManagerMemory,
      taskManagerSlots,
      queue,
      dynamicProperties,
      detachedMode,
      name,
      zkNamespace,
      config
    )

    if (!detachedMode)
      // not sure why we have to do this ourselves
      runtimeApi.onExit { _ =>
        client.shutdown()
      }

    client
  }

  def create(
    taskManagerCount: Int,
    flinkDistJar: File,
    shipFiles: Seq[File],
    jobManagerMemory: JInt = null,
    taskManagerMemory: JInt = null,
    taskManagerSlots: JInt = null,
    queue: String = null,
    dynamicProperties: Seq[String] = Nil,
    detachedMode: Boolean = false,
    name: String = null,
    zkNamespace: String = null,
    config: org.apache.flink.configuration.Configuration = loadConfig()
  ): YarnClusterClient = {

    val desc = new org.apache.flink.yarn.YarnClusterDescriptor

    desc.setTaskManagerCount(taskManagerCount)
    desc.setLocalJarPath(new org.apache.hadoop.fs.Path(flinkDistJar.toURI))
    desc.addShipFiles(shipFiles.asJava)

    for (q <- Option(queue))
      desc.setQueue(q)

    for (mem <- Option(jobManagerMemory))
      desc.setJobManagerMemory(mem)
    for (mem <- Option(taskManagerMemory))
      desc.setTaskManagerMemory(mem)

    for (n <- Option(taskManagerSlots))
      desc.setTaskManagerSlots(n)

    desc.setDynamicPropertiesEncoded(dynamicProperties.mkString("@@"))
    desc.setDetachedMode(detachedMode)

    for (n <- Option(name))
      desc.setName(n)

    for (ns <- Option(zkNamespace))
      desc.setZookeeperNamespace(ns)

    desc.setFlinkConfiguration(config)

    try desc.deploy()
    catch {
      case NonFatal(e) =>
        throw new RuntimeException("Error deploying the YARN cluster", e)
    }
  }

}