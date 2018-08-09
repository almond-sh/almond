package almond

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

import almond.api.JupyterApi
import almond.internals.{Capture, FunctionInputStream, FunctionOutputStream, UpdatableResults}
import almond.interpreter._
import almond.interpreter.api.{CommHandler, DisplayData, OutputHandler}
import almond.interpreter.comm.CommManager
import almond.interpreter.input.InputManager
import almond.protocol.KernelInfo
import almond.util.OptionalLogger
import ammonite.interp.{Parsers, Preprocessor}
import ammonite.ops.{Path, read}
import ammonite.repl._
import ammonite.runtime._
import ammonite.util._
import ammonite.util.Util.{newLine, normalizeNewlines}
import fastparse.core.Parsed

import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

final class ScalaInterpreter(
  updateBackgroundVariablesEcOpt: Option[ExecutionContext] = None,
  extraRepos: Seq[String] = Nil,
  extraBannerOpt: Option[String] = None,
  extraLinks: Seq[KernelInfo.Link] = Nil,
  predef: String = "",
  automaticDependencies: Map[String, Seq[String]] = Map(),
  codeWrapper: Preprocessor.CodeWrapper = Preprocessor.CodeClassWrapper,
  initialColors: Colors = Colors.Default,
  initialClassLoader: ClassLoader = Thread.currentThread().getContextClassLoader
) extends Interpreter { scalaInterp =>

  private val log = OptionalLogger(getClass)


  private val colors0 = Ref[Colors](initialColors)
  private val history0 = new History(Vector())

  private var currentInputManagerOpt = Option.empty[InputManager]

  private var currentPublishOpt = Option.empty[OutputHandler]

  private val input = new FunctionInputStream(
    UTF_8,
    currentInputManagerOpt.flatMap { m =>

      val res = {
        implicit val ec = ExecutionContext.global // just using that one to map over an existing future…
        log.info("Awaiting input")
        Await.result(
          m.readInput()
            .map(s => Success(s + newLine))
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
  private val capture = new Capture

  private val commManager = new CommManager
  private var commHandlerOpt = Option.empty[CommHandler]

  private val updatableResultsOpt =
    updateBackgroundVariablesEcOpt.map { ec =>
      new UpdatableResults(
        ec,
        data => commHandlerOpt.foreach(_.updateDisplay(data)) // throw if commHandlerOpt is empty?
      )
    }

  private val resultVariables = new mutable.HashMap[String, String]
  private val resultOutput = new StringBuilder
  private val resultStream = new FunctionOutputStream(20, 20, UTF_8, resultOutput.append(_)).printStream()

  private val storage = Storage.InMemory()

  private val frames = Ref(List(Frame.createInitial()))
  private val sess0 = new SessionApiImpl(frames)
  private var currentLine0 = 0

  private val printer0 = Printer(
    capture.out,
    capture.err,
    resultStream,
    s => currentPublishOpt.fold(Console.err.println(s))(_.stderr(s)),
    s => currentPublishOpt.fold(Console.err.println(s))(_.stderr(s)),
    s => currentPublishOpt.fold(println(s))(_.stdout(s))
  )


  private def withInputManager[T](m: Option[InputManager])(f: => T): T = {
    val previous = currentInputManagerOpt
    try {
      currentInputManagerOpt = m
      f
    } finally {
      currentInputManagerOpt = previous
      m.foreach(_.done())
    }
  }

  private def withOutputHandler[T](handlerOpt: Option[OutputHandler])(f: => T): T = {
    val previous = currentPublishOpt
    try {
      currentPublishOpt = handlerOpt
      f
    } finally {
      currentPublishOpt = previous
    }
  }

  private def withClientStdin[T](t: => T): T =
    Console.withIn(input) {
      val previous = System.in
      try {
        System.setIn(input)
        t
      } finally {
        System.setIn(previous)
        input.clear()
      }
    }

  private def capturingOutput[T](t: => T): T =
    currentPublishOpt match {
      case None => t
      case Some(p) => capture(p.stdout, p.stderr)(t)
    }


  lazy val ammInterp: ammonite.interp.Interpreter = {

    val replApi: ReplApiImpl =
      new ReplApiImpl {
        def replArgs0 = Vector.empty[Bind[_]]
        def printer = printer0

        def sess = sess0
        val prompt = Ref("nope")
        val frontEnd = Ref[FrontEnd](null)
        def lastException: Throwable = null
        def fullHistory = storage.fullHistory()
        def history = history0
        val colors = colors0
        def newCompiler() = ammInterp.compilerManager.init(force = true)
        def compiler = ammInterp.compilerManager.compiler.compiler
        def fullImports = ammInterp.predefImports ++ imports
        def imports = ammInterp.frameImports
        def usedEarlierDefinitions = ammInterp.frameUsedEarlierDefinitions
        def width = 80
        def height = 80

        val load: ReplLoad =
          new ReplLoad {
            def apply(line: String) =
              ammInterp.processExec(line, currentLine0, () => currentLine0 += 1) match {
                case Res.Failure(s) => throw new CompilationError(s)
                case Res.Exception(t, _) => throw t
                case _ =>
              }

            def exec(file: Path): Unit = {
              ammInterp.watch(file)
              apply(normalizeNewlines(read(file)))
            }
          }
      }

    val jupyterApi: JupyterApi =
      new JupyterApi {

        def stdin(prompt: String, password: Boolean): Option[String] =
          for (m <- currentInputManagerOpt)
            yield Await.result(m.readInput(prompt, password), Duration.Inf)

        override def changingPublish =
          currentPublishOpt.getOrElse(super.changingPublish)
        override def commHandler =
          commHandlerOpt.getOrElse(super.commHandler)

        def addResultVariable(k: String, v: String): Unit =
          resultVariables += k -> v
        def updateResultVariable(k: String, v: String, last: Boolean): Unit =
          updatableResultsOpt match {
            case None => throw new Exception("Results updating not available")
            case Some(r) => r.update(k, v, last)
          }
      }

    for (ec <- updateBackgroundVariablesEcOpt)
      replApi.pprinter() = {
        val p = replApi.pprinter()

        val additionalHandlers: PartialFunction[Any, pprint.Tree] = {
          case f: scala.concurrent.Future[_] =>
            implicit val ec0 = ec
            val id = "<future-" + java.util.UUID.randomUUID() + ">"
            jupyterApi.Internals.addResultVariable(id, "[running future]")
            f.onComplete { t =>
              jupyterApi.Internals.updateResultVariable(
                id,
                replApi.pprinter().tokenize(t).mkString,
                last = true
              )
            }
            pprint.Tree.Literal(id)
        }

        p.copy(
          additionalHandlers = p.additionalHandlers.orElse(additionalHandlers)
        )
      }

    try {

      log.info("Creating Ammonite interpreter")

      val ammInterp0: ammonite.interp.Interpreter =
        new ammonite.interp.Interpreter(
          printer0,
          storage = storage,
          wd = ammonite.ops.pwd,
          basePredefs = Seq(
            PredefInfo(
              Name("defaultPredef"),
              ScalaInterpreter.predef + ammonite.main.Defaults.replPredef + ammonite.main.Defaults.predefString,
              true,
              None
            )
          ),
          customPredefs = Nil,
          extraBridges = Seq(
            (ammonite.repl.ReplBridge.getClass.getName.stripSuffix("$"), "repl", replApi),
            (almond.api.JupyterAPIHolder.getClass.getName.stripSuffix("$"), "kernel", jupyterApi)
          ),
          colors = Ref(Colors.Default),
          getFrame = () => frames().head,
          createFrame = () => {
            val f = sess0.childFrame(frames().head); frames() = f :: frames(); f
          },
          replCodeWrapper = codeWrapper,
          scriptCodeWrapper = codeWrapper,
          alreadyLoadedDependencies = ammonite.main.Defaults.alreadyLoadedDependencies("almond/almond-user-dependencies.txt")
        )

      log.info("Initializing interpreter predef")

      ammInterp0.initializePredef()

      log.info("Loading base dependencies")

      ammInterp0.repositories() = ammInterp0.repositories() ++ extraRepos.map { repo =>
        coursier.MavenRepository(repo)
      }

      log.info("Initializing Ammonite interpreter")

      ammInterp0.compilerManager.init()

      log.info("Processing scalac args")

      ammInterp0.compilerManager.preConfigureCompiler(_.processArguments(Nil, processAll = true))

      log.info(s"Warming up interpreter (predef: $predef)")

      val code =
        if (predef.isEmpty)
          // from ammonite.repl.Repl.warmup()
          """val array = Seq.tabulate(10)(_*2).toArray.max"""
        else
          predef

      val stmts = Parsers.split(code).get.get.value
      ammInterp0.processLine(code, stmts, -1, silent = true, () => ())

      log.info("Ammonite interpreter ok")

      ammInterp0
    } catch {
      case t: Throwable =>
        log.error(s"Caught exception while initializing interpreter, exiting", t)
        sys.exit(1)
    }
  }

  // Actually init interpreter in background

  private val initThread = new Thread("interpreter-init") {
    setDaemon(true)
    override def run() =
      try {
        log.info("Initializing interpreter (background)")
        ammInterp
        log.info("Initialized interpreter (background)")
      }
      catch {
        case t: Throwable =>
          log.error(s"Caught exception while initializing interpreter", t)
      }
  }

  initThread.start()

  override def init(): Unit = {
    // wait for ammInterp to be initialized
    ammInterp
  }


  override def commManagerOpt: Some[CommManager] =
    Some(commManager)
  override def setCommHandler(commHandler0: CommHandler): Unit =
    commHandlerOpt = Some(commHandler0)

  def execute(
    line: String,
    outputHandler: Option[OutputHandler],
    inputManager: Option[InputManager],
    storeHistory: Boolean, // FIXME Take that one into account
    currentMessageOpt: Option[Message[_]]
  ): ExecuteResult = {

    val hackedLine =
      if (line.contains("$ivy.`"))
        automaticDependencies.foldLeft(line) {
          case (line0, (triggerDep, autoDeps)) =>
            if (line0.contains(triggerDep)) {
              log.info(s"Adding auto dependencies $autoDeps")
              autoDeps.map(dep => s"import $$ivy.`$dep`; ").mkString + line0
            } else
              line0
        }
      else
        line

    val ammInterp0 = ammInterp // ensures we don't capture output / catch signals during interp initialization

    var interruptedStackTraceOpt = Option.empty[Array[StackTraceElement]]

    val ammResult =
      withOutputHandler(outputHandler) {
        for {
          (code, stmts) <- Parsers.Splitter.parse(hackedLine) match {
            case Parsed.Success(value, _) =>
              Res.Success((hackedLine, value))
            case Parsed.Failure(_, index, extra) => Res.Failure(
              fastparse.core.ParseError.msg(extra.input, extra.traced.expected, index)
            )
          }
          _ = log.info(s"splitted $hackedLine")
          currentThread = Thread.currentThread()
          _ <- Signaller("INT") {
            interruptedStackTraceOpt = Some(currentThread.getStackTrace)
            currentThread.stop()
          }
          ev <- withInputManager(inputManager) {
            withClientStdin {
              capturingOutput {
                resultOutput.clear()
                resultVariables.clear()
                log.info(s"Compiling / evaluating $code ($stmts)")
                val r = ammInterp0.processLine(code, stmts, currentLine0, silent = false, incrementLine = () => currentLine0 += 1)
                log.info(s"Handling output of $hackedLine")
                Repl.handleOutput(ammInterp0, r)
                val variables = resultVariables.toMap
                val res0 = resultOutput.result()
                log.info(s"Result of $hackedLine: $res0")
                resultOutput.clear()
                resultVariables.clear()
                val data =
                  if (variables.isEmpty) {
                    if (res0.isEmpty)
                      DisplayData.empty
                    else
                      DisplayData.text(res0)
                  } else
                    updatableResultsOpt match {
                      case None =>
                        DisplayData.text(res0)
                      case Some(r) =>
                        r.add(
                          DisplayData.text(res0).withId(UUID.randomUUID().toString),
                          variables
                        )
                    }
                r.map((_, data))
              }
            }
          }
        } yield ev
      }

    ammResult match {
      case Res.Success((_, data)) =>
        ExecuteResult.Success(data)
      case Res.Failure(msg) =>
        interruptedStackTraceOpt match {
          case None =>
            val err = ScalaInterpreter.error(colors0(), None, msg)
            outputHandler.foreach(_.stderr(err.message)) // necessary?
            err
          case Some(st) =>

            val cutoff = Set("$main", "evaluatorRunPrinter")

            ExecuteResult.Error(
              (
                "Interrupted!" +: st
                  .takeWhile(x => !cutoff(x.getMethodName))
                  .map(ScalaInterpreter.highlightFrame(_, fansi.Attr.Reset, colors0().literal()))
              ).mkString(newLine)
            )
        }

      case Res.Exception(ex, msg) =>
        log.error(s"exception in user code (${ex.getMessage})", ex)
        ScalaInterpreter.error(colors0(), Some(ex), msg)

      case Res.Skip =>
        ExecuteResult.Success()

      case Res.Exit(_) =>
        ???
    }
  }

  def currentLine(): Int =
    currentLine0

  override def isComplete(code: String): Option[IsCompleteResult] = {

    val res = Parsers.Splitter.parse(code) match {
      case Parsed.Success(_, _) =>
        IsCompleteResult.Complete
      case Parsed.Failure(_, index, _) if code.drop(index).trim() == "" =>
        IsCompleteResult.Incomplete
      case Parsed.Failure(_, _, _) =>
        IsCompleteResult.Invalid
    }

    Some(res)
  }

  override def complete(code: String, pos: Int): Completion = {

    val (newPos, completions0, other) = ammInterp.compilerManager.complete(
      pos,
      frames().head.imports.toString(),
      code
    )

    println(other.mkString("\n") + "\n")

    val completions = completions0
      .filter(!_.contains("$"))
      .filter(_.nonEmpty)

    Completion(
      if (completions.isEmpty) pos else newPos,
      pos,
      completions.map(_.trim).distinct
    )
  }

  override def inspect(code: String, pos: Int, detailLevel: Int): Option[Inspection] = {
    // TODO Use ammonite.repl.tools.source.load to return some details here
    None
  }

  def kernelInfo() =
    KernelInfo(
      "scala",
      almond.api.Properties.version,
      KernelInfo.LanguageInfo(
        "scala",
        scala.util.Properties.versionNumberString,
        "text/x-scala",
        ".scala",
        "script",
        codemirror_mode = Some("text/x-scala")
      ),
      s"""Almond ${almond.api.Properties.version}
         |Ammonite ${ammonite.Constants.version}
         |${scala.util.Properties.versionMsg}
         |Java ${sys.props.getOrElse("java.version", "[unknown]")}
       """.stripMargin + extraBannerOpt.fold("")("\n\n" + _),
      help_links = Some(extraLinks.toList).filter(_.nonEmpty)
    )

}

object ScalaInterpreter {

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

  private def showException(ex: Throwable,
                    error: fansi.Attrs,
                    highlightError: fansi.Attrs,
                    source: fansi.Attrs) = {

    val cutoff = Set("$main", "evaluatorRunPrinter")
    val traces = Ex.unapplySeq(ex).get.map(exception =>
      error(exception.toString) + newLine +
        exception
          .getStackTrace
          .takeWhile(x => !cutoff(x.getMethodName))
          .map(highlightFrame(_, highlightError, source))
          .mkString(newLine)
    )
    traces.mkString(newLine)
  }

  private def predef =
    """import almond.api.JupyterAPIHolder.value.{
      |  publish,
      |  commHandler
      |}
      |import almond.api.JupyterAPIHolder.value.publish.display
      |import almond.interpreter.api.DisplayData.DisplayDataSyntax
      |import almond.api.helpers.Display.{html, js, text}
    """.stripMargin

  private def error(colors: Colors, exOpt: Option[Throwable], msg: String) =
    ExecuteResult.Error(
      msg + exOpt.fold("")(ex => (if (msg.isEmpty) "" else "\n") + showException(
        ex, colors.error(), fansi.Attr.Reset, colors.literal()
      ))
    )

}
