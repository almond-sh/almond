package almond

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.charset.StandardCharsets.UTF_8

import almond.amm.{AmmInterpreter, ReloadingSourceHook}
import almond.api.JupyterApi
import almond.directives.{DirectiveKeys, KernelOptions}
import almond.internals.{
  Capture,
  DisplayIdTracker,
  FunctionInputStream,
  FunctionOutputStream,
  HtmlAnsiOutputStream,
  UpdatableResults
}
import almond.interpreter.{ExecuteError, Message}
import almond.interpreter.api.{CommHandler, DisplayData, ExecuteResult, OutputHandler}
import almond.interpreter.input.InputManager
import almond.launcher.directives.CustomGroup
import almond.logger.LoggerContext
import almond.logger.internal.PrintStreamLogger
import almond.protocol.{Execute => ProtocolExecute}
import almond.util.SigInt
import ammonite.compiler.{CodeClassWrapper, Parsers}
import ammonite.compiler.iface.Preprocessor
import ammonite.repl.api.History
import ammonite.repl.Repl
import ammonite.directives.{DirectiveValue, Position, UsingDirective}
import ammonite.runtime.{DirectiveHook, ImportHook, Storage}
import ammonite.util.{Colors, Evaluated, Ex, Imports, Name, Printer, Ref, Res}
import ammonite.util.Util.CodeSource
import coursierapi.{Repository, RepositoryParser}
import dependency.ScalaParameters
import dependency.api.ops._
import fastparse.Parsed

