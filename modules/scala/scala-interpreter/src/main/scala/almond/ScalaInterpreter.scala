package almond

import almond.amm.AmmInterpreter
import almond.internals._
import almond.interpreter._
import almond.interpreter.api.{CommHandler, OutputHandler}
import almond.interpreter.input.InputManager
import almond.interpreter.util.AsyncInterpreterOps
import almond.logger.LoggerContext
import almond.protocol.KernelInfo
import ammonite.interp.Parsers
import ammonite.repl.{ReplApiImpl => _, _}
import ammonite.runtime._
import ammonite.util._
import fastparse.Parsed
import io.github.soc.directories.ProjectDirectories

import scala.util.control.NonFatal

/** Holds bits of state for the interpreter, and implements [[almond.interpreter.Interpreter]]. */
final class ScalaInterpreter(
  params: ScalaInterpreterParams = ScalaInterpreterParams(),
  val logCtx: LoggerContext = LoggerContext.nop
) extends Interpreter with AsyncInterpreterOps {

  private val log = logCtx(getClass)

  private val frames0: Ref[List[Frame]] = Ref(List(Frame.createInitial(params.initialClassLoader)))

  private val inspections = new ScalaInterpreterInspections(
    logCtx,
    params.metabrowse,
    params.metabrowseHost,
    params.metabrowsePort,
    ammInterp.compilerManager.pressy.compiler,
    frames0()
  )

  private val colors0: Ref[Colors] = Ref(params.initialColors)

  private var commHandlerOpt = Option.empty[CommHandler]

  private val storage =
    if (params.disableCache)
      Storage.InMemory()
    else
      new Storage.Folder(os.Path(ProjectDirectories.from(null, null, "Almond").cacheDir) / "ammonite")

  private val execute0 = new Execute(
    params.trapOutput,
    params.automaticDependencies,
    storage,
    logCtx,
    params.updateBackgroundVariablesEcOpt,
    commHandlerOpt
  )


  lazy val ammInterp: ammonite.interp.Interpreter = {

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
      new JupyterApiImpl(execute0, commHandlerOpt, replApi)

    for (ec <- params.updateBackgroundVariablesEcOpt)
      UpdatableFuture.setup(replApi, jupyterApi, ec)

    AmmInterpreter(
      execute0,
      storage,
      replApi,
      jupyterApi,
      params.predefCode,
      params.predefFiles,
      frames0,
      params.codeWrapper,
      params.extraRepos,
      params.forceMavenProperties,
      params.mavenProfiles,
      params.autoUpdateLazyVals,
      params.autoUpdateVars,
      params.initialClassLoader,
      logCtx
    )
  }

  if (!params.lazyInit)
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
  ): ExecuteResult =
    execute0(ammInterp, code, inputManager, outputHandler, colors0)

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

  override def inspect(code: String, pos: Int, detailLevel: Int): Option[Inspection] =
    inspections.inspect(code, pos, detailLevel)

  override def complete(code: String, pos: Int): Completion = {

    val (newPos, completions0, _) = ammInterp.compilerManager.complete(
      pos,
      (ammInterp.predefImports ++ frames0().head.imports).toString(),
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
        params.extraBannerOpt.fold("")("\n\n" + _),
      help_links = Some(params.extraLinks.toList).filter(_.nonEmpty)
    )

  override def shutdown(): Unit = {
    try Function.chain(ammInterp.beforeExitHooks).apply(())
    catch {
      case NonFatal(e) =>
        log.warn("Caught exception while trying to run exit hooks", e)
    }
    inspections.shutdown()
  }

}
