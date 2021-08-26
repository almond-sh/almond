package almond

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.charset.StandardCharsets.UTF_8

import almond.api.JupyterApi
import almond.internals.{Capture, FunctionInputStream, FunctionOutputStream, HtmlAnsiOutputStream, UpdatableResults}
import almond.interpreter.ExecuteResult
import almond.interpreter.api.{CommHandler, DisplayData, OutputHandler}
import almond.interpreter.input.InputManager
import almond.logger.LoggerContext
import ammonite.compiler.Parsers
import ammonite.repl.api.History
import ammonite.repl.{Repl, Signaller}
import ammonite.runtime.Storage
import ammonite.util.{Colors, Ex, Printer, Ref, Res}
import fastparse.Parsed

import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

/**
  * Wraps contextual things around when executing code (capturing output, stdin via front-ends, interruption, etc.)
  */
final class Execute(
  trapOutput: Boolean,
  storage: Storage,
  logCtx: LoggerContext,
  updateBackgroundVariablesEcOpt: Option[ExecutionContext],
  commHandlerOpt: => Option[CommHandler],
  silent: Ref[Boolean]
) {

  private val log = logCtx(getClass)

  private var currentInputManagerOpt0 = Option.empty[InputManager]

  private var interruptedStackTraceOpt0 = Option.empty[Array[StackTraceElement]]
  private var currentThreadOpt0 = Option.empty[Thread]

  private var history0 = new History(Vector())

  private val input0 = new FunctionInputStream(
    UTF_8,
    currentInputManagerOpt0.flatMap { m =>

      val res = {
        implicit val ec = ExecutionContext.global // just using that one to map over an existing future…
        log.info("Awaiting input")
        Await.result(
          m.readInput()
            .map(s => Success(s + System.lineSeparator()))
            .recover { case t => Failure(t) },
          Duration.Inf
        )
      }
      log.info("Received input")

      res match {
        case Success(s) => Some(s)
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
      Capture.create()

  private val updatableResultsOpt0 =
    updateBackgroundVariablesEcOpt.map { ec =>
      new UpdatableResults(
        ec,
        logCtx,
        data => commHandlerOpt.foreach(_.updateDisplay(data)) // throw if commHandlerOpt is empty?
      )
    }

  private val resultVariables = new mutable.HashMap[String, String]
  private val resultOutput = new StringBuilder
  private val resultStream = new FunctionOutputStream(20, 20, UTF_8, resultOutput.append(_)).printStream()

  private var currentLine0 = 0
  private var currentNoHistoryLine0 = Int.MaxValue / 2

  private val printer0 = Printer(
    capture0.out,
    capture0.err,
    resultStream,
    s => currentPublishOpt0.fold(Console.err.println(s))(_.stderr(s)),
    s => currentPublishOpt0.fold(Console.err.println(s))(_.stderr(s)),
    s => currentPublishOpt0.fold(println(s))(_.stdout(s))
  )

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
          case None => throw new Exception("Results updating not available")
          case Some(r) => r.update(k, v, last)
        }
    }

  private def withInputManager[T](m: Option[InputManager])(f: => T): T = {
    val previous = currentInputManagerOpt0
    try {
      currentInputManagerOpt0 = m
      f
    } finally {
      currentInputManagerOpt0 = previous
      m.foreach(_.done())
    }
  }

  private def withClientStdin[T](t: => T): T =
    Console.withIn(input0) {
      val previous = System.in
      try {
        System.setIn(input0)
        t
      } finally {
        System.setIn(previous)
        input0.clear()
      }
    }

  private def withOutputHandler[T](handlerOpt: Option[OutputHandler])(f: => T): T = {
    val previous = currentPublishOpt0
    try {
      currentPublishOpt0 = handlerOpt
      f
    } finally {
      currentPublishOpt0 = previous
    }
  }

  private def capturingOutput[T](t: => T): T =
    currentPublishOpt0 match {
      case None => t
      case Some(p) => capture0(p.stdout, p.stderr)(t)
    }

  private def interruptible[T](t: => T): T = {
    interruptedStackTraceOpt0 = None
    currentThreadOpt0 = Some(Thread.currentThread())
    try {
      Signaller("INT") {
        currentThreadOpt0 match {
          case None =>
            log.warn("Received SIGINT, but no execution is running")
          case Some(t) =>
            interruptedStackTraceOpt0 = Some(t.getStackTrace)
            log.debug(s"Received SIGINT, stopping thread $t\n${interruptedStackTraceOpt0.map("  " + _).mkString("\n")}")
            t.stop()
        }
      }.apply {
        t
      }
    } finally {
      currentThreadOpt0 = None
    }
  }

  def interrupt(): Unit =
    currentThreadOpt0 match {
      case None =>
        log.warn("Interrupt asked, but no execution is running")
      case Some(t) =>
        log.debug(s"Interrupt asked, stopping thread $t\n${t.getStackTrace.map("  " + _).mkString("\n")}")
        t.stop()
    }


  private var lastExceptionOpt0 = Option.empty[Throwable]

  def lastExceptionOpt: Option[Throwable] = lastExceptionOpt0


  private def ammResult(
    ammInterp: ammonite.interp.Interpreter,
    code: String,
    inputManager: Option[InputManager],
    outputHandler: Option[OutputHandler],
    storeHistory: Boolean
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
        ev <- interruptible {
          withInputManager(inputManager) {
            withClientStdin {
              capturingOutput {
                resultOutput.clear()
                resultVariables.clear()
                log.debug(s"Compiling / evaluating $code0 ($stmts)")
                val r = ammInterp.processLine(
                  code0,
                  stmts,
                  if (storeHistory) currentLine0 else currentNoHistoryLine0,
                  silent = silent(),
                  incrementLine =
                    if (storeHistory)
                      () => currentLine0 += 1
                    else
                      () => currentNoHistoryLine0 += 1
                )

                log.debug(s"Handling output of '$code0'")
                Repl.handleOutput(ammInterp, r)
                r match {
                  case Res.Exception(ex, _) =>
                    lastExceptionOpt0 = Some(ex)
                  case _ =>
                }

                val variables = resultVariables.toMap
                val res0 = resultOutput.result()
                log.debug(s"Result of '$code0': $res0")
                resultOutput.clear()
                resultVariables.clear()
                val data =
                  if (variables.isEmpty) {
                    if (res0.isEmpty)
                      DisplayData.empty
                    else
                      DisplayData.text(res0)
                  } else
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
    storeHistory: Boolean
  ): ExecuteResult = {

    if (storeHistory) {
      storage.fullHistory() = storage.fullHistory() :+ code
      history0 = history0 :+ code
    }

    ammResult(ammInterp, code, inputManager, outputHandler, storeHistory) match {
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
              (
                "Interrupted!" +: st
                  .takeWhile(x => !cutoff(x.getMethodName))
                  .map(Execute.highlightFrame(_, fansi.Attr.Reset, colors0().literal()))
                ).mkString(System.lineSeparator())
            )
        }

      case Res.Exception(ex, msg) =>
        log.error(s"exception in user code (${ex.getMessage})", ex)
        Execute.error(colors0(), Some(ex), msg)

      case Res.Skip =>
        ExecuteResult.Success()

      case Res.Exit(_) =>
        ExecuteResult.Exit
    }
  }
}

object Execute {

  // these come from Ammonite
  // exception display was tweaked a bit (too much red for notebooks else)

  private def highlightFrame(f: StackTraceElement,
                     highlightError: fansi.Attrs,
                     source: fansi.Attrs) = {
    val src =
      if (f.isNativeMethod) source("Native Method")
      else if (f.getFileName == null) source("Unknown Source")
      else source(f.getFileName) ++ ":" ++ source(f.getLineNumber.toString)

    val prefix :+ clsName = f.getClassName.split('.').toSeq
    val prefixString = prefix.map(_+'.').mkString("")
    val clsNameString = clsName //.replace("$", error("$"))
    val method =
    fansi.Str(prefixString) ++ highlightError(clsNameString) ++ "." ++
      highlightError(f.getMethodName)

    fansi.Str(s"  ") ++ method ++ "(" ++ src ++ ")"
  }

  def showException(ex: Throwable,
                    error: fansi.Attrs,
                    highlightError: fansi.Attrs,
                    source: fansi.Attrs) = {

    val cutoff = Set("$main", "evaluatorRunPrinter")
    val traces = Ex.unapplySeq(ex).get.map(exception =>
      error(exception.toString).render + System.lineSeparator() +
        exception
          .getStackTrace
          .takeWhile(x => !cutoff(x.getMethodName))
          .map(highlightFrame(_, highlightError, source))
          .mkString(System.lineSeparator())
    )
    traces.mkString(System.lineSeparator())
  }

  private def error(colors: Colors, exOpt: Option[Throwable], msg: String) =
    ExecuteResult.Error(
      msg + exOpt.fold("")(ex => (if (msg.isEmpty) "" else "\n") + showException(
        ex, colors.error(), fansi.Attr.Reset, colors.literal()
      ))
    )

}
