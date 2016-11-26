package com.spotify.scio.jupyter

import java.io.{File, FileInputStream}
import java.nio.file.{Files, Path}

import ammonite.repl.RuntimeAPI
import ammonite.runtime.InterpAPI

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.services.dataflow.DataflowScopes
import com.google.cloud.dataflow.sdk.options.{DataflowPipelineOptions, PipelineOptions, PipelineOptionsFactory}
import com.spotify.scio.{ScioContext, ScioResult}

import scala.collection.JavaConverters._

// similar to com.spotify.scio.repl.ReplScioContext

// in the com.spotify.scio namespace to access private[scio] things

class JupyterScioContext(
  options: PipelineOptions,
  replJarPath: Path
)(implicit
  interpApi: InterpAPI,
  runtimeApi: RuntimeAPI
) extends ScioContext(options, Nil) {

  addArtifacts(
    replJarPath.toAbsolutePath.toString ::
      runtimeApi.sess.frames
        .flatMap(_.classpath)
        .map(_.getAbsolutePath)
  )

  interpApi.load.onJarAdded {
    case Seq() => // just in case
    case jars =>
      addArtifacts(jars.map(_.getAbsolutePath).toList)
  }

  def setGcpCredential(credential: Credential): Unit =
    options.as(classOf[DataflowPipelineOptions]).setGcpCredential(credential)
  def setGcpCredential(path: String): Unit =
    setGcpCredential(
      GoogleCredential.fromStream(new FileInputStream(new File(path))).createScoped(
        List(DataflowScopes.CLOUD_PLATFORM).asJava
      )
    )

  def withGcpCredential(credential: Credential): this.type = {
    setGcpCredential(credential)
    this
  }
  def withGcpCredential(path: String): this.type = {
    setGcpCredential(path)
    this
  }

  /** Enhanced version that dumps REPL session jar. */
  override def close(): ScioResult = {
    runtimeApi.sess.sessionJarFile(replJarPath.toFile)
    super.close()
  }

  private[scio] override def requireNotClosed[T](body: => T) = {
    require(!isClosed, "ScioContext already closed")
    super.requireNotClosed(body)
  }

}

object JupyterScioContext {

  def apply(args: (String, String)*)(implicit
    interpApi: InterpAPI,
    runtimeApi: RuntimeAPI
  ): JupyterScioContext =
    JupyterScioContext(
      PipelineOptionsFactory.fromArgs(
        args
          .map { case (k, v) => s"--$k=$v" }
          .toArray
      ).as(classOf[DataflowPipelineOptions]),
      nextReplJarPath()
    )

  def apply(options: PipelineOptions)(implicit
    interpApi: InterpAPI,
    runtimeApi: RuntimeAPI
  ): JupyterScioContext =
    JupyterScioContext(options, nextReplJarPath())

  def apply(
    options: PipelineOptions,
    replJarPath: Path
  )(implicit
    interpApi: InterpAPI,
    runtimeApi: RuntimeAPI
  ): JupyterScioContext =
    new JupyterScioContext(options, replJarPath)


  def nextReplJarPath(prefix: String = "jupyter-scala-scio-", suffix: String = ".jar"): Path =
    Files.createTempFile(prefix, suffix)

}