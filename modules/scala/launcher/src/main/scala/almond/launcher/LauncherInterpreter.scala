package almond.launcher

import almond.directives.{DirectiveKeys, KernelOptions}
import almond.interpreter.api.OutputHandler
import almond.interpreter.{ExecuteError, Interpreter, Message}
import almond.interpreter.api.{DisplayData, ExecuteResult}
import almond.interpreter.input.InputManager
import almond.launcher.directives.{CustomGroup, LauncherParameters}
import almond.protocol.{Execute, KernelInfo}
import ammonite.directives.{
  DiagnosticSeverity,
  DirectiveValue,
  Position,
  UsingDirective,
  UsingDirectivesParser
}

import scala.util.control.NonFatal

/** Interpreter of the launcher, handling the cells with only directives before the actual kernel
  * starts
  *
  * @param prepareKernel
  *   fetches what the actual kernel needs, and returns the command starting it, given the launcher
  *   parameters and kernel options, the current cell count, the id of the message of the first cell
  *   with code, and an output handler for that cell. Throws if anything goes wrong.
  */
class LauncherInterpreter(
  connectionFile: String,
  options: LauncherOptions,
  prepareKernel: (
    LauncherParameters,
    KernelOptions,
    Int,
    Option[String],
    OutputHandler
  ) => Launcher.KernelCommand
) extends Interpreter {

  def kernelInfo(): KernelInfo = {
    val (sv, svOrigin) = LauncherInterpreter.computeScalaVersion(params, options)
    KernelInfo(
      implementation = "scala",
      implementation_version = Properties.version,
      language_info = KernelInfo.LanguageInfo(
        name = "scala",
        // the Scala version the actual kernel is going to run
        version = sv,
        mimetype = "text/x-scala",
        file_extension = ".sc",
        nbconvert_exporter = "script",
        codemirror_mode = Some("scala")
      ),
      banner =
        s"""Almond ${Properties.version}
           |Ammonite ${Properties.ammoniteVersion}
           |Scala $sv (from $svOrigin)""".stripMargin, // +
      // params.extraBannerOpt.fold("")("\n\n" + _),
      help_links = None // Some(params.extraLinks.toList).filter(_.nonEmpty)
    )
  }

  var kernelOptions = KernelOptions()
  var params        = LauncherParameters()

  val customDirectiveGroups = options.customDirectiveGroupsOrExit()

  def execute(
    code: String,
    storeHistory: Boolean,
    inputManager: Option[InputManager],
    outputHandler: Option[OutputHandler],
    messageOpt: Option[Message[Execute.Request]]
  ): ExecuteResult = {
    val directives =
      LauncherInterpreter.directives(code, s"cell$lineCount0.sc", customDirectiveGroups)
    directives match {
      case Left(errors) =>
        LauncherInterpreter.error(
          LauncherInterpreter.Colors.default,
          None,
          ("Error while processing using directives:" +: errors).mkString(System.lineSeparator())
        )
      case Right(LauncherInterpreter.Directives(paramsUpdate, kernelOptionsUpdate, warnings)) =>
        for (warning <- warnings) {
          val line = s"Warning: $warning" + System.lineSeparator()
          outputHandler match {
            case Some(h) => h.stderr(line)
            case None    => System.err.print(line)
          }
        }
        val newParams        = LauncherInterpreter.update(params, paramsUpdate)
        val newKernelOptions = kernelOptions + kernelOptionsUpdate
        if (ScalaParser.hasActualCode(code))
          prepare(newParams, newKernelOptions, messageOpt, outputHandler) match {
            case Right(kernelCommand) =>
              params = newParams
              kernelOptions = newKernelOptions
              kernelCommandOpt0 = Some(kernelCommand)
              // handing over execution to the actual kernel
              ExecuteResult.Close
            case Left(err) =>
              // Not keeping the directives of this cell, so that users can fix them and run
              // it again. We stay around, and the actual kernel isn't started. Like in the
              // actual kernel, failed cells don't increment the execution count.
              err
          }
        else {
          params = newParams
          kernelOptions = newKernelOptions
          lineCount0 += 1
          ExecuteResult.Success(DisplayData.empty)
        }
    }
  }

  private def prepare(
    params0: LauncherParameters,
    kernelOptions0: KernelOptions,
    messageOpt: Option[Message[Execute.Request]],
    outputHandler: Option[OutputHandler]
  ): Either[ExecuteResult.Error, Launcher.KernelCommand] =
    try {
      val (launcherParams, kernelParams) = params0.processCustomDirectives(kernelOptions0)
      val kernelCommand = prepareKernel(
        launcherParams,
        kernelParams,
        lineCount0,
        messageOpt.map(_.header.msg_id),
        outputHandler.getOrElse(OutputHandler.NopOutputHandler)
      )
      Right(kernelCommand)
    }
    catch {
      case e: Launcher.KernelPreparationException =>
        Left(LauncherInterpreter.preparationError(LauncherInterpreter.Colors.default, e))
      case NonFatal(e) =>
        Left(
          LauncherInterpreter.error(
            LauncherInterpreter.Colors.default,
            Some(e),
            "Error while starting the kernel"
          )
        )
    }

  @volatile private var kernelCommandOpt0 = Option.empty[Launcher.KernelCommand]

  /** The command starting the actual kernel, once a cell with code was run */
  def kernelCommandOpt: Option[Launcher.KernelCommand] =
    kernelCommandOpt0

  private var lineCount0 = 0
  def lineCount          = lineCount0
  def currentLine(): Int =
    lineCount0
}

object LauncherInterpreter {

  /** What the directives of a cell change
    *
    * @param warnings
    *   warnings to print (without a "Warning: " prefix)
    */
  private final case class Directives(
    launcherParameters: LauncherParameters,
    kernelOptions: KernelOptions,
    warnings: Seq[String]
  )

