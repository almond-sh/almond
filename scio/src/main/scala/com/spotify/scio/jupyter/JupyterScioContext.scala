package com.spotify.scio.jupyter

import java.io.{File, FileInputStream}
import java.nio.file.{Files, Path}

import ammonite.repl.RuntimeAPI
import ammonite.runtime.InterpAPI

import com.google.api.services.dataflow.DataflowScopes
import com.google.auth.Credentials
import com.google.auth.oauth2.GoogleCredentials
import com.spotify.scio.{ScioContext, ScioResult}
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions
import org.apache.beam.sdk.options.{PipelineOptions, PipelineOptionsFactory}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

// similar to com.spotify.scio.repl.ReplScioContext

// in the com.spotify.scio namespace to access private[scio] things

class JupyterScioContext(options: PipelineOptions,
                         replJarPath: Path)
                        (implicit interpApi: InterpAPI,
                         runtimeApi: RuntimeAPI
                        ) extends ScioContext(options,
  replJarPath.toAbsolutePath.toString ::
    runtimeApi.sess.frames
      .flatMap(_.classpath)
      .map(_.getAbsolutePath)
) {

  import JupyterScioContext._

  interpApi.load.onJarAdded {
    case Seq() => // just in case
    case _ =>
      throw new RuntimeException("Cannot add jars after ScioContext Initialization")
  }

  def setGcpCredential(credentials: Credentials): Unit = {
    // Save credentials for all future context creation
    _gcpCredentials = Some(credentials)

    require(_currentContext.isDefined && !_currentContext.get.isClosed,
      "Scio Context is not yet defined or already closed")

    _currentContext.get.options.as(classOf[DataflowPipelineOptions])
      .setGcpCredential(credentials)
  }

  def setGcpCredential(path: String): Unit =
    setGcpCredential(
      GoogleCredentials.fromStream(new FileInputStream(new File(path))).createScoped(
        List(DataflowScopes.CLOUD_PLATFORM).asJava
      )
    )

  /** Enhanced version that dumps REPL session jar. */
  override def close(): ScioResult = {
    // Some APIs exposed only for Jupyter will close Scio Context
    // even if the user intends to use it further. A new Context would be created again.
    logger.info("Closing Scio Context")
    runtimeApi.sess.sessionJarFile(replJarPath.toFile)
    super.close()
  }

  private[scio] override def requireNotClosed[T](body: => T) = {
    require(!isClosed, "ScioContext already closed")
    super.requireNotClosed(body)
  }

}

/**
 * Allow only one active Scio Context.
 * Also manage the existing Scio Context, create a new one if the current has been closed.
 */
object JupyterScioContext {

  private val logger = LoggerFactory.getLogger(this.getClass)

  private var _currentContext: Option[JupyterScioContext] = None
  private var _pipelineOptions: Option[PipelineOptions] = None
  private var _gcpCredentials: Option[Credentials] = None

  /**
   * Always returns a new Scio Context, and forgets the old context
   */
  def apply(args: (String, String)*)
           (implicit interpApi: InterpAPI,
            runtimeApi: RuntimeAPI): Unit = JupyterScioContext(
    PipelineOptionsFactory.fromArgs(
      args.map { case (k, v) => s"--$k=$v" }: _*
    ).as(classOf[PipelineOptions])
  )

  /**
   * Always returns a new Scio Context, and forgets the old context
   */
  def apply(options: PipelineOptions)
           (implicit interpApi: InterpAPI, runtimeApi: RuntimeAPI): Unit = {
    _pipelineOptions = Some(options)
    _currentContext = Some(new JupyterScioContext(options, nextReplJarPath()))
    logger.info("ScioContext is accessible as sc")
  }

  /**
   * Get Scio Context with currently defined options.
   * Get new Scio Context if previous is closed.
   *
   * @return
   */
  def sc(implicit interpApi: InterpAPI, runtimeApi: RuntimeAPI): JupyterScioContext = {
    if (_currentContext.isEmpty || _currentContext.get.isClosed) {
      // Create a new Scio Context
      JupyterScioContext(_pipelineOptions.getOrElse(PipelineOptionsFactory.create()))
      _gcpCredentials.foreach(_currentContext.get.setGcpCredential)
    }
    _currentContext.get
  }

  private def nextReplJarPath(prefix: String = "jupyter-scala-scio-", suffix: String = ".jar"): Path =
    Files.createTempFile(prefix, suffix)

}
