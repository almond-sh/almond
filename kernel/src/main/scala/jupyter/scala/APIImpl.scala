package jupyter.scala

import ammonite.interpreter._
import ammonite.pprint
import ammonite.pprint.{Config, PPrint, TPrint}
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

  object Internal extends jupyter.api.Internal{
    def combinePrints(iters: Iterator[String]*) = {
      iters.toIterator
        .filter(!_.isEmpty)
        .flatMap(Iterator("\n") ++ _)
        .drop(1)
    }
    def print[T: TPrint: PPrint: WeakTypeTag](value: => T, ident: String, custom: Option[String])(implicit cfg: Config) = {
      if (weakTypeOf[T] =:= weakTypeOf[Unit]) Iterator()
      else {
        val pprint = implicitly[PPrint[T]]
        val rhs = custom match {
          case None => pprint.render(value)
          case Some(s) => Iterator(pprint.cfg.color.literal(s))
        }
        Iterator(
          colors.ident, ident, colors.reset, ": ",
          implicitly[TPrint[T]].render(cfg), " = "
        ) ++ rhs
      }
    }
    def printDef(definitionLabel: String, ident: String) = {
      Iterator("defined ", colors.`type`, definitionLabel, " ", colors.ident, ident, colors.reset)
    }
    def printImport(imported: String) = {
      Iterator(colors.`type`, "import ", colors.ident, imported, colors.reset)
    }
  }

  def show[T](a: T, lines: Int = 0) = ammonite.pprint.Show(a, lines)

  def evidence = new Evidence(
    currentMessage.getOrElse(throw new IllegalStateException("Not processing a Jupyter message")))

  def publish = publish0
    .getOrElse(throw new IllegalStateException("Interpreter is not connected to a front-end"))
}
