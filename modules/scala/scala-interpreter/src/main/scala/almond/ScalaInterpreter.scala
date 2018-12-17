package almond

import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.UUID

import almond.api.JupyterApi
import almond.api.helpers.Display
import almond.channels.ConnectionParameters
import almond.internals._
import almond.interpreter._
import almond.interpreter.api.{CommHandler, DisplayData, OutputHandler}
import almond.interpreter.input.InputManager
import almond.interpreter.util.CancellableFuture
import almond.logger.{Logger, LoggerContext}
import almond.protocol.KernelInfo
import ammonite.interp.{Parsers, Preprocessor}
import ammonite.ops.read
import ammonite.repl._
import ammonite.runtime._
import ammonite.util._
import ammonite.util.Util.{newLine, normalizeNewlines}
import fastparse.Parsed
import jupyter.{Displayer, Displayers}
import metabrowse.server.{MetabrowseServer, Sourcepath}
import pprint.{TPrint, TPrintColors}

import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag
import scala.tools.nsc.Global
import scala.util.{Failure, Random, Success}

final class ScalaInterpreter(
  updateBackgroundVariablesEcOpt: Option[ExecutionContext] = None,
  extraRepos: Seq[String] = Nil,
  extraBannerOpt: Option[String] = None,
  extraLinks: Seq[KernelInfo.Link] = Nil,
  predefCode: String = "",
  predefFiles: Seq[Path] = Nil,
  automaticDependencies: Map[String, Seq[String]] = Map(),
  forceMavenProperties: Map[String, String] = Map(),
  mavenProfiles: Map[String, Boolean] = Map(),
  codeWrapper: Preprocessor.CodeWrapper = Preprocessor.CodeClassWrapper,
  initialColors: Colors = Colors.Default,
  initialClassLoader: ClassLoader = Thread.currentThread().getContextClassLoader,
  logCtx: LoggerContext = LoggerContext.nop,
  metabrowse: Boolean = false,
  metabrowseHost: String = "localhost",
  metabrowsePort: Int = -1,
  lazyInit: Boolean = false,
  trapOutput: Boolean = false
) extends Interpreter { scalaInterp =>

  private val log = logCtx(getClass)

  @volatile private var metabrowseServerOpt0 = Option.empty[(MetabrowseServer, Int, String)]
  private val metabrowseServerCreateLock = new Object

  private def metabrowseServerOpt() =
    if (metabrowse)
      metabrowseServerOpt0.orElse {
        metabrowseServerCreateLock.synchronized {
          metabrowseServerOpt0.orElse {
            metabrowseServerOpt0 = Some(createMetabrowseServer())
            metabrowseServerOpt0
          }
        }
      }
    else
      None

  private def createMetabrowseServer() = {

    if (metabrowse && !sys.props.contains("org.jboss.logging.provider") && !sys.props.get("almond.adjust.jboss.logging.provider").contains("0")) {
      log.info("Setting Java property org.jboss.logging.provider to slf4j")
      sys.props("org.jboss.logging.provider") = "slf4j"
    }

    val port =
      if (metabrowsePort > 0)
        metabrowsePort
      else
        ConnectionParameters.randomPort()

    val server = new MetabrowseServer(
      host = metabrowseHost,
      port = port
      // FIXME Pass custom logger?
    )

    val windowName = {
      val id = math.abs(Random.nextInt().toLong)
      s"almond-metabrowse-$id"
    }

    val baseSourcepath = ScalaInterpreter.baseSourcePath(
      frames()
        .last
        .classloader
        .getParent,
      log
    )

    val sourcePath = {

      import ScalaInterpreter.SourcepathOps

      val sessionJars = frames()
        .flatMap(_.classpath)
        .map(_.toPath)

      val (sources, other) = sessionJars
        .partition(_.getFileName.toString.endsWith("-sources.jar"))

      Sourcepath(other, sources) :: baseSourcepath
    }

    log.info(s"Starting metabrowse server at http://$metabrowseHost:$port")
    log.info(
      "Initial source path\n  Classpath\n" +
        sourcePath.classpath.map("    " + _).mkString("\n") +
        "\n  Sources\n" +
        sourcePath.sources.map("    " + _).mkString("\n")
    )
    server.start(sourcePath)

    (server, port, windowName)
  }


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

  private val capture =
    if (trapOutput)
      Capture.nop()
    else
      Capture.create()

  private var commHandlerOpt = Option.empty[CommHandler]

  private val updatableResultsOpt =
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

  private val storage = Storage.InMemory()

  private val frames = Ref(List(Frame.createInitial(initialClassLoader)))
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
      new ReplApiImpl { self =>
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
        def interactiveCompiler = ammInterp.compilerManager.pressy.compiler
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

            def exec(file: ammonite.ops.Path): Unit = {
              ammInterp.watch(file)
              apply(normalizeNewlines(read(file)))
            }
          }

        override protected[this] def internal0: FullReplAPI.Internal =
          new FullReplAPI.Internal {
            def pprinter = self.pprinter
            def colors = self.colors
            def replArgs: IndexedSeq[Bind[_]] = replArgs0

            val defaultDisplayer = Displayers.registration().find(classOf[ScalaInterpreter.Foo])

            override def print[T](
              value: => T,
              ident: String,
              custom: Option[String]
            )(implicit tprint: TPrint[T], tcolors: TPrintColors, classTagT: ClassTag[T]): Iterator[String] = {

              val displayerPublishOpt =
                if (classTagT == null)
                  None
                else
                  currentPublishOpt.flatMap { p =>
                    Some(Displayers.registration().find(classTagT.runtimeClass))
                      .filter(_ ne defaultDisplayer)
                      .map(d => (d.asInstanceOf[Displayer[T]], p))
                  }

              displayerPublishOpt match {
                case None =>
                  super.print(value, ident, custom)(tprint, tcolors, classTagT)
                case Some((displayer, publish)) =>
                  import scala.collection.JavaConverters._
                  val m = displayer.display(value)
                  val data = DisplayData(m.asScala.toMap)
                  publish.display(data)
                  Iterator()
              }
            }
          }
      }

    val jupyterApi: JupyterApi =
      new JupyterApi {

        def stdinOpt(prompt: String, password: Boolean): Option[String] =
          for (m <- currentInputManagerOpt)
            yield Await.result(m.readInput(prompt, password), Duration.Inf)

        override def changingPublish =
          currentPublishOpt.getOrElse(super.changingPublish)
        override def commHandler =
          commHandlerOpt.getOrElse(super.commHandler)

        protected def updatableResults0: JupyterApi.UpdatableResults =
          new JupyterApi.UpdatableResults {
            override def addVariable(k: String, v: String) =
              resultVariables += k -> v
            override def updateVariable(k: String, v: String, last: Boolean) =
              updatableResultsOpt match {
                case None => throw new Exception("Results updating not available")
                case Some(r) => r.update(k, v, last)
              }
          }
      }

    for (ec <- updateBackgroundVariablesEcOpt)
      UpdatableFuture.setup(replApi, jupyterApi, ec)

    val predefFileInfos =
      predefFiles.zipWithIndex.map {
        case (path, idx) =>
          val suffix = if (idx <= 0) "" else s"-$idx"
          PredefInfo(
            Name("FilePredef" + suffix),
            // read with the local charset…
            new String(Files.readAllBytes(path)),
            false,
            Some(os.Path(path))
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
          customPredefs = predefFileInfos ++ Seq(
            PredefInfo(Name("CodePredef"), predefCode, false, None)
          ),
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

      for ((e, _) <- ammInterp0.initializePredef())
        e match {
          case Res.Failure(msg) =>
            throw new ScalaInterpreter.PredefException(msg, None)
          case Res.Exception(t, msg) =>
            throw new ScalaInterpreter.PredefException(msg, Some(t))
          case Res.Skip =>
          case Res.Exit(v) =>
            log.warn(s"Ignoring exit request from predef (exit value: $v)")
        }

      log.info("Loading base dependencies")

      ammInterp0.repositories() = ammInterp0.repositories() ++ extraRepos.map { repo =>
        coursier.MavenRepository(repo)
      }

      log.info("Initializing Ammonite interpreter")

      ammInterp0.compilerManager.init()

      log.info("Processing scalac args")

      ammInterp0.compilerManager.preConfigureCompiler(_.processArguments(Nil, processAll = true))

      log.info("Ammonite interpreter ok")

      if (forceMavenProperties.nonEmpty)
        ammInterp0.resolutionHooks += { res =>
          res.copy(
            forceProperties = res.forceProperties ++ forceMavenProperties
          )
        }

      if (mavenProfiles.nonEmpty)
        ammInterp0.resolutionHooks += { res =>
          res.copy(
            userActivations = Some(res.userActivations.getOrElse(Map.empty[String, Boolean]) ++ mavenProfiles)
          )
        }

      ammInterp0
    } catch {
      case t: Throwable =>
        log.error(s"Caught exception while initializing interpreter", t)
        throw t
    }
  }

  if (!lazyInit)
    // eagerly initialize ammInterp
    ammInterp

  private var interruptedStackTraceOpt = Option.empty[Array[StackTraceElement]]
  private var currentThreadOpt = Option.empty[Thread]

  override def interruptSupported: Boolean =
    true
  override def interrupt(): Unit = {
    currentThreadOpt match {
      case None =>
        log.warn("Interrupt asked, but no execution is running")
      case Some(t) =>
        log.debug(s"Interrupt asked, stopping thread $t")
        t.stop()
    }
  }

  private def interruptible[T](t: => T): T = {
    interruptedStackTraceOpt = None
    currentThreadOpt = Some(Thread.currentThread())
    try {
      Signaller("INT") {
        currentThreadOpt match {
          case None =>
            log.warn("Received SIGINT, but no execution is running")
          case Some(t) =>
            log.debug(s"Received SIGINT, stopping thread $t")
            interruptedStackTraceOpt = Some(t.getStackTrace)
            t.stop()
        }
      }.apply {
        t
      }
    } finally {
      currentThreadOpt = None
    }
  }


  override def supportComm: Boolean = true
  override def setCommHandler(commHandler0: CommHandler): Unit =
    commHandlerOpt = Some(commHandler0)

  def execute(
    code: String,
    storeHistory: Boolean, // FIXME Take that one into account
    inputManager: Option[InputManager],
    outputHandler: Option[OutputHandler]
  ): ExecuteResult = {

    val hackedLine =
      if (code.contains("$ivy.`"))
        automaticDependencies.foldLeft(code) {
          case (line0, (triggerDep, autoDeps)) =>
            if (line0.contains(triggerDep)) {
              log.info(s"Adding auto dependencies $autoDeps")
              autoDeps.map(dep => s"import $$ivy.`$dep`; ").mkString + line0
            } else
              line0
        }
      else
        code

    val ammInterp0 = ammInterp // ensures we don't capture output / catch signals during interp initialization

    val ammResult =
      withOutputHandler(outputHandler) {
        for {
          (code, stmts) <- fastparse.parse(hackedLine, Parsers.Splitter(_)) match {
            case Parsed.Success(value, _) =>
              Res.Success((hackedLine, value))
            case f: Parsed.Failure => Res.Failure(
              Preprocessor.formatFastparseError("(console)", code, f)
            )
          }
          _ = log.info(s"splitted $hackedLine")
          ev <- interruptible {
            withInputManager(inputManager) {
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
                          val d = r.add(
                            DisplayData.text(res0).withId(Display.newId()),
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
        ExecuteResult.Exit
    }
  }

  def currentLine(): Int =
    currentLine0

  override def isComplete(code: String): Option[IsCompleteResult] = {

    val res = fastparse.parse(code, Parsers.Splitter(_)) match {
      case Parsed.Success(_, _) =>
        IsCompleteResult.Complete
      case Parsed.Failure(_, index, _) if code.drop(index).trim() == "" =>
        IsCompleteResult.Incomplete
      case Parsed.Failure(_, _, _) =>
        IsCompleteResult.Invalid
    }

    Some(res)
  }

  // As most "cancelled" calculations (completions, inspections, …) are run in other threads by the presentation
  // compiler, they aren't actually cancelled, they'll keep running in the background. This just interrupts
  // the thread that waits for the background calculation.
  // Having a thread that blocks for results, in turn, is almost required by scala.tools.nsc.interactive.Response…
  private val cancellableFuturePool = new CancellableFuturePool(logCtx)

  override def asyncIsComplete(code: String): Some[CancellableFuture[Option[IsCompleteResult]]] =
    Some(cancellableFuturePool.cancellableFuture(isComplete(code)))
  override def asyncComplete(code: String, pos: Int): Some[CancellableFuture[Completion]] =
    Some(cancellableFuturePool.cancellableFuture(complete(code, pos)))
  override def asyncInspect(code: String, pos: Int, detailLevel: Int): Some[CancellableFuture[Option[Inspection]]] =
    Some(cancellableFuturePool.cancellableFuture(inspect(code, pos)))

  override def complete(code: String, pos: Int): Completion = {

    val (newPos, completions0, _) = ammInterp.compilerManager.complete(
      pos,
      frames().head.imports.toString(),
      code
    )

    val completions = completions0
      .filter(!_.contains("$"))
      .filter(_.nonEmpty)

    Completion(
      if (completions.isEmpty) pos else newPos,
      pos,
      completions.map(_.trim).distinct
    )
  }

  override def inspect(code: String, pos: Int, detailLevel: Int): Option[Inspection] =
    metabrowseServerOpt().flatMap {
      case (metabrowseServer, metabrowsePort0, metabrowseWindowId) =>
        val pressy = ammInterp.compilerManager.pressy.compiler

        val prefix = frames().head.imports.toString() + newLine + "object InspectWrapper{" + newLine
        val suffix = newLine + "}"
        val allCode = prefix + code + suffix
        val index = prefix.length + pos

        val currentFile = new scala.reflect.internal.util.BatchSourceFile(
          ammonite.interp.Compiler.makeFile(allCode.getBytes, name = "Current.sc"),
          allCode
        )

        val r = new scala.tools.nsc.interactive.Response[Unit]
        pressy.askReload(List(currentFile), r)
        r.get.swap match {
          case Left(e) =>
            log.warn(s"Error loading '${code.take(pos)}|${code.drop(pos)}' into presentation compiler", e)
            None
          case Right(()) =>
            val r0 = new scala.tools.nsc.interactive.Response[pressy.Tree]
            pressy.askTypeAt(new scala.reflect.internal.util.OffsetPosition(currentFile, index), r0)
            r0.get.swap match {
              case Left(e) =>
                log.debug(s"Getting type info for '${code.take(pos)}|${code.drop(pos)}' via presentation compiler", e)
                None
              case Right(tree) =>

                val r0 = pressy.askForResponse(() => metabrowseServer.urlForSymbol(pressy)(tree.symbol))
                r0.get.swap match {
                  case Left(e) =>
                    log.warn(s"Error loading '${code.take(pos)}|${code.drop(pos)}' into presentation compiler", e)
                    None
                  case Right(relUrlOpt) =>
                    log.debug(s"url of $tree: $relUrlOpt")
                    val urlOpt = relUrlOpt.map(relUrl => s"http://$metabrowseHost:$metabrowsePort0/$relUrl")

                    val typeStr = ScalaInterpreter.typeOfTree(pressy)(tree).getOrElse(tree.toString)

                    import scalatags.Text.all._

                    val typeHtml0 = pre(typeStr)
                    val typeHtml: Frag = urlOpt.fold(typeHtml0) { url =>
                      a(href := url, target := metabrowseWindowId, typeHtml0)
                    }

                    val res = Inspection.fromDisplayData(
                      DisplayData.html(typeHtml.toString)
                    )

                    Some(res)
                }
            }
        }
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
         |Java ${sys.props.getOrElse("java.version", "[unknown]")}""".stripMargin +
        extraBannerOpt.fold("")("\n\n" + _),
      help_links = Some(extraLinks.toList).filter(_.nonEmpty)
    )

  override def shutdown(): Unit =
    for ((metabrowseServer, _, _) <- metabrowseServerOpt0) {
      log.info("Stopping metabrowse server")
      metabrowseServer.stop()
    }

}

object ScalaInterpreter {

  final class PredefException(
    msg: String,
    causeOpt: Option[Throwable]
  ) extends Exception(msg, causeOpt.orNull) {
    def describe: String =
      if (causeOpt.isEmpty)
        s"Error while running predef: $msg"
      else
        s"Caught exception while running predef: $msg"
  }

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
      |import almond.api.helpers.Display.{html, js, latex, markdown, text, svg, Image}
    """.stripMargin

  private def error(colors: Colors, exOpt: Option[Throwable], msg: String) =
    ExecuteResult.Error(
      msg + exOpt.fold("")(ex => (if (msg.isEmpty) "" else "\n") + showException(
        ex, colors.error(), fansi.Attr.Reset, colors.literal()
      ))
    )

  private def baseSourcePath(loader: ClassLoader, log: Logger): Sourcepath = {

    lazy val javaDirs = {
      val l = Seq(sys.props("java.home")) ++
        sys.props.get("java.ext.dirs").toSeq.flatMap(_.split(File.pathSeparator)).filter(_.nonEmpty) ++
        sys.props.get("java.endorsed.dirs").toSeq.flatMap(_.split(File.pathSeparator)).filter(_.nonEmpty)
      l.map(_.stripSuffix("/") + "/")
    }

    def isJdkJar(uri: URI): Boolean =
      uri.getScheme == "file" && {
        val path = new File(uri).getAbsolutePath
        javaDirs.exists(path.startsWith)
      }

    def classpath(cl: ClassLoader): Stream[java.net.URL] = {
      if (cl == null)
        Stream()
      else {
        val cp = cl match {
          case u: java.net.URLClassLoader => u.getURLs.toStream
          case _ => Stream()
        }

        cp #::: classpath(cl.getParent)
      }
    }

    val baseJars = classpath(loader)
      .map(_.toURI)
      // assuming the JDK on the YARN machines already have those
      .filter(u => !isJdkJar(u))
      .map(Paths.get)
      .toList

    log.info(
      "Found base JARs:\n" +
        baseJars.sortBy(_.toString).map("  " + _).mkString("\n") +
        "\n"
    )

    val (baseSources, baseOther) = baseJars
      .partition(_.getFileName.toString.endsWith("-sources.jar"))

    Sourcepath(baseOther, baseSources)
  }

  private implicit class SourcepathOps(private val p: Sourcepath) extends AnyVal {
    def ::(other: Sourcepath): Sourcepath =
      Sourcepath(other.classpath ::: p.classpath, other.sources ::: p.sources)
  }

  // from https://github.com/scalameta/metals/blob/cec8b98cba23110d5b2919d9879c78d3b0146ab2/metaserver/src/main/scala/scala/meta/languageserver/providers/HoverProvider.scala#L34-L51
  // (via https://github.com/almond-sh/almond/pull/235#discussion_r222696661)
  private def typeOfTree(c: Global)(t: c.Tree): Option[String] = {
    import c._

    val stringOrTree = t match {
      case t: DefDef => Right(t.symbol.asMethod.info.toLongString)
      case t: ValDef if t.tpt != null => Left(t.tpt)
      case t: ValDef if t.rhs != null => Left(t.rhs)
      case x => Left(x)
    }

    stringOrTree match {
      case Right(string) => Some(string)
      case Left(null) => None
      case Left(tree) if tree.tpe ne NoType => Some(tree.tpe.widen.toString)
      case _ => None
    }

  }

  private class Foo
}
