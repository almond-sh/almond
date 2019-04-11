package almond

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import almond.api.{FullJupyterApi, JupyterApi}
import almond.internals._
import almond.interpreter._
import almond.interpreter.api.{CommHandler, DisplayData, OutputHandler}
import almond.interpreter.input.InputManager
import almond.interpreter.util.CancellableFuture
import almond.logger.LoggerContext
import almond.protocol.KernelInfo
import ammonite.interp.{CodeClassWrapper, CodeWrapper, CompilerLifecycleManager, DefaultPreprocessor, Parsers, Preprocessor}
import ammonite.ops.read
import ammonite.repl._
import ammonite.runtime._
import ammonite.util._
import ammonite.util.Util.normalizeNewlines
import coursier.almond.tmp.Tmp
import fastparse.Parsed
import io.github.soc.directories.ProjectDirectories
import jupyter.{Displayer, Displayers}
import pprint.{TPrint, TPrintColors}

import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag

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
  codeWrapper: CodeWrapper = CodeClassWrapper,
  initialColors: Colors = Colors.Default,
  initialClassLoader: ClassLoader = Thread.currentThread().getContextClassLoader,
  val logCtx: LoggerContext = LoggerContext.nop,
  val metabrowse: Boolean = false,
  val metabrowseHost: String = "localhost",
  val metabrowsePort: Int = -1,
  lazyInit: Boolean = false,
  trapOutput: Boolean = false,
  disableCache: Boolean = false
) extends Interpreter with ScalaInterpreterInspections { scalaInterp =>

  private val log = logCtx(getClass)

  def pressy = ammInterp.compilerManager.pressy.compiler

  private val colors0 = Ref[Colors](initialColors)
  private val history0 = new History(Vector())

  private var commHandlerOpt = Option.empty[CommHandler]

  private val execute0 = new Execute(trapOutput, logCtx, updateBackgroundVariablesEcOpt, commHandlerOpt)

  private val storage =
    if (disableCache)
      Storage.InMemory()
    else
      new Storage.Folder(os.Path(ProjectDirectories.from(null, null, "Almond").cacheDir) / "ammonite")

  private val frames0 = Ref(List(Frame.createInitial(initialClassLoader)))
  private val sess0 = new SessionApiImpl(frames0)

  def frames(): List[Frame] = frames0()


  lazy val ammInterp: ammonite.interp.Interpreter = {

    val defaultDisplayer = Displayers.registration().find(classOf[ScalaInterpreter.Foo])

    def printSpecial[T](
              value: => T,
              ident: String,
              custom: Option[String],
              onChange: Option[(T => Unit) => Unit],
              pprinter: Ref[pprint.PPrinter],
              updatableResultsOpt: Option[JupyterApi.UpdatableResults]
            )(implicit tprint: TPrint[T], tcolors: TPrintColors, classTagT: ClassTag[T]): Option[Iterator[String]] = {

      execute0.currentPublishOpt match {
                case None =>
                  None
                case Some(p) =>

                  val isUpdatableDisplay =
                    classTagT != null &&
                      classOf[almond.display.Display]
                        .isAssignableFrom(classTagT.runtimeClass)

                  val jvmReprDisplayer: Displayer[_] =
                    Displayers.registration().find(classTagT.runtimeClass)
                  val useJvmReprDisplay =
                    jvmReprDisplayer ne defaultDisplayer

                  if (isUpdatableDisplay) {
                    val d = value.asInstanceOf[almond.display.Display]
                    d.display()(p)
                    Some(Iterator())
                  } else if (useJvmReprDisplay) {
                    import scala.collection.JavaConverters._
                    val m = jvmReprDisplayer
                      .asInstanceOf[Displayer[T]]
                      .display(value)
                      .asScala
                      .toMap
                    p.display(DisplayData(m))
                    Some(Iterator())
                  } else
                    for (updatableResults <- updatableResultsOpt; onChange0 <- onChange) yield {

                      // Pre-compute how many lines and how many columns the prefix of the
                      // printed output takes, so we can feed that information into the
                      // pretty-printing of the main body
                      val prefix = new pprint.Truncated(
                        Iterator(
                          colors0().ident()(ident).render, ": ",
                          implicitly[pprint.TPrint[T]].render(tcolors), " = "
                        ),
                        pprinter().defaultWidth,
                        pprinter().defaultHeight
                      )
                      val output = mutable.Buffer.empty[fansi.Str]

                      prefix.foreach(output.+=)

                      val rhs = custom match {
                        case None =>

                          var currentValue = value

                          val id = updatableResults.updatable {
                            pprinter().tokenize(
                              currentValue,
                              height = pprinter().defaultHeight - prefix.completedLineCount,
                              initialOffset = prefix.lastLineLength
                            ).map(_.render).mkString
                          }

                          onChange0 { value0 =>
                            if (value0 != currentValue) {
                              val s = pprinter().tokenize(
                                value0,
                                height = pprinter().defaultHeight - prefix.completedLineCount,
                                initialOffset = prefix.lastLineLength
                              )
                              updatableResults.update(id, s.map(_.render).mkString, last = false)
                              currentValue = value0
                            }
                          }

                          id

                        case Some(s) =>
                          pprinter().colorLiteral(s).render
                      }

                      output.iterator.map(_.render) ++ Iterator(rhs)
                    }
              }
            }

    val replApi: ReplApiImpl =
      new ReplApiImpl { self =>
        def replArgs0 = Vector.empty[Bind[_]]
        def printer = execute0.printer

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
              ammInterp.processExec(line, execute0.currentLine, () => execute0.incrementLineCount()) match {
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
            )(implicit tprint: TPrint[T], tcolors: TPrintColors, classTagT: ClassTag[T]): Iterator[String] =
              printSpecial(value, ident, custom, None, pprinter, None)(tprint, tcolors, classTagT).getOrElse {
                super.print(value, ident, custom)(tprint, tcolors, classTagT)
              }
          }
      }

    val jupyterApi: FullJupyterApi =
      new FullJupyterApi {

        protected def printOnChange[T](
          value: => T,
          ident: String,
          custom: Option[String],
          onChange: Option[(T => Unit) => Unit]
        )(implicit
          tprint: TPrint[T],
          tcolors: TPrintColors,
          classTagT: ClassTag[T]
        ): Iterator[String] =
          printSpecial(value, ident, custom, onChange, replApi.pprinter, Some(updatableResults))(tprint, tcolors, classTagT).getOrElse {
            replApi.Internal.print(value, ident, custom)(tprint, tcolors, classTagT)
          }

        protected def ansiTextToHtml(text: String): String = {
          val baos = new ByteArrayOutputStream
          val haos = new HtmlAnsiOutputStream(baos)
          haos.write(text.getBytes(StandardCharsets.UTF_8))
          haos.close()
          baos.toString("UTF-8")
        }

        def stdinOpt(prompt: String, password: Boolean): Option[String] =
          for (m <- execute0.currentInputManagerOpt)
            yield Await.result(m.readInput(prompt, password), Duration.Inf)

        override def changingPublish =
          execute0.currentPublishOpt.getOrElse(super.changingPublish)
        override def commHandler =
          commHandlerOpt.getOrElse(super.commHandler)

        protected def updatableResults0: JupyterApi.UpdatableResults =
          execute0.updatableResults
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
          execute0.printer,
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
          getFrame = () => frames0().head,
          createFrame = () => {
            val f = sess0.childFrame(frames0().head); frames0() = f :: frames0(); f
          },
          replCodeWrapper = codeWrapper,
          scriptCodeWrapper = codeWrapper,
          alreadyLoadedDependencies = ammonite.main.Defaults.alreadyLoadedDependencies("almond/almond-user-dependencies.txt")
        ) {
          override val compilerManager =
            new CompilerLifecycleManager(storage, headFrame) {
              import scala.reflect.internal.Flags
              import scala.tools.nsc.{Global => G}
              def customPprintSignature(ident: String, customMsg: Option[String], modOpt: Option[String]) = {
                val customCode = customMsg.fold("_root_.scala.None")(x => s"""_root_.scala.Some("$x")""")
                val modOptCode = modOpt.fold("_root_.scala.None")(x => s"""_root_.scala.Some($x)""")
                s"""
    _root_.almond
          .api
          .JupyterAPIHolder
          .value
          .Internal
          .printOnChange($ident, ${fastparse.internal.Util.literalize(ident)}, $customCode, $modOptCode)
    """
              }
              override def preprocess(fileName: String): Preprocessor =
                synchronized {
                  if (compiler == null) init(force = true)
                  new DefaultPreprocessor(compiler.parse(fileName, _)) {
                    val CustomPatVarDef = Processor {
                      case (_, code, t: G#ValDef)
                        if !DefaultPreprocessor.isPrivate(t) &&
                          !t.name.decoded.contains("$") &&
                          !t.mods.hasFlag(Flags.LAZY) =>
                        val (code0, modOpt) = fastparse.parse(code, Parsers.PatVarSplitter(_)) match {
                          case Parsed.Success((lhs, rhs), _) if lhs.startsWith("var ") =>
                            val mod = Name.backtickWrap(t.name.decoded + "$value")
                            val c = s"""val $mod = new _root_.almond.api.internal.Modifiable($rhs)
                                       |import $mod.{value => ${t.name.encoded}}""".stripMargin
                            (c, Some(mod + ".onChange"))
                          case _ =>
                            (code, None)
                        }
                        DefaultPreprocessor.Expanded(
                          code0,
                          Seq(customPprintSignature(Name.backtickWrap(t.name.decoded), None, modOpt))
                        )
                    }
                    override val decls = Seq[(String, String, G#Tree) => Option[DefaultPreprocessor.Expanded]](
                      CustomPatVarDef,
                      // same as super.decls
                      ObjectDef, ClassDef, TraitDef, DefDef, TypeDef, PatVarDef, Import, Expr
                    )
                  }
                }
            }
        }

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
        ammInterp0.resolutionHooks += { fetch =>
          val params0 = Tmp.resolutionParams(fetch)
          val params = params0
            .withForcedProperties(params0.forcedProperties ++ forceMavenProperties)
          fetch.withResolutionParams(params)
        }

      if (mavenProfiles.nonEmpty)
        ammInterp0.resolutionHooks += { fetch =>
          val mavenProfiles0 = mavenProfiles.toVector.map {
            case (p, true) => p
            case (p, false) => "!" + p
          }
          val params0 = Tmp.resolutionParams(fetch)
          val params = params0
            .withProfiles(params0.profiles ++ mavenProfiles0)
          fetch.withResolutionParams(params)
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

  override def interruptSupported: Boolean =
    true
  override def interrupt(): Unit =
    execute0.interrupt()

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

    execute0.execute(ammInterp0, hackedLine, inputManager, outputHandler, colors0)
  }

  def currentLine(): Int =
    execute0.currentLine

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
      frames0().head.imports.toString(),
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
    inspectionsShutdown()

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

  private def predef =
    """import almond.api.JupyterAPIHolder.value.{
      |  publish,
      |  commHandler
      |}
      |import almond.api.JupyterAPIHolder.value.publish.display
      |import almond.interpreter.api.DisplayData.DisplayDataSyntax
      |import almond.display._
      |import almond.display.Display.{markdown, html, latex, text, js, svg}
    """.stripMargin

  private class Foo
}
