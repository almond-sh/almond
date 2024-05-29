package almond

import almond.amm.AmmInterpreter
import almond.api.JupyterApi
import almond.internals._
import almond.interpreter._
import almond.interpreter.api.{CommHandler, OutputHandler}
import almond.interpreter.input.InputManager
import almond.interpreter.util.AsyncInterpreterOps
import almond.logger.LoggerContext
import almond.protocol.{KernelInfo, RawJson}
import almond.toree.{CellMagicHook, LineMagicHook}
import ammonite.compiler.Parsers
import ammonite.repl.{ReplApiImpl => _, _}
import ammonite.runtime._
import ammonite.util.{Frame => _, _}
import coursier.cache.shaded.dirs.{GetWinDirs, ProjectDirectories}
import fastparse.Parsed

import java.nio.charset.StandardCharsets

import scala.util.control.NonFatal

/** Holds bits of state for the interpreter, and implements [[almond.interpreter.Interpreter]]. */
final class ScalaInterpreter(
  params: ScalaInterpreterParams = ScalaInterpreterParams(),
  val logCtx: LoggerContext = LoggerContext.nop
) extends Interpreter with AsyncInterpreterOps {

  private val log = logCtx(getClass)

  private val frames0: Ref[List[Frame]] = Ref(List(Frame.createInitial(params.initialClassLoader)))

  if (params.extraClassPath.nonEmpty)
    frames0().head.addClasspath(params.extraClassPath.map(_.toNIO.toUri.toURL))

  private var inspectionsInitialized = false
  private lazy val inspections = {
    val value = new ScalaInterpreterInspections(
      logCtx,
      params.metabrowse,
      params.metabrowseHost,
      params.metabrowsePort,
      ammonite.compiler.CompilerBuilder.scalaVersion,
      ammInterp
        .compilerManager
        .asInstanceOf[ammonite.compiler.CompilerLifecycleManager],
      frames0()
    )
    inspectionsInitialized = true
    value
  }

  private val colors0: Ref[Colors] = Ref(params.initialColors)

  private val silent0: Ref[Boolean] = Ref(false)

  private var commHandlerOpt = Option.empty[CommHandler]

  private val storage =
    if (params.disableCache)
      Storage.InMemory()
    else {
      val getWinDirs: GetWinDirs =
        if (coursier.paths.Util.useJni())
          new JniGetWinDirs
        else
          GetWinDirs.powerShellBased
      val projDirs = ProjectDirectories.from(null, null, "Almond", getWinDirs)
      new Storage.Folder(
        os.Path(projDirs.cacheDir) / "ammonite"
      )
    }

  private val execute0 = new Execute(
    params.trapOutput,
    params.quiet,
    storage,
    logCtx,
    params.updateBackgroundVariablesEcOpt,
    commHandlerOpt,
    silent0,
    params.useThreadInterrupt,
    params.initialCellCount,
    enableExitHack = params.compileOnly,
    ignoreLauncherDirectivesIn = params.ignoreLauncherDirectivesIn,
    launcherDirectiveGroups = params.launcherDirectiveGroups
  )

  val sessApi = new SessionApiImpl(frames0)

  val replApi =
    new ReplApiImpl(
      execute0,
      storage,
      colors0,
      ammInterp,
      sessApi
    )

  val jupyterApi =
    new JupyterApiImpl(
      execute0,
      commHandlerOpt,
      replApi,
      silent0,
      params.allowVariableInspector,
      kernelClassLoader = Thread.currentThread().getContextClassLoader,
      consoleOut = System.out,
      consoleErr = System.err,
      logCtx = logCtx
    )

  if (params.toreeMagics) {
    jupyterApi.addExecuteHook(LineMagicHook.hook(replApi.pprinter))
    jupyterApi.addExecuteHook(CellMagicHook.hook(jupyterApi.publish))
  }

  lazy val ammInterp: ammonite.interp.Interpreter = {

    for (ec <- params.updateBackgroundVariablesEcOpt)
      UpdatableFuture.setup(replApi, jupyterApi, ec)

    val interp = AmmInterpreter(
      execute0,
      storage,
      replApi,
      jupyterApi,
      params.predefCode,
      params.predefFiles,
      frames0,
      params.codeWrapper,
      params.extraRepos,
      params.automaticDependencies,
      params.automaticVersions,
      params.forceMavenProperties,
      params.mavenProfiles,
      params.autoUpdateLazyVals,
      params.autoUpdateVars,
      params.useNotebookCoursierLogger,
      params.silentImports,
      logCtx,
      jupyterApi.VariableInspector.enabled,
      outputDir = params.outputDir,
      compileOnly = params.compileOnly,
      addToreeApiCompatibilityImport = params.toreeApiCompatibility,
      initialSettings = params.initialSettings
    )

    execute0.loadOptions(interp, params.upfrontKernelOptions)

    interp
  }

  if (!params.lazyInit)
    // eagerly initialize ammInterp
    ammInterp

  override def interruptSupported: Boolean =
    true
  override def interrupt(): Unit =
    execute0.interrupt(jupyterApi)

  override def supportComm: Boolean = true
  override def setCommHandler(commHandler0: CommHandler): Unit =
    commHandlerOpt = Some(commHandler0)

  def execute(
    code: String,
    storeHistory: Boolean, // FIXME Take that one into account
    inputManager: Option[InputManager],
    outputHandler: Option[OutputHandler]
  ): ExecuteResult =
    execute0(
      ammInterp,
      code,
      inputManager,
      outputHandler,
      colors0,
      storeHistory,
      jupyterApi.executeHooks,
      jupyterApi
    )

  def currentLine(): Int =
    execute0.currentLine

  override def isComplete(code: String): Option[IsCompleteResult] = {

    val res = ammonite.compiler.Parsers.split(code, ignoreIncomplete = true, "(notebook)") match {
      case None           => IsCompleteResult.Incomplete
      case Some(Right(_)) => IsCompleteResult.Complete
      case Some(Left(_))  => IsCompleteResult.Invalid
    }

    Some(res)
  }

  override def inspect(code: String, pos: Int, detailLevel: Int): Option[Inspection] =
    inspections.inspect(code, pos, detailLevel)

  override def complete(code: String, pos: Int): Completion = {

    val (newPos, completions0, _, completionsWithTypes) = ScalaInterpreterCompletions.complete(
      ammInterp.compilerManager,
      Some(ammInterp.dependencyComplete),
      pos,
      (ammInterp.predefImports ++ frames0().head.imports).toString(),
      code,
      logCtx
    )

    val completions = completions0
      .filter(!_.contains("$"))
      .filter(_.nonEmpty)

    val metadata =
      if (java.lang.Boolean.getBoolean("almond.completion.demo") && code.startsWith("// Demo"))
        // Types from https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#completionItemKind
        RawJson(
          """{
            |  "_jupyter_types_experimental": [
            |    {
            |      "text": "AField",
            |      "type": "Field"
            |    },
            |    {
            |      "text": "AMethod",
            |      "type": "Method"
            |    },
            |    {
            |      "text": "AConstructor",
            |      "type": "Constructor"
            |    },
            |    {
            |      "text": "AVariable",
            |      "type": "Variable"
            |    },
            |    {
            |      "text": "AClass",
            |      "type": "Class"
            |    },
            |    {
            |      "text": "AnInterface",
            |      "type": "Interface"
            |    },
            |    {
            |      "text": "AModule",
            |      "type": "Module"
            |    },
            |    {
            |      "text": "AProperty",
            |      "type": "Property"
            |    }
            |  ]
            |}
            |""".stripMargin.getBytes(StandardCharsets.UTF_8)
        )
      else {
        val elems = completionsWithTypes.map {
          case (compl, tpe) =>
            val tpe0 = tpe match {
              case "value" => "Field"
              case _       => tpe.capitalize
            }
            ujson.Obj(
              "text" -> ujson.Str(compl),
              "type" -> ujson.Str(tpe0)
            )
        }
        if (elems.isEmpty)
          RawJson.emptyObj
        else {
          val json = ujson.Obj("_jupyter_types_experimental" -> ujson.Arr(elems: _*)).render()
          RawJson(json.getBytes(StandardCharsets.UTF_8))
        }
      }

    Completion(
      if (completions.isEmpty) pos else newPos,
      pos,
      completions.map(_.trim).distinct,
      None,
      metadata = metadata
    )
  }

  def kernelInfo() =
    KernelInfo(
      "scala",
      almond.api.Properties.version,
      KernelInfo.LanguageInfo(
        name = "scala",
        version = scala.util.Properties.versionNumberString,
        mimetype = "text/x-scala",
        file_extension = ".sc",
        nbconvert_exporter = "script",
        codemirror_mode = Some("text/x-scala")
      ),
      s"""Almond ${almond.api.Properties.version}
         |Ammonite ${ammonite.Constants.version}
         |${scala.util.Properties.versionMsg}
         |Java ${sys.props.getOrElse("java.version", "[unknown]")}""".stripMargin +
        params.extraBannerOpt.fold("")("\n\n" + _),
      help_links = Some(params.extraLinks.toList).filter(_.nonEmpty)
    )

  override def shutdown(): Unit = {
    try Function.chain(ammInterp.beforeExitHooks).apply(())
    catch {
      case NonFatal(e) =>
        log.warn("Caught exception while trying to run exit hooks", e)
    }
    if (inspectionsInitialized)
      inspections.shutdown()
  }

}
