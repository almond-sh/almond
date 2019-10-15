package almond.api

import java.util.UUID

import almond.interpreter.api.{CommHandler, OutputHandler}
import jupyter.{Displayer, Displayers}

import scala.reflect.{ClassTag, classTag}

abstract class JupyterApi { api =>

  /** Request input from the the Jupyter UI */
  final def stdin(prompt: String = "", password: Boolean = false): String =
    stdinOpt(prompt, password).getOrElse {
      throw new Exception("stdin not available")
    }

  def stdinOpt(prompt: String = "", password: Boolean = false): Option[String]

  def silent(s: Boolean): Unit = {}
  def silent: Boolean = false

  protected implicit def changingPublish: OutputHandler =
    new almond.interpreter.api.OutputHandler.OnlyUpdateVia(commHandler)

  /**
    * Jupyter publishing helper
    *
    * Allows to push display items to the front-end.
    */
  final implicit lazy val publish: OutputHandler =
    new OutputHandler.StableOutputHandler(changingPublish)

  implicit def commHandler: CommHandler =
    throw new Exception("Comm handler not available (not supported)")
  final def comm: CommHandler = commHandler

  protected def updatableResults0: JupyterApi.UpdatableResults

  final lazy val updatableResults: JupyterApi.UpdatableResults =
    updatableResults0

  def register[T: ClassTag](f: T => Map[String, String]): Unit =
    Displayers.register(
      classTag[T].runtimeClass.asInstanceOf[Class[T]],
      new Displayer[T] {
        import scala.collection.JavaConverters._
        def display(t: T) = f(t).asJava
      }
    )
}

object JupyterApi {

  abstract class UpdatableResults {

    @deprecated("Use updatable instead", "0.4.1")
    def addVariable(k: String, v: String): Unit =
      updatable(k, v)
    @deprecated("Use update instead", "0.4.1")
    def updateVariable(k: String, v: String, last: Boolean): Unit =
      update(k, v, last)

    def updatable(k: String, v: String): Unit = {
      // temporary dummy implementation for binary compatibility
    }
    def updatable(v: String): String = {
      val id = UUID.randomUUID().toString
      updatable(id, v)
      id
    }
    def update(k: String, v: String, last: Boolean): Unit = {
      // temporary dummy implementation for binary compatibility
    }
  }

}