  /** Parses and handles the using directives of a cell
    *
    * Like Ammonite does in the kernel (see `ammonite.runtime.DirectiveHook`), directives are
    * handled by key: we handle the launcher ones (`//> using scala`, …) and those of custom groups,
    * and pass kernel ones (`//> using dep`, …) to the kernel, that validates their values.
    * Unrecognized directives are errors, like in the kernel.
    *
    * @param path
    *   how to refer to the cell in messages
    * @return
    *   errors, or what the directives change
    */
  private def directives(
    code: String,
    path: String,
    customGroups: Seq[CustomGroup]
  ): Either[Seq[String], Directives] = {

    def at(position: Position, message: String): String =
      s"$path:${position.line + 1}:${position.column + 1}: $message"

    val parsed = UsingDirectivesParser.parse(code)
    val (parseErrors, parseWarnings) =
      parsed.diagnostics.partition(_.severity == DiagnosticSeverity.Error)

    // the values of a directive, without the empty ones (of '//> using scala', with no value)
    def values(directive: UsingDirective): Seq[String] =
      directive.values.collect {
        case value if !value.isInstanceOf[DirectiveValue.EmptyVal] => value.stringValue
      }
    def singleValue(directive: UsingDirective): Either[String, Option[String]] =
      values(directive) match {
        case Seq()      => Right(None)
        case Seq(value) => Right(Some(value).filter(_.trim.nonEmpty))
        case _ =>
          Left(at(
            directive.keyPosition,
            s"Expected a single value after '//> using ${directive.key}'"
          ))
      }

    val results = parsed.directives.map { directive =>
      val key = directive.key
      if (DirectiveKeys.scala.contains(key))
        singleValue(directive).map(v => (LauncherParameters(scala = v), KernelOptions()))
      else if (DirectiveKeys.jvm.contains(key))
        singleValue(directive).map(v => (LauncherParameters(jvm = v), KernelOptions()))
      else if (DirectiveKeys.javaOpt.contains(key))
        Right((LauncherParameters(javaOptions = values(directive)), KernelOptions()))
      else if (DirectiveKeys.javaCmd.contains(key)) {
        val cmd = Some(values(directive)).filter(_.exists(_.nonEmpty))
        Right((LauncherParameters(javaCmd = cmd), KernelOptions()))
      }
      else if (DirectiveKeys.kernel.contains(key))
        Right((LauncherParameters(), KernelOptions.fromDirective(key, values(directive))))
      else
        customGroups.find(_.matches(key)) match {
          case Some(group) =>
            val params =
              LauncherParameters(customDirectives = Seq((group, key, values(directive))))
            Right((params, KernelOptions()))
          case None =>
            Left(at(directive.keyPosition, s"unrecognized directive '//> using $key'"))
        }
    }

    val errors = parseErrors.map(diag => at(diag.position, diag.message)) ++
      results.collect { case Left(err) => err }
    if (errors.isEmpty) {
      val results0 = results.collect { case Right(res) => res }
      Right(
        Directives(
          results0.map(_._1).foldLeft(LauncherParameters())(_ + _),
          results0.map(_._2).foldLeft(KernelOptions())(_ + _),
          parseWarnings.map(diag => at(diag.position, diag.message))
        )
      )
    }
    else
      Left(errors)
  }

  private def error(colors: Colors, exOpt: Option[Throwable], msg: String) =
    ExecuteError.error(colors.error, colors.literal, exOpt, msg)

  /** Error for exceptions users can likely fix themselves, showing their messages, but no stack
    * trace
    */
  private def preparationError(
    colors: Colors,
    ex: Launcher.KernelPreparationException
  ): ExecuteResult.Error = {
    val causeMessages = Ex.unapplySeq(ex).get.drop(1)
      .flatMap(e => Option(e.getMessage))
      .map(_.trim)
      .filter(_.nonEmpty)
    val hint = "Fix the directives (in this cell or earlier ones), then run this cell again."
    ExecuteResult.Error(
      "",
      (ex.getMessage +: causeMessages).mkString(System.lineSeparator()),
      colors.error(ex.getMessage).render +: (causeMessages.flatMap(_.linesIterator) :+ "" :+ hint)
        .toList
    )
  }

  /** Updates launcher parameters with those of a newer cell
    *
    * Unlike [[LauncherParameters.+]], the newer Scala version, JVM, or Java command win, so that
    * users can fix those by running their directives again.
    */
  private def update(
    params: LauncherParameters,
    newer: LauncherParameters
  ): LauncherParameters =
    LauncherParameters(
      jvm = newer.jvm.orElse(params.jvm),
      javaOptions = params.javaOptions ++ newer.javaOptions,
      scala = newer.scala.orElse(params.scala),
      javaCmd = newer.javaCmd.orElse(params.javaCmd),
      kernelOptions = params.kernelOptions ++ newer.kernelOptions,
      customDirectives = params.customDirectives ++ newer.customDirectives
    )

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

  def computeScalaVersion(
    params0: LauncherParameters,
    options: LauncherOptions
  ): (String, String) = {

    val requestedScalaVersion = params0.scala.map((_, "directive"))
      .orElse(options.scala.map(_.trim).filter(_.nonEmpty).map((_, "command-line")))
      .getOrElse((Properties.defaultScalaVersion, "default"))

    requestedScalaVersion._1 match {
      case "2.12"       => (Properties.defaultScala212Version, requestedScalaVersion._2)
      case "2" | "2.13" => (Properties.defaultScala213Version, requestedScalaVersion._2)
      case "3"          => (Properties.defaultScalaVersion, requestedScalaVersion._2)
      case _            => requestedScalaVersion
    }
  }
}
