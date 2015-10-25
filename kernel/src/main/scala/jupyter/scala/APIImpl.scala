package jupyter.scala

import ammonite.api.ClassLoaderType
import ammonite.interpreter._
import ammonite.pprint
import ammonite.pprint.{Config, PPrint, TPrint}
import coursier.core.Repository
import jupyter.api._
import jupyter.kernel.protocol.ParsedMessage

import java.io.File

import scala.reflect.runtime.universe._


// Cut-n-pasted here from ammonite-shell not add depend on it
case class ColorSet(prompt: String, ident: String, `type`: String, reset: String)
object ColorSet {
  val Default = ColorSet(Console.MAGENTA, Console.CYAN, Console.GREEN, Console.RESET)
  val BlackWhite = ColorSet("", "", "", "")
}

class APIImpl(
  intp: Interpreter,
  publish0: => Option[Publish[Evidence]],
  currentMessage: => Option[ParsedMessage[_]],
  startJars: Map[ClassLoaderType, Seq[File]],
  startIvys: Map[ClassLoaderType, Seq[(String, String, String)]],
  jarMap: File => File,
  startResolvers: Seq[Repository],
  colors: ColorSet,
  var pprintConfig: pprint.Config,
  history0: => Seq[String]
) extends API {

  val load = new Load(intp, startJars, startIvys, jarMap, startResolvers)
  def interpreter = intp
  def history = history0

  def show[T](a: T, lines: Int = 0) = ammonite.pprint.Show(a, lines)

  def evidence = new Evidence(
    currentMessage.getOrElse(throw new IllegalStateException("Not processing a Jupyter message")))

  def publish = publish0
    .getOrElse(throw new IllegalStateException("Interpreter is not connected to a front-end"))
}
