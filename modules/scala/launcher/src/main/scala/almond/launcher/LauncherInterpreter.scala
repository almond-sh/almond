package almond.launcher

import almond.interpreter.api.OutputHandler
import almond.interpreter.{ExecuteResult, Interpreter}
import almond.interpreter.api.DisplayData
import almond.interpreter.input.InputManager
import almond.protocol.KernelInfo

import java.io.File

import scala.cli.directivehandler._
import scala.cli.directivehandler.EitherSequence._

class LauncherInterpreter(
  connectionFile: String,
  options: LauncherOptions
) extends Interpreter {

  def kernelInfo(): KernelInfo =
    KernelInfo(
      implementation = "scala",
      implementation_version = "???",
      language_info = KernelInfo.LanguageInfo(
        name = "scala",
        version = "???",
        mimetype = "text/x-scala",
        file_extension = ".sc",
        nbconvert_exporter = "script",
        codemirror_mode = Some("text/x-scala")
      ),
      banner =
        s"""Almond ${"???"}
           |Ammonite ${"???"}
           |${"???"}
           |Java ${"???"}""".stripMargin, // +
      // params.extraBannerOpt.fold("")("\n\n" + _),
      help_links = None // Some(params.extraLinks.toList).filter(_.nonEmpty)
    )

  var params = LauncherParameters()

  def execute(
    code: String,
    storeHistory: Boolean,
    inputManager: Option[InputManager],
    outputHandler: Option[OutputHandler]
  ): ExecuteResult = {
    val path      = Left(s"cell$lineCount0.sc")
    val scopePath = ScopePath(Left("."), os.sub)
    ExtractedDirectives.from(code.toCharArray, path) match {
      case Left(ex) =>
        LauncherInterpreter.error(LauncherInterpreter.Colors.default, Some(ex), "")
      case Right(directives) =>
        val directivesErrorOpt =
          if (directives.directives.isEmpty)
            None
          else {
            // FIXME There might be actual code alongside directives

            val scopedDirectives = directives.directives.map { dir =>
              ScopedDirective(dir, path, scopePath)
            }
            val res = scopedDirectives
              .map { dir =>
                LauncherInterpreter.handlersMap.get(dir.directive.key) match {
                  case Some(h) =>
                    h.handleValues(dir).map(_.global.map(_.launcherParameters))
                  case None =>
                    Left(dir.unusedDirectiveError)
                }
              }
              .sequence
              .left.map(CompositeDirectiveException(_))
              .map(_.flatMap(_.toSeq).foldLeft(LauncherParameters())(_ + _))

            res match {
              case Left(ex) =>
                val err = LauncherInterpreter.error(
                  LauncherInterpreter.Colors.default,
                  Some(ex),
                  "Error while processing using directives"
                )
                Some(err)
              case Right(paramsUpdate) =>
                params = params + paramsUpdate
                None
            }
          }

        directivesErrorOpt.getOrElse {
          if (ScalaParser.hasActualCode(code))
            // handing over execution to the actual kernel
            ExecuteResult.Close
          else {
            lineCount0 += 1
            ExecuteResult.Success(DisplayData.empty)
          }
        }
    }
  }

  private var lineCount0 = 0
  def lineCount          = lineCount0
  def currentLine(): Int =
    lineCount0
}

object LauncherInterpreter {

  private val handlers = Seq[DirectiveHandler[directives.HasLauncherParameters]](
    directives.JavaOptions.handler,
    directives.Jvm.handler,
    directives.ScalaVersion.handler
  )

  private val handlersMap = handlers.flatMap(h => h.keys.map(_ -> h)).toMap

  private def error(colors: Colors, exOpt: Option[Throwable], msg: String) =
    ExecuteResult.Error.error(colors.error, colors.literal, exOpt, msg)

  // from Model.scala in Ammonite
  object Ex {
    def unapplySeq(t: Throwable): Option[Seq[Throwable]] = {
      def rec(t: Throwable): List[Throwable] =
        t match {
          case null => Nil
          case t    => t :: rec(t.getCause)
        }
      Some(rec(t))
    }
  }
  case class Colors(
    prompt: fansi.Attrs,
    ident: fansi.Attrs,
    `type`: fansi.Attrs,
    literal: fansi.Attrs,
    prefix: fansi.Attrs,
    comment: fansi.Attrs,
    keyword: fansi.Attrs,
    selected: fansi.Attrs,
    error: fansi.Attrs,
    warning: fansi.Attrs,
    info: fansi.Attrs
  )

  object Colors {

    def default = Colors(
      fansi.Color.Magenta,
      fansi.Color.Cyan,
      fansi.Color.Green,
      fansi.Color.Green,
      fansi.Color.Yellow,
      fansi.Color.Blue,
      fansi.Color.Yellow,
      fansi.Reversed.On,
      fansi.Color.Red,
      fansi.Color.Yellow,
      fansi.Color.Blue
    )
    def blackWhite = Colors(
      fansi.Attrs.Empty,
      fansi.Attrs.Empty,
      fansi.Attrs.Empty,
      fansi.Attrs.Empty,
      fansi.Attrs.Empty,
      fansi.Attrs.Empty,
      fansi.Attrs.Empty,
      fansi.Attrs.Empty,
      fansi.Attrs.Empty,
      fansi.Attrs.Empty,
      fansi.Attrs.Empty
    )
  }
}
