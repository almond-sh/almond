package almond.internals

import ammonite.compiler.CompilerExtensions._
import ammonite.interp.api.InterpAPI

import scala.collection.mutable.ListBuffer
import scala.tools.nsc.Settings

object ConfigureCompiler {

  /** Adds options to the compiler, if they're valid
    *
    * The options are checked on settings of their own first, so that invalid ones are reported
    * right away (rather than when the compiler is next initialized, upon each later compilation).
    *
    * @return
    *   the errors of the options if they're invalid, or the warnings they get otherwise
    */
  def addOptions(interpApi: InterpAPI)(options: Seq[String]): Either[String, Seq[String]] =
    if (options.isEmpty) Right(Nil)
    else {
      val errors       = new ListBuffer[String]
      val (success, _) = new Settings(errors += _).processArguments(options.toList, true)
      if (success && errors.isEmpty) {
        interpApi.preConfigureCompiler { settings =>
          settings.processArguments(options.toList, true)
          ()
        }
        Right(Nil)
      }
      else
        Left(
          if (errors.isEmpty) s"Invalid compiler options: ${options.mkString(" ")}"
          else errors.mkString(System.lineSeparator())
        )
    }
}
