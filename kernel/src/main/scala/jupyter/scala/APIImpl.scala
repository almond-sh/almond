package jupyter.scala

import ammonite.interpreter._
import ammonite.pprint

import org.apache.ivy.plugins.resolver.DependencyResolver

import java.io.File

import scala.reflect.runtime.universe._


/**
 * A set of colors used to highlight the miscellanious bits of the REPL.
 */
case class ColorSet(prompt: String, ident: String, `type`: String, reset: String)
object ColorSet{
  val Default = ColorSet(Console.MAGENTA, Console.CYAN, Console.GREEN, Console.RESET)
  val BlackWhite = ColorSet("", "", "", "")
}

class APIImpl(intp: ammonite.api.Interpreter,
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
}
