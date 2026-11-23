package almond

import almond.amm.{AlmondCompilerLifecycleManager, AmmInterpreter}
import almond.api.JupyterApi
import almond.internals._
import almond.interpreter._
import almond.interpreter.api.{CommHandler, ExecuteResult, OutputHandler}
import almond.interpreter.input.InputManager
import almond.interpreter.lsp.{LanguageServer, LspCommTarget}
import almond.interpreter.util.AsyncInterpreterOps
import almond.logger.LoggerContext
import almond.lsp.{
  JupyterLabDocumentModel,
  Lsp4jLanguageServer,
  AlmondLspFeatures,
  AlmondLanguageServer,
  SessionSources,
  SourceFile,
  SourceLayout,
  WrappedSource
}
import almond.protocol.{Execute => ProtocolExecute, KernelInfo, RawJson}
import almond.toree.{CellMagicHook, LineMagicHook}
import ammonite.repl.bsp.{ReplBspServer, ReplBspSession}
import ammonite.compiler.Parsers
import ammonite.repl.{ReplApiImpl => _, _}
import ammonite.runtime._
import ammonite.util.{Frame => _, _}
import coursier.paths.shaded.dirs.ProjectDirectories
import coursier.paths.shaded.dirs.impl.Windows
import coursier.paths.shaded.dirs.jni.WindowsJni
import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReaderException,
  JsonValueCodec,
  readFromArray
}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fastparse.Parsed

import java.nio.charset.StandardCharsets

