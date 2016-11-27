package jupyter.flink

import java.io.{ByteArrayOutputStream, File}
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.zip.{ZipEntry, ZipOutputStream}

import ammonite.repl.RuntimeAPI
import ammonite.runtime.Frame
import org.apache.flink.api.common.PlanExecutor
import org.apache.flink.api.java.RemoteEnvironment
import org.apache.flink.api.scala.ExecutionEnvironment

import scala.collection.JavaConverters._

class JupyterFlinkRemoteEnvironment(
  host: String,
  port: Int,
  sessionJar: => File,
  loadedJars: => Seq[File]
) extends RemoteEnvironment(
    host,
    port,
    null,
    loadedJars.map(_.getAbsolutePath).toArray
) {

  override def getExecutor: PlanExecutor = {

    if (executor != null)
      executor.stop()

    val jars = sessionJar +: loadedJars

    executor = PlanExecutor.createRemoteExecutor(
      host,
      port,
      clientConfiguration,
      jars.map(_.toURI.toURL).asJava,
      globalClasspaths
    )

    executor.setPrintStatusDuringExecution(getConfig.isSysoutLoggingEnabled)

    executor
  }

}

object JupyterFlinkRemoteEnvironment {

  def apply(
    host: String,
    port: Int
  )(implicit runtimeApi: RuntimeAPI): ExecutionEnvironment =
    new ExecutionEnvironment(
      new JupyterFlinkRemoteEnvironment(
        host,
        port,
        runtimeApi.sess.sessionJarFile(),
        keepJars(runtimeApi.sess.classpath())
      )
    )

  def apply(
    jobManagerAddress: InetSocketAddress
  )(implicit runtimeApi: RuntimeAPI): ExecutionEnvironment =
    apply(
      jobManagerAddress.getHostString,
      jobManagerAddress.getPort
    )


  def keepJars(files: Seq[File]): Seq[File] = {

    val (dirs, files0) = files.partition(_.isDirectory)
    val (jars0, others) = files0.partition(_.getName.endsWith(".jar"))

    if (dirs.nonEmpty || others.nonEmpty) {
      if (dirs.nonEmpty) {
        Console.err.println("Warning: found directories in classpath:")
        for (dir <- dirs)
          Console.err.println(s"  $dir")
      }

      if (others.nonEmpty) {
        Console.err.println(s"Warning: found non-JAR files in classpath:")
        for (f <- others)
          Console.err.println(s"  $f")
      }
    }

    jars0
  }

}