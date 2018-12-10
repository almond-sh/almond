package almond.api

import almond.interpreter.api.{CommHandler, OutputHandler}

trait JupyterApi { api =>

  /** Request input from the the Jupyter UI */
  final def stdin(prompt: String = "", password: Boolean = false): String =
    stdinOpt(prompt, password).getOrElse {
      throw new Exception("stdin not available")
    }

  def stdinOpt(prompt: String = "", password: Boolean = false): Option[String]

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

  protected def addResultVariable(k: String, v: String): Unit
  protected def updateResultVariable(k: String, v: String, last: Boolean): Unit

  object Internals {
    def addResultVariable(k: String, v: String): Unit =
      api.addResultVariable(k, v)
    def updateResultVariable(k: String, v: String, last: Boolean): Unit =
      api.updateResultVariable(k, v, last)
  }
}