import scala.collection.mutable
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

  /** The UNIX domain socket the BSP server of the session listens on, if any */
  private val bspSocket: Option[os.Path] =
    params.bspSocket.orElse {
      if (params.lsp)
        // short path, as socket paths can't be longer than about 100 characters
        Some(os.temp.dir(prefix = "almond-bsp") / "bsp.sock")
      else
        None
    }

  /** Where the code of each cell, and its byte code, are written, if needed */
  private val frameOutputs: Option[FrameOutputs] =
    params.sessionDirectory
      .orElse(bspSocket.map(_ => os.temp.dir(prefix = "almond-session")))
      .map { dir =>
        // BSP clients want SemanticDB files to refer to the sources we report, the .sc files
        val outputs = new FrameOutputs(dir, false, bspSocket.nonEmpty)
        for (frame <- frames0())
          outputs.register(frame)
        outputs
      }

  @volatile private var bspServer = Option.empty[ReplBspServer]

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
      frames0(),
      fullImports
    )
    inspectionsInitialized = true
    value
  }

  private val colors0: Ref[Colors] = Ref(params.initialColors)

  private val silent0: Ref[Boolean] = Ref(false)

  private val outputStyle0: Ref[OutputStyle] = Ref(
    if (params.lastValueOnly && params.outputStyle == OutputStyle.Default) OutputStyle.Last
    else params.outputStyle
  )

  private var commHandlerOpt = Option.empty[CommHandler]

  private lazy val lspSources = {
    val root = frameOutputs.map(_.directory / "lsp").getOrElse {
      params.outputDir match {
        case Left(dir) => dir / "lsp"
        case Right(_)  => os.temp.dir(prefix = "almond-lsp")
      }
    }
    new SessionSources(SourceLayout(root, params.pkgName))
  }

  private val storage =
    if (params.disableCache)
      Storage.InMemory()
    else {
      val windows =
        if (coursier.paths.Util.useJni())
          WindowsJni.getJdkAwareSupplier()
        else
          Windows.getDefaultSupplier()
      val projDirs = ProjectDirectories.from(null, null, "Almond", windows)
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
    outputStyle0,
    params.useThreadInterrupt,
    params.initialCellCount,
    enableExitHack = params.compileOnly,
    ignoreLauncherDirectivesIn = params.ignoreLauncherDirectivesIn,
    launcherDirectiveGroups = params.launcherDirectiveGroups,
    pkgName = params.pkgName,
    wrapperPath = params.codeWrapper.wrapperPath
  )

  private var currentExecuteRequestOpt0 = Option.empty[Message[ProtocolExecute.Request]]

  val sessApi = new SessionApiImpl(frames0)

  val replApi =
    new ReplApiImpl(
      execute0,
      storage,
      colors0,
      ammInterp,
      sessApi,
      outputStyle = () => outputStyle0()
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
      logCtx = logCtx,
      currentExecuteRequest0 = currentExecuteRequestOpt0
    )

  if (params.toreeMagics) {
    jupyterApi.addExecuteHook(LineMagicHook.hook(replApi.pprinter))
    jupyterApi.addExecuteHook(CellMagicHook.hook(jupyterApi.publish))
  }

  /** The imports in scope in cells: those of the predefs, then those of the previous cells */
  private def fullImports: Imports =
    ammInterp.predefImports ++ frames0().head.imports

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
      params.dependencies,
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
      () => outputStyle0(),
      outputDir = params.outputDir,
      compileOnly = params.compileOnly,
      addToreeApiCompatibilityImport = params.toreeApiCompatibility,
      initialSettings = params.initialSettings,
      wrapperNamePrefix = params.wrapperNamePrefix,
      pkgName = params.pkgName,
      evaluatorHookOpt = params.evaluatorHookOpt,
      logCode = params.logCode,
      frameOutputs = frameOutputs
    )

    for (socket <- bspSocket; outputs <- frameOutputs)
      if (ammonite.util.Util.javaMajorVersion < 17)
        log.warn(s"Not starting a BSP server on $socket, as it requires Java 17 or later")
      else {
        val session = new ReplBspSession(
          () => sessApi.liveFrames,
          outputs,
          interp.scalaVersion,
          interp.scalacOptions ++ interp.bspSemanticDbOptions,
          ammonite.util.Classpath.classpath(params.initialClassLoader)
        )
        val server = ReplBspServer.start(socket.toString, session, msg => log.error(msg))
        log.info(s"BSP server listening on ${server.socketPath}")
        bspServer = Some(server)
      }

    interp
  }

  if (!params.lazyInit) {
    // eagerly initialize ammInterp
    ammInterp
    // and start indexing source JARs in the background (Scala 2), for inspections
    inspections.updateSourceIndex()
  }

  override def interruptSupported: Boolean =
    true
  override def interrupt(): Unit =
    execute0.interrupt(jupyterApi)

  override def supportComm: Boolean = true
  override def setCommHandler(commHandler0: CommHandler): Unit = {
    commHandlerOpt = Some(commHandler0)
    if (params.lsp)
      LspCommTarget.register(
        new Lsp4jLanguageServer(
          () =>
            new AlmondLanguageServer(
              lspSources,
              AlmondLspFeatures.Unimplemented,
              Seq(new JupyterLabDocumentModel),
              logCtx
            ),
          logCtx
        ),
        commHandler0,
        logCtx
      )
  }

  private var upfrontOptionsLoaded = false

  /** Loads the upfront kernel options, if that wasn't done already
    *
    * These come from the directives of the first cell, that the launcher passes us. They're loaded
    * when that cell runs, rather than when the interpreter is initialized (in the background, when
    * the kernel starts), so that the notebook coursier logger can show the downloads of their
    * dependencies in that cell.
    */
  private def loadUpfrontOptions(
    ammInterp: ammonite.interp.Interpreter,
    outputHandler: Option[OutputHandler]
  ): Option[ExecuteResult.Error] =
    if (upfrontOptionsLoaded) None
    else {
      upfrontOptionsLoaded = true
      execute0.loadOptions(ammInterp, params.upfrontKernelOptions, outputHandler, colors0())
    }

  def execute(
    code: String,
    storeHistory: Boolean, // FIXME Take that one into account
    inputManager: Option[InputManager],
    outputHandler: Option[OutputHandler],
    messageOpt: Option[Message[ProtocolExecute.Request]]
  ): ExecuteResult =
    try {
      currentExecuteRequestOpt0 = messageOpt
      val ammInterp0 = ammInterp
      // If the upfront options can't be loaded, we report it in the cell and don't run it (like
      // when the directives of a cell can't be loaded). Users can then run the cell holding
      // the faulty directives again, once fixed.
      loadUpfrontOptions(ammInterp0, outputHandler).getOrElse {
        val lineBefore = currentLine()
        val res = execute0(
          ammInterp0,
          code,
          inputManager,
          outputHandler,
          colors0,
          storeHistory,
          // after the hooks of users, so that they can handle magics unknown to the Toree ones
          if (params.toreeMagics)
            jupyterApi.executeHooks :+ LineMagicHook.unrecognizedMagicsHook
          else
            jupyterApi.executeHooks,
          jupyterApi.postRunHooks(),
          jupyterApi
        )
        // the line only changes if the code was compiled
        if (params.lsp && currentLine() > lineBefore) {
          val wrapperName = params.wrapperNamePrefix + currentLine()
          lspSources.add(
            currentLine(),
            wrapperName,
            code,
            messageOpt.flatMap(ScalaInterpreter.cellId),
            inputSource(wrapperName, code)
          )
        }
        res
      }
    }
    finally {
      currentExecuteRequestOpt0 = None
      // index the source JARs of the dependencies the cell added, if any
      inspections.updateSourceIndex()
    }

  def currentLine(): Int =
    execute0.currentLine

  /** Registers the code of a cell that wasn't run as a source of the current frame of the session
    * outputs, wrapped like the next input would be, and returns its file
    *
    * BSP clients then see it as a source of the current frame, alongside the inputs run so far, so
    * that they can offer completions, say, in cells that weren't run yet. The file of each cell is
    * overwritten when its code changes.
    */
  def registerPendingSource(notebookUri: String, cell: Int, code: String): Option[SourceFile] =
    frameOutputs.flatMap { outputs =>
      // empty code gets skipped when preprocessing, which would leave the former code in place
      val code0 = if (code.trim.isEmpty) "()" else code
      val stmts = Parsers.split(code0, ignoreIncomplete = true, "(notebook)") match {
        case Some(Right(stmts)) => stmts
        case _                  => Seq(code0)
      }
      val name   = s"pending_${SourceLayout.hash(notebookUri)}_cell$cell"
      val interp = ammInterp
      withCompilerLock {
        val codeSource = ammonite.util.Util.CodeSource(
          Name(name),
          Nil,
          params.pkgName.map(Name(_)),
          Some((os.pwd / "(notebook)").toNIO)
        )
        val res = interp.compilerManager.preprocess("(notebook)").transform(
          stmts,
          "0",
          "",
          codeSource,
          Name(name),
          interp.predefImports ++ interp.frameImports,
          // nothing gets printed, the code is only meant to be type checked
          _ => "()",
          "",
          true,
          false,
          params.codeWrapper
        )
        res match {
          case Res.Success(processed) =>
            val pending = outputs.prepare(
              interp.headFrame,
              os.sub / "pending" / SourceLayout.hash(notebookUri) / s"cell$cell",
              processed
            )
            outputs.commit(pending, Vector.empty)
            inputSource(pending.sourceFile, code)
          case other =>
            log.debug(s"Could not preprocess cell $cell of $notebookUri: $other")
            None
        }
      }
    }

  /** The file Ammonite wrote the code of input `wrapperName` to, if it wrote it somewhere */
  private def inputSource(wrapperName: String, code: String): Option[SourceFile] =
    frameOutputs.flatMap { outputs =>
      outputs.sources(frames0().head)
        .find(_.path.last == s"$wrapperName.sc")
        .flatMap(source => inputSource(source.path, code))
    }

  /** The file of a source of the current frame, along with how it was wrapped */
  private def inputSource(path: os.Path, code: String): Option[SourceFile] =
    for {
      outputs <- frameOutputs
      source  <- outputs.sources(frames0().head).find(_.path == path)
    } yield SourceFile(
      source.path.toNIO.toUri.toASCIIString,
      source.wrapped.map { wrapped =>
        WrappedSource(
          wrapped.path.toNIO.toUri.toASCIIString,
          wrapped.topWrapper.count(_ == '\n'),
          code.count(_ == '\n') + 1
        )
      }
    )

  override def isComplete(code: String): Option[IsCompleteResult] = {

    val res = ammonite.compiler.Parsers.split(code, ignoreIncomplete = true, "(notebook)") match {
      case None           => IsCompleteResult.Incomplete(Indentation.nextLine(code))
      case Some(Right(_)) => IsCompleteResult.Complete
      case Some(Left(_))  => IsCompleteResult.Invalid
    }

    Some(res)
  }

  // Completions and inspections are computed from threads other than the one running cells
  // (see AsyncInterpreterOps), so that they can be computed while a cell is running.
  // They use compiler instances managed by the compiler lifecycle manager, whose
  // methods that compile code synchronize on the manager itself. We do the same here,
  // so that completions and inspections wait for any ongoing compilation to be done
  // (and so that compilations wait for them to be done).
  private def withCompilerLock[T](f: => T): T = {
    val compilerManager = ammInterp.compilerManager
    compilerManager.synchronized(f)
  }

  override def inspect(code: String, pos: Int, detailLevel: Int): Option[Inspection] =
    withCompilerLock {
      inspections.inspect(code, pos, detailLevel)
    }

  // completes the dependencies of '//> using dep' directives, that the compiler sees as comments
  private def completeDirectiveDependency(
    value: DirectiveCompletion.DependencyValue
  ): (Int, Seq[String]) =
    if (value.prefix.isEmpty) (value.start, Nil)
    else {
      val (from, completions) =
        try ammInterp.dependencyComplete(value.prefix)
        catch {
          case NonFatal(e) =>
            log.info(s"Ignoring exception while completing dependency ${value.prefix}", e)
            (0, Nil)
        }
      (value.start + from, DirectiveCompletion.sortCompletions(value.prefix, completions))
    }

  /** Removes the completions we don't want to offer, and the duplicates (keeping the first one) */
  private def cleanCompletions(completions: Seq[(String, String)]): Seq[(String, String)] = {
    val seen = mutable.HashSet.empty[String]
    completions
      .map { case (compl, kind) => (compl.trim, kind) }
      .filter { case (compl, _) => compl.nonEmpty && !compl.contains("$") && seen.add(compl) }
  }

  private def completeCode(
    code: String,
    pos: Int
  ): (Int, Int, Seq[(String, String)]) = {

    val backquotedIdentOpt = BackquotedCompletion.find(code, pos)

    val (newPos0, completions0) = withCompilerLock {
      ScalaInterpreterCompletions.complete(
        ammInterp.compilerManager,
        Some(ammInterp.dependencyComplete),
        pos,
        fullImports.toString(),
        backquotedIdentOpt.fold(code)(_.codeToComplete(code, pos)),
        logCtx
      )
    }

    val (newPos, endPos, adjustCompletion) = backquotedIdentOpt
      .flatMap(_.adjust(pos, newPos0))
      .getOrElse((newPos0, pos, (s: String) => s))

    val completions = cleanCompletions(completions0).map {
      case (compl, kind) =>
        (adjustCompletion(compl), kind)
    }

    (newPos, endPos, cleanCompletions(completions))
  }

  override def complete(code: String, pos: Int): Completion = {

    // matches, and their types if any, which must be aligned with them: JupyterLab matches
    // items of '_jupyter_types_experimental' with completions by index, and inserts the text
    // of the former
    val (newPos, endPos, completions, typesOpt) =
      DirectiveCompletion.findDependency(code, pos) match {
        case Some(value) =>
          val (start, completions) = completeDirectiveDependency(value)
          // No types for these: when filtering completions, JupyterLab sorts the ones matching
          // equally well alphabetically by the text that comes with their type, and keeps their
          // order if they have none, which keeps versions sorted (highest first)
          val completions0 = cleanCompletions(completions.map((_, ""))).map(_._1)
          (start, pos, completions0, None)
        case None if Comments.isInComment(code, pos) =>
          // the Scala 2 presentation compiler offers all the names in scope there
          (pos, pos, Nil, None)
        case None =>
          val (newPos, endPos, completionsWithKinds) = completeCode(code, pos)
          (newPos, endPos, completionsWithKinds.map(_._1), Some(completionsWithKinds.map(_._2)))
      }

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
        val elems = typesOpt.toSeq.flatMap(completions.zip(_)).map {
          case (compl, kind) =>
            val tpe0 = kind match {
              case "value" => "Field"
              case _       => kind.capitalize
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
      if (completions.isEmpty) pos else endPos,
      completions,
      None,
      metadata = metadata
    )
  }

  private def nl = System.lineSeparator()
  def kernelInfo() = {
    // Not scala.util.Properties.versionNumberString, that's the version of the Scala library,
    // like 2.13.x for Scala 3 versions earlier than 3.8
    val scalaVersion = AlmondCompilerLifecycleManager.compilerVersion
    KernelInfo(
      "scala",
      almond.api.Properties.version,
      KernelInfo.LanguageInfo(
        name = "scala",
        version = scalaVersion,
        mimetype = "text/x-scala",
        file_extension = ".sc",
        nbconvert_exporter = "script",
        codemirror_mode = Some("scala")
      ),
      s"""Almond ${almond.api.Properties.version}
         |Ammonite ${ammonite.Constants.version}
         |Scala $scalaVersion
         |Java ${sys.props.getOrElse("java.version", "[unknown]")}""".stripMargin +
        params.extraBannerOpt.fold("")(nl + nl + _),
      help_links = Some(params.extraLinks.toList).filter(_.nonEmpty)
    ).copy(
      supported_features =
        if (params.lsp) List(LanguageServer.supportedFeature)
        else Nil,
      lsp =
        if (params.lsp)
          Some(KernelInfo.Lsp(cell_separator_prefix =
            Some(JupyterLabDocumentModel.cellSeparatorPrefix)
          ))
        else None
    )
  }

  override def shutdown(): Unit = {
    try Function.chain(ammInterp.beforeExitHooks).apply(())
    catch {
      case NonFatal(e) =>
        log.warn("Caught exception while trying to run exit hooks", e)
    }

    if (inspectionsInitialized)
      inspections.shutdown()

    AlmondCompilerLifecycleManager.closeCompiler(ammInterp.compilerManager.compiler)

    for (server <- bspServer)
      server.close()

    for (frame <- frames0()) {
      frame.pluginClassloader.close()
      frame.classloader.close()
    }
  }

}

object ScalaInterpreter {

  private final case class ExecuteMetadata(cellId: Option[String] = None)

  private implicit val executeMetadataCodec: JsonValueCodec[ExecuteMetadata] =
    JsonCodecMaker.make

  /** Id of the cell of an execute request, that JupyterLab sends in its metadata */
  private def cellId(message: Message[ProtocolExecute.Request]): Option[String] =
    try readFromArray[ExecuteMetadata](message.metadata.value).cellId
    catch {
      case _: JsonReaderException => None
    }
}
