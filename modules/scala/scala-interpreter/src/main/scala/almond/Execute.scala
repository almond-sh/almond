package almond

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.charset.StandardCharsets.UTF_8

import almond.api.JupyterApi
import almond.directives.{HasKernelOptions, KernelOptions}
import almond.directives.HasKernelOptions.ops._
import almond.internals.{
  Capture,
  FunctionInputStream,
  FunctionOutputStream,
  HtmlAnsiOutputStream,
  UpdatableResults
}
import almond.interpreter.ExecuteResult
import almond.interpreter.api.{CommHandler, DisplayData, OutputHandler}
import almond.interpreter.input.InputManager
import almond.launcher.directives.{CustomGroup, LauncherParameters}
import almond.logger.LoggerContext
import ammonite.compiler.Parsers
import ammonite.repl.api.History
import ammonite.repl.{Repl, Signaller}
import ammonite.runtime.Storage
import ammonite.util.{Colors, Ex, Printer, Ref, Res}
import coursierapi.{IvyRepository, MavenRepository}
import dependency.ScalaParameters
import dependency.api.ops._
import fastparse.Parsed

import scala.cli.directivehandler._
import scala.cli.directivehandler.EitherSequence._
import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

/** Wraps contextual things around when executing code (capturing output, stdin via front-ends,
  * interruption, etc.)
  */
