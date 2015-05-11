package jupyter.scala

import scala.reflect.runtime.universe.WeakTypeTag

trait API {
  /**
   * History of commands that have been entered
   */
  def history: Seq[String]

  /**
   * Tools related to loading external scripts and code
   */
  implicit def load: ammonite.api.Load

  /**
   * Exposes some internals of the current interpreter
   */
  implicit def interpreter: ammonite.api.Interpreter


  /**
   * Controls how things are pretty-printed
   */
  implicit var pprintConfig: ammonite.pprint.Config

  /**
   * Prettyprint the given `value` with no truncation. Optionally takes
   * a number of lines to print.
   */
  def show[T](value: T, lines: Int = 0): ammonite.pprint.Show[T]
}

/**
 * Things that are part of the API that aren't really "public"
 */
trait FullAPI extends API {
  def shellPPrint[T: WeakTypeTag](value: => T, ident: String): String
  def shellPrintDef(definitionLabel: String, ident: String): String
  def shellPrintImport(imported: String): String
}

class APIHolder {
  @transient var shell0: FullAPI = null
  @transient lazy val shell = shell0
}

object APIHolder {
  def initReplBridge(holder: Class[APIHolder], api: FullAPI) =
    holder
      .getDeclaredMethods
      .find(_.getName == "shell0_$eq")
      .get
      .invoke(null, api)
}
