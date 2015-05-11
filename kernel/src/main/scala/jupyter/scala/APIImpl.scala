package jupyter.scala

import ammonite.interpreter._
import ammonite.pprint
import jupyter.api._
import jupyter.kernel.protocol.ParsedMessage

import org.apache.ivy.plugins.resolver.DependencyResolver

import java.io.File

import scala.reflect.runtime.universe._


// Cut-n-pasted here from ammonite-shell not add depend on it
case class ColorSet(prompt: String, ident: String, `type`: String, reset: String)
object ColorSet {
  val Default = ColorSet(Console.MAGENTA, Console.CYAN, Console.GREEN, Console.RESET)
  val BlackWhite = ColorSet("", "", "", "")
}

class APIImpl(intp: ammonite.api.Interpreter,
              publish0: => Option[Publish[Evidence]],
              currentMessage: => Option[ParsedMessage[_]],
              startJars: Seq[File],
              startIvys: Seq[(String, String, String)],
              jarMap: File => File,
              startResolvers: Seq[DependencyResolver],
              colors: ColorSet,
              var pprintConfig: pprint.Config) extends FullAPI {

  val load = new Load(intp, startJars, startIvys, jarMap, startResolvers)
  def interpreter = intp
  def history = intp.history.toVector.dropRight(1)

  def shellPPrint[T: WeakTypeTag](value: => T, ident: String) = {
    colors.ident + ident + colors.reset + ": " +
      colors.`type` + weakTypeOf[T].toString + colors.reset
  }
  def shellPrintDef(definitionLabel: String, ident: String) = {
    s"defined ${colors.`type`}$definitionLabel ${colors.ident}$ident${colors.reset}"
  }
  def shellPrintImport(imported: String) = {
    s"${colors.`type`}import ${colors.ident}$imported${colors.reset}"
  }

  def show[T](a: T, lines: Int = 0) = ammonite.pprint.Show(a, lines)

  def evidence = new Evidence(
    currentMessage.getOrElse(throw new IllegalStateException("Not processing a Jupyter message")))

  def publish = publish0
    .getOrElse(throw new IllegalStateException("Interpreter is not connected to a front-end"))
}
