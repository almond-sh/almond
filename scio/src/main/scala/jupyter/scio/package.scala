package jupyter

import java.io.File

import ammonite.repl.RuntimeAPI
import ammonite.runtime.InterpAPI

import com.google.auth.Credentials
import com.spotify.scio.bigquery.BigQueryClient
import com.spotify.scio.io.Tap
import com.spotify.scio.values.SCollection

import _root_.scala.tools.nsc.interpreter.Helper

package object scio {

  // Alias to reduce number of imports in notebook
  val JupyterScioContext: com.spotify.scio.jupyter.JupyterScioContext.type =
    com.spotify.scio.jupyter.JupyterScioContext

  def sc(implicit interpApi: InterpAPI, runtimeApi: RuntimeAPI) = JupyterScioContext.sc

  def bigQueryClient(project: String): BigQueryClient =
    Helper.bigQueryClient(project)

  def bigQueryClient(project: String, credentials: Credentials): BigQueryClient =
    Helper.bigQueryClient(project, credentials)

  def bigQueryClient(project: String, secretFile: File): BigQueryClient =
    Helper.bigQueryClient(project, secretFile)

  // Helpers for interactive analysis
  implicit class JupyterSCollection[T](self: SCollection[T]) {

    /**
     * Get first n elements of the SCollection as a String separated by \n
     */
    private def asString(numElements: Int): String =
      self
        .withName(s"Take $numElements elements")
        .take(numElements)
        .tap()
        .value
        .mkString("\n")

    /**
     * Closes the ScioContext and print elements on screen
     */
    def show(numElements: Int = 20): Unit = println(asString(numElements))

    /**
     * Closes the ScioContext and gets SCollection as a Tap
     */
    def tap(): Tap[T] = {
      val mSelf = self.materialize
      self.context.close().waitUntilDone()
      mSelf.waitForResult() // Should be ready
    }
  }

  implicit class JupyterTap[T](self: Tap[T]) {

    /**
     * Print the contents of a tap on screen
     */
    def show(numElements: Int = 20): Unit = println(self
      .value
      .take(numElements)
      .mkString("\n")
    )
  }

}