import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
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
  outputStyle: Ref[OutputStyle],
  useThreadInterrupt: Boolean,
  initialCellCount: Int,
  enableExitHack: Boolean,
  ignoreLauncherDirectivesIn: Set[String],
  launcherDirectiveGroups: Seq[CustomGroup],
  pkgName: Seq[String] = AmmInterpreter.defaultPkgName,
  wrapperPath: Seq[Name] = CodeClassWrapper.wrapperPath
) {

  private val log = logCtx(getClass)

  private var currentInputManagerOpt0 = Option.empty[InputManager]

  private var interruptedStackTraceOpt0 = Option.empty[Array[StackTraceElement]]
  private var currentThreadOpt0         = Option.empty[Thread]

  private var history0 = new History(Vector())

  private val input0 = new FunctionInputStream(
    UTF_8,
    currentInputManagerOpt0.flatMap { m =>

      val res = {
        log.info("Awaiting input")

        try Success(Await.result(m.readInput(), Duration.Inf))
        catch {
          case t: Throwable => Failure(t)
        }
      }
      log.info(s"Received input ${res.map { case "" => "[empty]"; case _ => "[non empty]" }}")

      res match {
        case Success(s)                                    => Some(s + System.lineSeparator())
        case Failure(_: InputManager.NoMoreInputException) => None
        case Failure(e) => throw new Exception("Error getting more input", e)
      }
    }
  )

  private var currentPublishOpt0 = Option.empty[OutputHandler]

  @volatile private var currentDisplayIdTracker0 = Option.empty[DisplayIdTracker]

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

  private val printer0 = {
    def doPrint(s: String, toClientStdout: Boolean = false): Unit =
      currentPublishOpt0 match {
        case None => Console.err.println(s)
        case Some(outputHandler) =>
          if (!quiet)
            capture0.originalErr.getOrElse(System.err).println(s)
          if (toClientStdout)
            outputHandler.stdout(s + System.lineSeparator())
          else
            outputHandler.stderr(s + System.lineSeparator())
      }
    Printer(
      capture0.out,
      capture0.err,
      resultStream,
      doPrint(_),
      doPrint(_),
      // to stdout in notebooks, not to get a red background,
      // but stderr in the console, not to pollute stdout
      doPrint(_, toClientStdout = true)
    )
  }

  def history: History =
    history0

  def printer: Printer =
    printer0

  def currentLine: Int = currentLine0
  def incrementLineCount(): Unit = {
    currentLine0 += 1
  }

  /** Line count of the code that doesn't get an execution count, like silent cells, or the code
    * loaded with `repl.load`
    */
  def currentNoHistoryLine: Int = currentNoHistoryLine0
  def incrementNoHistoryLineCount(): Unit = {
    currentNoHistoryLine0 += 1
  }

  def currentInputManagerOpt: Option[InputManager] =
    currentInputManagerOpt0
  def currentPublishOpt: Option[OutputHandler] =
    currentPublishOpt0

  /** Whether an element with display id `id` was displayed in the output of the current cell */
  def displayedInCurrentCell(id: String): Boolean =
    currentDisplayIdTracker0.exists(_.wasDisplayed(id))

  lazy val updatableResults: JupyterApi.UpdatableResults =
    new JupyterApi.UpdatableResults {
      override def updatable(k: String, v: String) =
        resultVariables += k -> v
      override def update(k: String, v: String, last: Boolean) =
        updatableResultsOpt0 match {
          case None    => throw new Exception("Results updating not available")
          case Some(r) => r.update(k, v, last)
        }
      override def updateLazily(k: String, v: () => String, last: Boolean) =
        updatableResultsOpt0 match {
          case None    => throw new Exception("Results updating not available")
          case Some(r) => r.update(k, v(), last)
        }
    }

  private def withInputManager[T](m: Option[InputManager], done: Boolean)(f: => T): T = {
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

  /** Stops the thread running a cell, with `Thread.stop`, or with `Thread.interrupt` if asked to,
    * or if `Thread.stop` isn't supported (JDK 20 and later, where it throws an
    * `UnsupportedOperationException`).
    */
  private def stopThread(t: Thread): Unit =
    if (useThreadInterrupt || Execute.isJdk20OrHigher) {
      log.debug(s"Calling 'Thread.interrupt'")
      t.interrupt()
    }
    else {
      log.debug(s"Calling 'Thread.stop'")
      t.stop()
    }

  /** Guards [[currentThreadOpt0]], so that interrupting the thread running user code and the end of
    * that code don't race
    */
  private val interruptLock = new Object

  /** Runs `t` with a SIGINT handler that interrupts the user code it runs (see
    * [[interruptibleSection]])
    *
    * The handler stays installed while `t` runs, even outside of user code, so that SIGINTs
    * received while we handle the outputs of a cell only get logged, rather than going to the
    * handler that was there before.
    */
  private def withSigIntHandler[T](jupyterApi: JupyterApi)(t: => T): T =
    SigInt.scoped { () =>
      interruptCurrentThread(jupyterApi, "Received SIGINT", fromSignal = true)
    } {
      t
    }

  /** Runs user code, that SIGINT (see [[withSigIntHandler]]) or interrupt requests (see
    * [[interrupt]]) can interrupt
    */
  private def interruptibleSection[T](t: => T): T = {
    interruptLock.synchronized {
      interruptedStackTraceOpt0 = None
      currentThreadOpt0 = Some(Thread.currentThread())
    }
    try t
    finally
      interruptLock.synchronized {
        currentThreadOpt0 = None
        // When interrupted with Thread.interrupt, code that doesn't check for interruptions (like
        // busy loops) runs until its end, and the interrupt flag of the thread is still set
        // afterwards. Same if the interruption comes right when user code returns. We clear that
        // flag, so that it doesn't break what we run next on this thread (publishing outputs,
        // compiling and running the next cells, …).
        if (Thread.interrupted())
          log.debug("Cleared interrupt flag of thread after running user code")
      }
  }

  private def interruptible[T](jupyterApi: JupyterApi)(t: => T): T =
    withSigIntHandler(jupyterApi) {
      interruptibleSection(t)
    }

  private def interruptCurrentThread(
    jupyterApi: JupyterApi,
    reason: String,
    fromSignal: Boolean
  ): Unit = {
    val interrupted = interruptLock.synchronized {
      currentThreadOpt0 match {
        case None =>
          false
        case Some(t) =>
          val stackTrace = t.getStackTrace
          if (fromSignal)
            interruptedStackTraceOpt0 = Some(stackTrace)
          log.debug {
            val nl = System.lineSeparator()
            s"$reason, stopping thread $t$nl${stackTrace.map("  " + _).mkString(nl)}"
          }
          stopThread(t)
          true
      }
    }

    if (interrupted)
      jupyterApi.runPostInterruptHooks()
    else
      log.warn(s"$reason, but no execution is running")
  }

  def interrupt(jupyterApi: JupyterApi): Unit =
    interruptCurrentThread(jupyterApi, "Interrupt asked", fromSignal = false)

  private var lastExceptionOpt0 = Option.empty[Throwable]

  def lastExceptionOpt: Option[Throwable] = lastExceptionOpt0

  private def incrementLine(storeHistory: Boolean): Unit =
    if (storeHistory)
      currentLine0 += 1
    else
      currentNoHistoryLine0 += 1

  /** Loads kernel options passed upfront
    *
    * @return
    *   an error to report in the current cell, if they couldn't be loaded (like an unknown
    *   dependency)
    */
  def loadOptions(
    ammInterp: ammonite.interp.Interpreter,
    options: KernelOptions,
    outputHandler: Option[OutputHandler],
    colors: Colors
  ): Option[ExecuteResult.Error] =
    // with the output handler set, so that the notebook coursier logger can show the downloads of
    // the dependencies of these options
    withOutputHandler(outputHandler)(loadDirectives(ammInterp, options.directives)) match {
      case Res.Success(_) | Res.Skip =>
        None
      case Res.Failure(err) =>
        log.warn(s"Error loading initial kernel options: $err")
        // same error as when loading the directives of a cell fails
        Some(Execute.error(colors, None, err))
      case Res.Exception(ex, msg) =>
        log.warn(s"Error loading initial kernel options: $msg", ex)
        Some(Execute.error(colors, Some(ex), msg))
      case Res.Exit(_) =>
        None
    }

  private var standaloneSourceCount = 0

  /** The hooks loading scripts (`import $file.…`, `//> using script …`), that reload scripts that
    * changed since they were last loaded
    */
  val scriptHooks: ReloadingSourceHook.Hooks = ReloadingSourceHook.hooks()

  /** The hooks handling the using directives of cells (`//> using …`), by directive key
    *
    * Ammonite's ones (dependencies, repositories, scripts), along with ours, for compiler options
    * (that we check before passing them to the compiler), the output style, and the directives of
    * the launcher (that we ignore). Ammonite handles the directives of cells before compiling them,
    * and offers completions for their keys and values.
    */
  lazy val directiveHooks: Map[String, DirectiveHook] =
    scriptHooks.directiveHooks ++
      DirectiveKeys.scalacOption.map(_ -> ScalacOptionsHook) ++
      DirectiveKeys.outputStyle.map(_ -> OutputStyleHook) ++
      (DirectiveKeys.launcher ++ launcherDirectiveGroups.map(_.prefix))
        .map(_ -> LauncherDirectiveHook)

  /** The hook of a directive whose key we don't know in advance, if any (like those of the custom
    * directive groups of the launcher, such as `//> using spark.version …`)
    */
  def extraDirectiveHook(key: String): Option[DirectiveHook] =
    if (launcherDirectiveGroups.exists(_.matches(key))) Some(LauncherDirectiveHook)
    else None

  /** Handles compiler options (`//> using option …`)
    *
    * Unlike Ammonite's hook, this checks the options before passing them to the compiler, so that
    * invalid ones are reported right away (and not upon each later compilation)
    */
  private object ScalacOptionsHook extends DirectiveHook {
    def handle(
      source: CodeSource,
      directive: UsingDirective,
      interp: ImportHook.InterpreterInterface,
      wrapperPath: Seq[Name]
    ): Either[String, Seq[ImportHook.Result]] =
      interp match {
        case ammInterp: ammonite.interp.Interpreter =>
          for {
            options  <- DirectiveHook.values(directive)
            warnings <- almond.internals.ConfigureCompiler.addOptions(ammInterp.interpApi)(options)
          } yield {
            for (warning <- warnings)
              printer0.warning(s"Warning: $warning")
            Nil
          }
        case _ =>
          DirectiveHook.ScalacOptions.handle(source, directive, interp, wrapperPath)
      }
  }

  /** Changes how the results of the next cells are displayed (`//> using outputStyle python`) */
  private object OutputStyleHook extends DirectiveHook {
    def handle(
      source: CodeSource,
      directive: UsingDirective,
      interp: ImportHook.InterpreterInterface,
      wrapperPath: Seq[Name]
    ): Either[String, Seq[ImportHook.Result]] =
      for {
        values <- DirectiveHook.values(directive)
        style  <- OutputStyle.parse(values.last)
      } yield {
        outputStyle() = style
        Nil
      }
    override def complete(
      interp: ammonite.interp.Interpreter,
      directory: os.Path,
      prefix: String
    ): (Int, Seq[String]) =
      (0, OutputStyle.all.map(_.name).filter(_.startsWith(prefix)))
  }

  /** Ignores the directives of the launcher (`//> using scala …`, `//> using spark.version …` for a
    * custom `spark` group, …), that can only be used before the kernel is started
    *
    * Unless the launcher already handled them (in the first cell with code, that both it and us
    * run), users are warned about them.
    */
  private object LauncherDirectiveHook extends DirectiveHook {
    def handle(
      source: CodeSource,
      directive: UsingDirective,
      interp: ImportHook.InterpreterInterface,
      wrapperPath: Seq[Name]
    ): Either[String, Seq[ImportHook.Result]] = {
      val handledByLauncher = currentPublishOpt0
        .flatMap(_.messageIdOpt)
        .exists(ignoreLauncherDirectivesIn.contains)
      if (!handledByLauncher)
        printer0.warning(
          s"Warning: ignoring '//> using ${directive.key}', " +
            "that can only be used prior to any code"
        )
      Right(Nil)
    }
  }

  /** Loads directives, like those of the kernel options the launcher passes us
    *
    * These are handled with the same hooks as those of cells, as if they were in a cell.
    */
  private def loadDirectives(
    ammInterp: ammonite.interp.Interpreter,
    directives: Seq[(String, Seq[String])]
  ): Res[Unit] = {
    // the source the scripts are loaded from - the same as the one Ammonite uses for cells,
    // so that scripts are loaded exactly like via `import $file.…` from a cell (paths are relative
    // to the working directory)
    val source = CodeSource(
      Name("cell"),
      Nil,
      pkgName.map(Name(_)),
      Some((os.pwd / "(console)").toNIO)
    )
    val position = Position(0, 0, 0)
    val maybeImports = Res.map(directives) {
      case (key, values) =>
        val directive = UsingDirective(
          key,
          values.map(DirectiveValue.StringVal(_, isQuoted = false, position)),
          position
        )
        ammInterp.resolveUsingDirective(source, directive, wrapperPath)
    }
    maybeImports.map { imports =>
      ammInterp.handleImports(imports.foldLeft(Imports())(_ ++ _))
    }
  }

  private def ammResult(
    ammInterp: ammonite.interp.Interpreter,
    code: String,
    inputManager: Option[InputManager],
    outputHandler: Option[OutputHandler],
    storeHistory: Boolean,
    jupyterApi: JupyterApi
  ): Res[DisplayData] =
    withOutputHandler(outputHandler) {
      val code0 = {
        val ls = System.lineSeparator()
        if (ammInterp.scalaVersion.startsWith("2.") || code.endsWith(ls))
          code
        else
          code + ls
      }
      // TODO Ignore comments before any package directive too
      // (standalone sources can't have directives then, as these need to be at the very beginning)
      val isStandaloneSource = code.startsWith("package ")
      if (isStandaloneSource) {
        val count = standaloneSourceCount
        standaloneSourceCount = standaloneSourceCount + 1
        for {
          output <- interruptible(jupyterApi) {
            capturingOutput {
              Res(
                ammInterp.compilerManager.compileClass(
                  Preprocessor.Output(code, 0, 0),
                  printer0,
                  s"standalone-source-$count.scala"
                ),
                "Compilation Failed"
              )
            }
          }
          _ = {
            for ((name, bytes) <- output.classFiles)
              ammInterp.headFrame.classloader.addClassFile(
                name.stripSuffix(".class").replace('/', '.'),
                bytes
              )
          }
        } yield DisplayData.empty
      }
      else
        // Directives (`//> using …`) are handled by Ammonite, in processLine, before compiling
        // the cell (see directiveHooks)
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
          ev <- withSigIntHandler(jupyterApi) {
            withInputManager(inputManager, done = false) {
              withClientStdin {
                capturingOutput {
                  resultOutput.clear()
                  resultVariables.clear()
                  log.debug(s"Compiling / evaluating $code0 ($stmts)")
                  val r = interruptibleSection {
                    ammInterp.processLine(
                      code0,
                      stmts,
                      (if (storeHistory) currentLine0 else currentNoHistoryLine0) + 1,
                      silent = silent(),
                      incrementLine = () => incrementLine(storeHistory)
                    )
                  }

                  // Send the updates that the cell triggered and that are still pending,
                  // so that they reach the front-end before the cell is marked as done
                  updatableResultsOpt0.foreach(_.flush())

                  val updatedRes = r match {
                    case ex: Res.Exception =>
                      val exOpt =
                        jupyterApi.exceptionHandlers().foldLeft[Option[Throwable]](Some(ex.t)) {
                          (exOpt, handler) =>
                            exOpt.flatMap(handler.handle)
                        }
                      exOpt match {
                        case Some(ex0) =>
                          if (ex0 == ex.t) ex
                          else ex.copy(t = ex0)
                        case None =>
                          ex.copy(t = JupyterApi.ExceptionHandler.noException())
                      }
                    case _ =>
                      r
                  }

                  log.debug(s"Handling output of '$code0'")
                  Repl.handleOutput(ammInterp, updatedRes)
                  updatedRes match {
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
                  updatedRes.map(_ => data)
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
    outputHandler0: Option[OutputHandler],
    colors0: Ref[Colors],
    storeHistory: Boolean,
    executeHooks: Seq[JupyterApi.ExecuteHook],
    postRunHooks: Seq[JupyterApi.PostRunHook],
    jupyterApi: JupyterApi
  ): ExecuteResult = {
    val tracker  = new DisplayIdTracker
    val previous = currentDisplayIdTracker0
    currentDisplayIdTracker0 = Some(tracker)
    try apply0(
        ammInterp,
        code,
        inputManager,
        outputHandler0.map(tracker.wrap),
        colors0,
        storeHistory,
        executeHooks,
        postRunHooks,
        jupyterApi
      )
    finally currentDisplayIdTracker0 = previous
  }

  private def apply0(
    ammInterp: ammonite.interp.Interpreter,
    code: String,
    inputManager: Option[InputManager],
    outputHandler: Option[OutputHandler],
    colors0: Ref[Colors],
    storeHistory: Boolean,
    executeHooks: Seq[JupyterApi.ExecuteHook],
    postRunHooks: Seq[JupyterApi.PostRunHook],
    jupyterApi: JupyterApi
  ): ExecuteResult = {

    if (enableExitHack && code.endsWith("// ALMOND FORCE EXIT")) {
      log.debug("Exit hack enabled and code ends with force-exit comment, exiting")
      sys.exit(0)
    }

    val lineBefore = currentLine0

    if (storeHistory) {
      storage.fullHistory() = storage.fullHistory() :+ code
      history0 = history0 :+ code
    }

    val finalCodeOrResult =
      if (executeHooks.isEmpty) Success(Right(code))
      else
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

    val result = finalCodeOrResult match {
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
        ammResult(
          ammInterp,
          finalCode,
          inputManager,
          outputHandler,
          storeHistory,
          jupyterApi
        ) match {
          case Res.Success(data) =>
            ExecuteResult.Success(data)
          case Res.Failure(msg) =>
            interruptedStackTraceOpt0 match {
              case None =>
                // Not printed to stderr: the error output that the kernel publishes
                // for this result already shows the message (L4). This includes the errors of
                // directives, like invalid compiler options, or dependency resolution errors -
                // no stack trace for those, which would only point at the kernel code reporting
                // the error (L9).
                Execute.error(colors0(), None, msg)
              case Some(st) =>
                val cutoff = Set("$main", "evaluatorRunPrinter")

                ExecuteResult.Error(
                  "Interrupted!",
                  "",
                  List("Interrupted!") ++ st
                    .takeWhile(x => !cutoff(x.getMethodName))
                    .map(ExecuteError.highlightFrame(
                      _,
                      fansi.Attr.Reset,
                      colors0().literal()
                    ))
                    .map(_.render)
                    .toList
                )
            }

          case Res.Exception(ex, msg) =>
            log.error(
              s"exception in user code (${PrintStreamLogger.exceptionString(ex)})",
              ex
            )
            Execute.error(colors0(), Some(ex), msg)

          case Res.Skip =>
            // cells with only comments, or directives that bring nothing in scope
            ExecuteResult.Success()

          case Res.Exit(_) =>
            ExecuteResult.Exit
        }
    }

    // Like ipykernel, every cell but empty ones gets an execution count, whether it succeeds or
    // not, and whether it runs code or not (cells failing to compile, magic-only cells, …), so
    // that the next cell gets a new one (L2). Ammonite only increments it for cells that it
    // compiles successfully.
    if (storeHistory && code.trim.nonEmpty && currentLine0 == lineBefore)
      currentLine0 += 1

    if (postRunHooks.isEmpty) {
      withInputManager(inputManager, done = true)(())
      result
    }
    else {
      val maybeRes = withOutputHandler(outputHandler) {
        interruptible(jupyterApi) {
          withInputManager(inputManager, done = true) {
            withClientStdin {
              capturingOutput {
                postRunHooks.foldLeft[Try[ExecuteResult]](Success(result)) {
                  (res, hook) =>
                    res.flatMap { res0 =>
                      Try {
                        hook.process(res0)
                      }
                    }
                }
              }
            }
          }
        }
      }

      maybeRes match {
        case Success(res) => res
        case Failure(ex) =>
          log.error(s"exception when running post run hooks (${ex.getMessage})", ex)
          Execute.error(colors0(), Some(ex), "")
      }
    }
  }
}

object Execute {

  def error(colors: Colors, exOpt: Option[Throwable], msg: String) =
    ExecuteError.error(colors.error(), colors.literal(), exOpt, msg)

  /** Parses repositories the same way as Coursier and Scala CLI: URLs, `ivy:`-prefixed Ivy
    * patterns, but also predefined repositories like `m2Local`, `ivy2Local`, `jitpack`, or
    * `sonatype:snapshots`
    */
  private[almond] def parseRepository(input: String): Either[String, Repository] =
    try Right(RepositoryParser.repository(input))
    catch {
      // RepositoryParsingError is the declared error, but malformed URLs actually
      // end up as IllegalArgumentException-s
      case e @ (_: coursierapi.error.RepositoryParsingError | _: IllegalArgumentException) =>
        Left(s"Error parsing repository '$input': ${e.getMessage}")
    }

  /** Applies the Scala parameters of the passed Scala version to dependencies, and converts them to
    * coursier-interface ones. Dependencies with a `compat` user param get those of the Scala 2
    * library on the class path if there's one (Scala 2, or Scala 3 before 3.8), and those of the
    * default Scala 2.13 version of Almond otherwise (Scala 3.8 and later, that ship their own
    * library).
    */
  private[almond] def toCoursierDependencies(
    scalaVersion: String,
    dependencies: Seq[dependency.AnyDependency]
  ): Seq[coursierapi.Dependency] = {
    val params = ScalaParameters(scalaVersion)
    val compatParams = ScalaParameters(
      if (scala.util.Properties.versionNumberString.startsWith("2."))
        scala.util.Properties.versionNumberString
      else
        almond.internal.InterpreterConstants.defaultScala213Version
    )
    dependencies.map { dep =>
      val params0 =
        if (dep.userParams.exists(_._1 == "compat")) compatParams
        else params
      dep.applyParams(params0).toCs
    }
  }

  private lazy val isJdk20OrHigher =
    sys.props
      .get("java.version")
      .flatMap { ver =>
        val ver0 = ver.stripPrefix("1.").takeWhile(_.isDigit)
        if (ver0.isEmpty) None
        else Some(ver0.toInt)
      }
      .exists(_ >= 20)
}