final class Execute(
  trapOutput: Boolean,
  quiet: Boolean,
  storage: Storage,
  logCtx: LoggerContext,
  updateBackgroundVariablesEcOpt: Option[ExecutionContext],
  commHandlerOpt: => Option[CommHandler],
  silent: Ref[Boolean],
  useThreadInterrupt: Boolean,
  initialCellCount: Int,
  enableExitHack: Boolean,
  ignoreLauncherDirectivesIn: Set[String],
  launcherDirectiveGroups: Seq[CustomGroup]
) {

  private val handlers = HasKernelOptions.handlers ++
    LauncherParameters.handlers.mapDirectives(_.ignoredDirective).addCustomHandler { key =>
      launcherDirectiveGroups.find(_.matches(key)).map { group =>
        new DirectiveHandler[HasKernelOptions] {
          def name        = s"custom group ${group.prefix}"
          def description = s"custom group ${group.prefix}"
          def usage       = s"//> ${group.prefix}..."
          def keys        = Seq(key)
          def handleValues(scopedDirective: ScopedDirective)
            : Either[DirectiveException, ProcessedDirective[HasKernelOptions]] =
            Right(ProcessedDirective(
              Some(HasKernelOptions.IgnoredDirectives(Seq(IgnoredDirective(scopedDirective)))),
              Nil
            ))
        }
      }
    }

  private val log = logCtx(getClass)

  private var currentInputManagerOpt0 = Option.empty[InputManager]

  private var interruptedStackTraceOpt0 = Option.empty[Array[StackTraceElement]]
  private var currentThreadOpt0         = Option.empty[Thread]

  private var history0 = new History(Vector())

  private val input0 = new FunctionInputStream(
    UTF_8,
    currentInputManagerOpt0.flatMap { m =>

      val res = {
        implicit val ec =
          ExecutionContext.global // just using that one to map over an existing future…
        log.info("Awaiting input")
        Await.result(
          m.readInput()
            .map(s => Success(s + System.lineSeparator()))
            .recover { case t => Failure(t) },
          Duration.Inf
        )
      }
      log.info(s"Received input ${res.map { case "" => "[empty]"; case _ => "[non empty]" }}")

      res match {
        case Success(s)                                    => Some(s)
        case Failure(_: InputManager.NoMoreInputException) => None
        case Failure(e) => throw new Exception("Error getting more input", e)
      }
    }
  )

  private var currentPublishOpt0 = Option.empty[OutputHandler]

  private val capture0 =
    if (trapOutput)
      Capture.nop()
    else
      Capture.create(mirrorToConsole = !quiet)

  private val updatableResultsOpt0 =
    updateBackgroundVariablesEcOpt.map { ec =>
      new UpdatableResults(
        ec,
        logCtx,
        data => commHandlerOpt.foreach(_.updateDisplay(data)) // throw if commHandlerOpt is empty?
      )
    }

  private val resultVariables = new mutable.HashMap[String, String]
  private val resultOutput    = new StringBuilder
  private val resultStream =
    new FunctionOutputStream(20, 20, UTF_8, resultOutput.append(_)).printStream()

  private var currentLine0          = initialCellCount
  private var currentNoHistoryLine0 = Int.MaxValue / 2

  private val printer0 = Printer(
    capture0.out,
    capture0.err,
    resultStream,
    s => currentPublishOpt0.fold(Console.err.println(s))(_.stderr(s + System.lineSeparator())),
    s => currentPublishOpt0.fold(Console.err.println(s))(_.stderr(s + System.lineSeparator())),
    // to stdout in notebooks, not to get a red background,
    // but stderr in the console, not to pollute stdout
    s => currentPublishOpt0.fold(Console.err.println(s))(_.stdout(s + System.lineSeparator()))
  )

  private def useOptions(
    ammInterp: ammonite.interp.Interpreter,
    options: KernelOptions
  ): Either[String, Unit] = {

    for (input <- options.extraRepositories) {
      val repo =
        if (input.startsWith("ivy:"))
          IvyRepository.of(input.drop("ivy:".length))
        else
          MavenRepository.of(input)
      ammInterp.repositories.update(ammInterp.repositories() :+ repo)
    }

    almond.internals.ConfigureCompiler.addOptions(ammInterp.interpApi)(
      options.scalacOptions.toSeq.map(_.value.value)
    )

    val params       = ScalaParameters(ammInterp.scalaVersion)
    val compatParams = ScalaParameters(scala.util.Properties.versionNumberString)
    val deps = options.dependencies.map { dep =>
      val params0 =
        if (dep.userParams.get("compat").nonEmpty) compatParams
        else params
      dep.applyParams(params0).toCs
    }
    val loadDepsRes =
      if (deps.isEmpty) Right(Nil)
      else ammInterp.loadIvy(deps: _*)
    loadDepsRes.map { loaded =>
      ammInterp.headFrame.addClasspath(loaded.map(_.toURI.toURL))
      ()
    }
  }

  def history: History =
    history0

  def printer: Printer =
    printer0

  def currentLine: Int = currentLine0
  def incrementLineCount(): Unit = {
    currentLine0 += 1
  }

  def currentInputManagerOpt: Option[InputManager] =
    currentInputManagerOpt0
  def currentPublishOpt: Option[OutputHandler] =
    currentPublishOpt0

  lazy val updatableResults: JupyterApi.UpdatableResults =
    new JupyterApi.UpdatableResults {
      override def updatable(k: String, v: String) =
        resultVariables += k -> v
      override def update(k: String, v: String, last: Boolean) =
        updatableResultsOpt0 match {
          case None    => throw new Exception("Results updating not available")
          case Some(r) => r.update(k, v, last)
        }
    }

  private def withInputManager[T](m: Option[InputManager], done: Boolean = true)(f: => T): T = {
    val previous = currentInputManagerOpt0
    try {
      currentInputManagerOpt0 = m
      f
    }
    finally {
      currentInputManagerOpt0 = previous
      if (done)
        m.foreach(_.done())
    }
  }

  private def withClientStdin[T](t: => T): T =
    Console.withIn(input0) {
      val previous = System.in
      try {
        System.setIn(input0)
        t
      }
      finally {
        System.setIn(previous)
        input0.clear()
      }
    }

  private def withOutputHandler[T](handlerOpt: Option[OutputHandler])(f: => T): T = {
    val previous = currentPublishOpt0
    try {
      currentPublishOpt0 = handlerOpt
      f
    }
    finally
      currentPublishOpt0 = previous
  }

  private def capturingOutput[T](t: => T): T =
    currentPublishOpt0 match {
      case None    => t
      case Some(p) => capture0(p.stdout, p.stderr)(t)
    }

  private def interruptible[T](jupyterApi: JupyterApi)(t: => T): T = {
    interruptedStackTraceOpt0 = None
    currentThreadOpt0 = Some(Thread.currentThread())
    try
      Signaller("INT") {
        currentThreadOpt0 match {
          case None =>
            log.warn("Received SIGINT, but no execution is running")
          case Some(t) =>
            interruptedStackTraceOpt0 = Some(t.getStackTrace)
            log.debug(
              s"Received SIGINT, stopping thread $t\n${interruptedStackTraceOpt0.map("  " + _).mkString("\n")}"
            )
            if (useThreadInterrupt) {
              log.debug(s"Calling 'Thread.interrupt'")
              t.interrupt()
            }
            else {
              log.debug(s"Calling 'Thread.stop'")
              t.stop()
            }

            // Run post-interrupt hooks
            jupyterApi.runPostInterruptHooks()
        }
      }.apply {
        t
      }
    finally
      currentThreadOpt0 = None
  }

  def interrupt(jupyterApi: JupyterApi): Unit =
    currentThreadOpt0 match {
      case None =>
        log.warn("Interrupt asked, but no execution is running")
      case Some(t) =>
        log.debug(
          s"Interrupt asked, stopping thread $t\n${t.getStackTrace.map("  " + _).mkString("\n")}"
        )
        if (useThreadInterrupt) {
          log.debug(s"Calling 'Thread.interrupt'")
          t.interrupt()
        }
        else {
          log.debug(s"Calling 'Thread.stop'")
          t.stop()
        }

        // Run post-interrupt hooks
        jupyterApi.runPostInterruptHooks()
    }

  private var lastExceptionOpt0 = Option.empty[Throwable]

  def lastExceptionOpt: Option[Throwable] = lastExceptionOpt0

  private def incrementLine(storeHistory: Boolean): Unit =
    if (storeHistory)
      currentLine0 += 1
    else
      currentNoHistoryLine0 += 1

  def loadOptions(ammInterp: ammonite.interp.Interpreter, options: KernelOptions): Unit =
    useOptions(ammInterp, options) match {
      case Left(err) =>
        log.warn(s"Error loading initial kernel options: $err")
      case Right(()) =>
    }

  private def ammResult(
    ammInterp: ammonite.interp.Interpreter,
    code: String,
    inputManager: Option[InputManager],
    outputHandler: Option[OutputHandler],
    storeHistory: Boolean,
    jupyterApi: JupyterApi
  ) =
    withOutputHandler(outputHandler) {
      val code0 = {
        val ls = System.lineSeparator()
        if (ammInterp.scalaVersion.startsWith("2.") || code.endsWith(ls))
          code
        else
          code + ls
      }
      for {
        stmts <- ammonite.compiler.Parsers.split(code0, ignoreIncomplete = false) match {
          case None =>
            // In Scala 2? cannot happen with ignoreIncomplete = false.
            // In Scala 3, this might unexpectedly happen. The lineSeparator stuff above
            // tries to avoid some cases where this happens.
            Res.Skip
          case Some(Right(stmts)) =>
            Res.Success(stmts)
          case Some(Left(err)) =>
            Res.Failure(err)
        }
        _ = log.debug(s"splitted '$code0'")
        ev <- interruptible(jupyterApi) {
          withInputManager(inputManager) {
            withClientStdin {
              capturingOutput {
                resultOutput.clear()
                resultVariables.clear()
                log.debug(s"Compiling / evaluating $code0 ($stmts)")
                val r = ammInterp.processLine(
                  code0,
                  stmts,
                  (if (storeHistory) currentLine0 else currentNoHistoryLine0) + 1,
                  silent = silent(),
                  incrementLine = () => incrementLine(storeHistory)
                )

                log.debug(s"Handling output of '$code0'")
                Repl.handleOutput(ammInterp, r)
                r match {
                  case Res.Exception(ex, _) =>
                    lastExceptionOpt0 = Some(ex)
                  case _ =>
                }

                val variables = resultVariables.toMap
                val res0      = resultOutput.result()
                log.debug(s"Result of '$code0': $res0")
                resultOutput.clear()
                resultVariables.clear()
                val data =
                  if (variables.isEmpty)
                    if (res0.isEmpty)
                      DisplayData.empty
                    else
                      DisplayData.text(res0)
                  else
                    updatableResultsOpt0 match {
                      case None =>
                        DisplayData.text(res0)
                      case Some(r) =>
                        val baos = new ByteArrayOutputStream
                        val haos = new HtmlAnsiOutputStream(baos)
                        haos.write(res0.getBytes(StandardCharsets.UTF_8))
                        haos.close()
                        val html =
                          s"""<div class="jp-RenderedText">
                             |<pre><code>${baos.toString("UTF-8")}</code></pre>
                             |</div>""".stripMargin
                        log.debug(s"HTML: $html")
                        val d = r.add(
                          almond.display.Data(
                            almond.display.Text.mimeType -> res0,
                            almond.display.Html.mimeType -> html
                          ).displayData(),
                          variables
                        )
                        outputHandler match {
                          case None =>
                            d
                          case Some(h) =>
                            h.display(d)
                            DisplayData.empty
                        }
                    }
                r.map((_, data))
              }
            }
          }
        }
      } yield ev
    }

  def apply(
    ammInterp: ammonite.interp.Interpreter,
    code: String,
    inputManager: Option[InputManager],
    outputHandler: Option[OutputHandler],
    colors0: Ref[Colors],
    storeHistory: Boolean,
    executeHooks: Seq[JupyterApi.ExecuteHook],
    jupyterApi: JupyterApi
  ): ExecuteResult = {

    if (enableExitHack && code.endsWith("// ALMOND FORCE EXIT")) {
      log.debug("Exit hack enabled and code ends with force-exit comment, exiting")
      sys.exit(0)
    }

    if (storeHistory) {
      storage.fullHistory() = storage.fullHistory() :+ code
      history0 = history0 :+ code
    }

    val finalCodeOrResult =
      withOutputHandler(outputHandler) {
        interruptible(jupyterApi) {
          withInputManager(inputManager, done = false) {
            withClientStdin {
              capturingOutput {
                executeHooks.foldLeft[Try[Either[JupyterApi.ExecuteHookResult, String]]](
                  Success(Right(code))
                ) {
                  (codeOrDisplayDataAttempt, hook) =>
                    codeOrDisplayDataAttempt.flatMap { codeOrDisplayData =>
                      try Success(codeOrDisplayData.flatMap { value =>
                          hook.hook(value)
                        })
                      catch {
                        case e: Throwable => // kind of meh, but Ammonite does the same it seems…
                          Failure(e)
                      }
                    }
                }
              }
            }
          }
        }
      }

    finalCodeOrResult match {
      case Failure(ex) =>
        log.error(s"exception when running hooks (${ex.getMessage})", ex)
        Execute.error(colors0(), Some(ex), "")

      case Success(Left(res)) =>
        res match {
          case s: JupyterApi.ExecuteHookResult.Success =>
            ExecuteResult.Success(s.data)
          case e: JupyterApi.ExecuteHookResult.Error =>
            ExecuteResult.Error(e.name, e.message, e.stackTrace)
          case JupyterApi.ExecuteHookResult.Abort =>
            ExecuteResult.Abort
          case JupyterApi.ExecuteHookResult.Exit =>
            ExecuteResult.Exit
        }

      case Success(Right(emptyCode)) if emptyCode.trim.isEmpty =>
        ExecuteResult.Success()

      case Success(Right(finalCode)) =>
        val path      = Left(s"cell$currentLine0.sc")
        val scopePath = ScopePath(Left("."), os.sub)
        handlers.parse(finalCode, path, scopePath) match {
          case Left(err) =>
            log.error(s"exception while processing directives (${err.getMessage})", err)
            Execute.error(colors0(), Some(err), err.getMessage)
          case Right(res) =>
            val maybeOptions = res
              .flatMap(_.global.map(_.kernelOptions).toSeq)
              .sequence
              .map(_.foldLeft(KernelOptions())(_ + _))
            maybeOptions match {
              case Left(err) =>
                // FIXME Use positions in the exception to report errors as diagnostics
                // FIXME This discards all errors but the first
                Execute.error(colors0(), Some(err.head), "")
              case Right(options) =>
                val optionsRes = useOptions(ammInterp, options)

                if (
                  options.ignoredDirectives.nonEmpty &&
                  outputHandler
                    .flatMap(_.messageIdOpt)
                    .forall(id => !ignoreLauncherDirectivesIn.contains(id))
                ) {
                  def printErr(s: String): Unit =
                    outputHandler match {
                      case Some(h) => h.stderr(s + System.lineSeparator())
                      case None    => System.err.println(s)
                    }
                  printErr(
                    s"Warning: ignoring ${options.ignoredDirectives.length} directive(s) that can only be used prior to any code:"
                  )
                  for (dir <- options.ignoredDirectives.map(_.directive))
                    printErr(s"  //> using ${dir.directive.key}")
                }

                optionsRes match {
                  case Left(failureMsg) =>
                    // kind of meh that we have to build a new Exception here
                    Execute.error(colors0(), Some(new Exception(failureMsg)), "")
                  case Right(()) =>
                    ammResult(
                      ammInterp,
                      finalCode,
                      inputManager,
                      outputHandler,
                      storeHistory,
                      jupyterApi
                    ) match {
                      case Res.Success((_, data)) =>
                        ExecuteResult.Success(data)
                      case Res.Failure(msg) =>
                        interruptedStackTraceOpt0 match {
                          case None =>
                            val err = Execute.error(colors0(), None, msg)
                            outputHandler.foreach(_.stderr(err.message)) // necessary?
                            err
                          case Some(st) =>
                            val cutoff = Set("$main", "evaluatorRunPrinter")

                            ExecuteResult.Error(
                              "Interrupted!",
                              "",
                              List("Interrupted!") ++ st
                                .takeWhile(x => !cutoff(x.getMethodName))
                                .map(ExecuteResult.Error.highlightFrame(
                                  _,
                                  fansi.Attr.Reset,
                                  colors0().literal()
                                ))
                                .map(_.render)
                                .toList
                            )
                        }

                      case Res.Exception(ex, msg) =>
                        log.error(s"exception in user code (${ex.getMessage})", ex)
                        Execute.error(colors0(), Some(ex), msg)

                      case Res.Skip =>
                        if (!options.isEmpty)
                          incrementLine(storeHistory)
                        ExecuteResult.Success()

                      case Res.Exit(_) =>
                        ExecuteResult.Exit
                    }
                }
            }
        }
    }
  }
}

object Execute {
  def error(colors: Colors, exOpt: Option[Throwable], msg: String) =
    ExecuteResult.Error.error(colors.error(), colors.literal(), exOpt, msg)
}
