package almond.lsp

import almond.logger.LoggerContext
import org.eclipse.lsp4j._
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.{LanguageClient, LanguageServer}

import java.util.concurrent.{
  CompletableFuture,
  ConcurrentLinkedDeque,
  ExecutorService,
  Executors
}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** Language features computed by Plasmon, which gets the session from its BSP server
  *
  * Plasmon is run as a sub-process, speaking LSP over its standard input and output. It's started
  * upon the first request. The files of units are opened in it, with their code (along with that of
  * the pending cells before them for pending units, see [[UnitText]]), before sending it requests
  * about them.
  *
  * @param launcher
  *   how Plasmon is run
  * @param bspSocket
  *   UNIX domain socket the BSP server of the session listens on
  * @param workspace
  *   the directory Plasmon runs from, and that it gets as workspace
  * @param pendingSource
  *   registers a cell that wasn't run as a source of the session, with the code it's passed for its
  *   file (that of its [[UnitText]]), so that Plasmon gets it from the BSP server, and returns its
  *   file
  * @param parses
  *   whether code parses, see [[UnitText]]
  */
final class PlasmonFeatures(
  launcher: PlasmonLauncher,
  bspSocket: os.Path,
  workspace: os.Path,
  sources: SessionSources,
  pendingSource: (SourceUnit.Pending, String) => Option[SourceFile],
  parses: String => Boolean,
  logCtx: LoggerContext
) extends AlmondLspFeatures with AutoCloseable {

  import PlasmonFeatures._

  private val log = logCtx(getClass)

  /** Code of the files opened in Plasmon, by URI, along with their version */
  private val opened = TrieMap.empty[String, (UnitText, Int)]

  /** Whether code parses, by code, for the cells we nest in the text of the pending cells after
    * them, which get synced again whenever the notebook changes
    */
  private val parsed = TrieMap.empty[String, Boolean]

  private def unitText(unit: SourceUnit): UnitText =
    UnitText(
      unit,
      code => {
        // the code of cells being edited changes all the time
        if (parsed.size > 1000) parsed.clear()
        parsed.getOrElseUpdate(code, parses(code))
      }
    )

  /** Code and file of the pending cells registered so far, by notebook URI and cell index, along
    * with the number of inputs run at that time (which changes the imports of their wrapper)
    */
  private val pendingFiles = TrieMap.empty[(String, Int), (String, Int, SourceFile)]

  /** Diagnostics last published by Plasmon, by URI */
  private val publishedDiagnostics = TrieMap.empty[String, Seq[Diagnostic]]

  private val diagnosticsListeners = new java.util.concurrent.CopyOnWriteArrayList[() => Unit]

  override def onDiagnosticsChange(listener: () => Unit): AutoCloseable = {
    diagnosticsListeners.add(listener)
    () => diagnosticsListeners.remove(listener)
  }

  private object client extends PlasmonClient {
    def plasmonStatusUpdate(uri: String, updates: AnyRef): Unit = ()
    def plasmonProgress(params: com.google.gson.JsonObject): Unit =
      onPlasmonProgress(params)
    def plasmonHeartBeat(): Unit = ()
    // we re-index upon the changes this is about ourselves, see sessionChanged
    def plasmonBuildChangeDetected(params: AnyRef): Unit  = ()
    def plasmonExecuteClientCommand(params: AnyRef): Unit = ()
    def plasmonLog(params: com.google.gson.JsonObject): Unit =
      for (line <- params.getAsJsonArray("lines").asScala)
        log.debug(s"Plasmon: ${line.getAsString}")
    def telemetryEvent(obj: AnyRef): Unit = ()
    def publishDiagnostics(params: PublishDiagnosticsParams): Unit = {
      val diagnostics = params.getDiagnostics.asScala.toVector
      val former      = publishedDiagnostics.put(params.getUri, diagnostics)
      if (!former.contains(diagnostics))
        diagnosticsListeners.asScala.foreach(_())
    }
    def showMessage(params: MessageParams): Unit =
      log.info(s"Plasmon: ${params.getMessage}")
    def showMessageRequest(params: ShowMessageRequestParams)
      : CompletableFuture[MessageActionItem] = {
      log.info(s"Plasmon: ${params.getMessage}")
      CompletableFuture.completedFuture(null)
    }
    def logMessage(params: MessageParams): Unit =
      log.debug(s"Plasmon: ${params.getMessage}")
  }

  @volatile private var started = false

  /** The Plasmon process, once started */
  @volatile private var process = Option.empty[os.SubProcess]

  /** Plasmon, started upon first access
    *
    * Getting its command can mean downloading it, so this is done on a thread of its own, not to
    * block the thread of the first request.
    */
  private lazy val running: CompletableFuture[Running] = {
    started = true
    val result = new CompletableFuture[Running]
    tasks.start(startTask)
    result.whenComplete((_, _) => tasks.done(startTask.id))
    val starter = new Thread("plasmon-start") {
      override def run(): Unit =
        try startPlasmon(launcher.command(logCtx), result)
        catch {
          case NonFatal(ex) =>
            if (!closing) log.warn("Error starting Plasmon", ex)
            result.completeExceptionally(ex)
        }
    }
    starter.setDaemon(true)
    starter.start()
    result
  }

  private def startPlasmon(command: Seq[String], result: CompletableFuture[Running]): Unit = {
    if (closing)
      throw new Exception("Not starting Plasmon, as we're closing")
    // --auto, so that Plasmon loads the BSP module of the files we open
    val cmd = command ++ Seq("server", "--bsp", bspSocket.toString, "--auto=true")
    log.info(s"Starting Plasmon: ${cmd.mkString(" ")}")
    // last lines of its stderr, to report them if it exits
    val stderrTail = new ConcurrentLinkedDeque[String]
    val proc = os.proc(cmd).spawn(
      cwd = workspace,
      stderr = os.ProcessOutput.Readlines { line =>
        log.debug(s"Plasmon: $line")
        stderrTail.addLast(line)
        if (stderrTail.size > stderrTailSize) stderrTail.pollFirst()
      }
    )
    process = Some(proc)
    if (closing)
      proc.destroy()
    val pool = Executors.newCachedThreadPool { (r: Runnable) =>
      val t = new Thread(r, "plasmon-client")
      t.setDaemon(true)
      t
    }
    val clientLauncher = LSPLauncher.createClientLauncher(
      Lsp4jLanguageServer.proxy(classOf[PlasmonClient], client),
      proc.stdout.wrapped,
      proc.stdin.wrapped,
      pool,
      null
    )
    clientLauncher.startListening()
    val server = clientLauncher.getRemoteProxy

    val params = new InitializeParams
    params.setWorkspaceFolders(
      List(new WorkspaceFolder(workspace.toNIO.toUri.toASCIIString, "session")).asJava
    )
    params.setCapabilities(clientCapabilities)
    server.initialize(params).whenComplete { (_, ex) =>
      if (ex == null) {
        server.initialized(new InitializedParams)
        log.info("Plasmon initialized")
        enablePcDiagnostics(server)
        result.complete(Running(proc, server, pool))
      }
      else
        result.completeExceptionally(ex)
    }
    // Requests sent to a Plasmon that exited would never get answers: report its exit, and fail
    // the requests waiting for it to start
    val watcher = new Thread("plasmon-exit") {
      override def run(): Unit = {
        proc.waitFor()
        val tail = stderrTail.asScala.mkString(System.lineSeparator())
        val msg  = s"Plasmon exited with code ${proc.exitCode()}"
        if (!closing) log.warn(s"$msg, last lines of its output:${System.lineSeparator()}$tail")
        result.completeExceptionally(new Exception(msg))
      }
    }
    watcher.setDaemon(true)
    watcher.start()
  }

  private val indexLock = new Object

  /** Completes once the re-indexings asked so far are done */
  private var indexed: CompletableFuture[Unit] = CompletableFuture.completedFuture(())

  /** Whether a re-indexing was asked, but didn't start yet
    *
    * Changes happening in the meantime are taken into account by that re-indexing.
    */
  private var indexQueued = false

  /** To be called when the sources or the class path of the session change, as reported by its BSP
    * server
    *
    * Plasmon leaves re-indexing to its clients when BSP says build targets changed - when cells are
    * run, and add sources to the session - and tells them with `plasmon/buildChangeDetected`. Until
    * then, it doesn't know about these sources, nor about what they define. We don't wait for that
    * notification, which comes some time after the change: requests sent in the meantime would get
    * answered as if nothing changed.
    */
  def sessionChanged(): Unit =
    if (started && !closing)
      indexLock.synchronized {
        if (!indexQueued) {
          indexQueued = true
          indexed = indexed.thenCompose[Unit] { _ =>
            indexLock.synchronized {
              indexQueued = false
            }
            running
              .thenCompose[AnyRef] { r =>
                r.server.getWorkspaceService
                  .executeCommand(
                    new ExecuteCommandParams("plasmon/index", java.util.Collections.emptyList())
                  )
                  .thenCompose[AnyRef](_ => enablePcDiagnostics(r.server))
              }
              .handle[Unit] { (_, ex) =>
                if (ex != null && !closing)
                  log.warn("Error re-indexing the session in Plasmon", ex)
              }
          }
        }
      }

  /** Completes once the re-indexings asked so far are done
    *
    * Plasmon answers requests with empty results while it re-indexes.
    */
  private def reIndexed(): CompletableFuture[Unit] =
    indexLock.synchronized(indexed)

  @volatile private var closing = false

  /** Has Plasmon report the diagnostics of its presentation compiler
    *
    * Plasmon only reports the diagnostics of compilations and syntax errors by default. Cells don't
    * get compiled by the build, only by the presentation compiler, whose diagnostics are the only
    * ones they get. This applies to the BSP connections Plasmon has at that time, so this is called
    * again after re-indexing, which may connect to the BSP server again.
    */
  private def enablePcDiagnostics(server: LanguageServer): CompletableFuture[AnyRef] =
    server.getWorkspaceService
      .executeCommand(
        new ExecuteCommandParams(
          "plasmon/enablePcDiagnostics",
          java.util.Collections.singletonList[AnyRef](java.lang.Boolean.TRUE)
        )
      )
      .exceptionally { ex =>
        if (!closing)
          log.warn("Error enabling the presentation compiler diagnostics of Plasmon", ex)
        null
      }

  private val tasks = new LspTasks(logCtx = logCtx)

  override def onTasksChange(listener: Seq[LspTask] => Unit): AutoCloseable =
    tasks.subscribe(listener)

  /** Requests in progress in Plasmon, by component (indexer, BSP connection, presentation compiler,
    * …), along with the name of that component
    */
  private val plasmonRequests =
    mutable.LinkedHashMap.empty[String, (String, mutable.LinkedHashMap[String, String])]

  /** Reports what Plasmon says it's doing as a task per component of Plasmon
    *
    * Plasmon reports requests to its components, and steps of its own work (like indexing), each
    * with an identifier, an "id" and a name for the component, and a description. The task of a
    * component is described by its latest request in progress.
    */
  private def onPlasmonProgress(params: com.google.gson.JsonObject): Unit = {
    def field(name: String): Option[com.google.gson.JsonElement] =
      Option(params.get(name)).filter(!_.isJsonNull)
    def string(name: String): Option[String] = field(name).map(_.getAsString)
    for {
      component <- string("buildToolId")
      // commands we send, re-indexing in practice, which the indexer reports too
      if component != "command"
      requestId <- string("requestId")
    } {
      val name    = string("buildToolName").getOrElse(component)
      val request = string("request").getOrElse("")
      val done    = field("done").exists(_.getAsBoolean)
      val taskId  = s"plasmon-$component"
      val update = plasmonRequests.synchronized {
        val (_, requests) = plasmonRequests.getOrElseUpdate(
          component,
          (name, mutable.LinkedHashMap.empty[String, String])
        )
        if (done) requests -= requestId
        else requests(requestId) = request
        if (requests.isEmpty) {
          plasmonRequests -= component
          None
        }
        else
          Some(LspTask(taskId, componentTitle(component, name), Some(requests.last._2)))
      }
      update match {
        case Some(task) => tasks.start(task)
        case None       => tasks.done(taskId)
      }
    }
  }

  private def clientCapabilities: ClientCapabilities = {
    val textDocument = new TextDocumentClientCapabilities
    val hover        = new HoverCapabilities
    hover.setContentFormat(List(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT).asJava)
    textDocument.setHover(hover)
    val completionItem = new CompletionItemCapabilities
    completionItem.setSnippetSupport(true)
    completionItem.setDocumentationFormat(List(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT).asJava)
    textDocument.setCompletion(new CompletionCapabilities(completionItem))
    textDocument.setPublishDiagnostics(new PublishDiagnosticsCapabilities)
    val capabilities = new ClientCapabilities
    capabilities.setTextDocument(textDocument)
    capabilities
  }

  /** Makes Plasmon see the current code of `unit`, and returns its URI, along with the text Plasmon
    * has for it
    */
  private def sync(server: LanguageServer, unit: SourceUnit): (String, UnitText) = {
    val text = unitText(unit)
    val uri  = fileUri(text)
    opened.get(uri) match {
      case None =>
        opened(uri) = (text, 1)
        server.getTextDocumentService.didOpen(
          new DidOpenTextDocumentParams(new TextDocumentItem(uri, "scala", 1, text.text))
        )
      case Some((former, version)) if former.text != text.text =>
        opened(uri) = (text, version + 1)
        server.getTextDocumentService.didChange(
          new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier(uri, version + 1),
            List(new TextDocumentContentChangeEvent(text.text)).asJava
          )
        )
      case Some((former, version)) =>
        // same text, but positions in it may now point to other units, like units of cells
        // that moved
        if (former != text)
          opened(uri) = (text, version)
    }
    (uri, text)
  }

  /** The file of a unit Plasmon knows about, registering it first if it's a pending one
    *
    * Plasmon reads the files of pending units too (to compute SemanticDB data, say), which we give
    * the same code as the one it gets via `didOpen` / `didChange`, so that positions in both agree.
    */
  private def fileUri(text: UnitText): String =
    text.unit match {
      case p: SourceUnit.Pending =>
        val key        = (p.notebookUri, p.cell)
        val inputCount = sources.inputs.length
        pendingFiles.get(key) match {
          case Some((code, count, file)) if code == text.text && count == inputCount => file.uri
          case former =>
            pendingSource(p, text.text) match {
              case Some(file) =>
                pendingFiles(key) = (text.text, inputCount, file)
                file.uri
              case None =>
                former.map(_._3.uri).getOrElse(p.source.uri)
            }
        }
      case unit =>
        unit.source.uri
    }

  /** Empties the files of the cells of `notebookUri` that were registered as pending ones, but that
    * were run since then, or that don't exist anymore
    *
    * Their code would be seen twice by Plasmon otherwise, in their pending file and in the file of
    * their input. These files can't be removed from the session sources, so they're left with no
    * code.
    */
  private def clearStalePendingFiles(
    server: LanguageServer,
    notebookUri: String,
    units: Seq[SourceUnit]
  ): Unit =
    for {
      ((uri, cell), (code, _, file)) <- pendingFiles.toVector
      if uri == notebookUri && code.nonEmpty
      if !units.lift(cell).exists(_.isInstanceOf[SourceUnit.Pending])
    } {
      // what Plasmon has in memory for opened files takes precedence over their content
      if (opened.remove(file.uri).nonEmpty)
        server.getTextDocumentService.didClose(
          new DidCloseTextDocumentParams(new TextDocumentIdentifier(file.uri))
        )
      val empty = SourceUnit.Pending(notebookUri, cell, "", SourceFile(file.uri, None))
      for (file0 <- pendingSource(empty, ""))
        pendingFiles((notebookUri, cell)) = ("", sources.inputs.length, file0)
    }

  private def onServer[T](unit: SourceUnit)(
    f: (LanguageServer, String, UnitText) => CompletableFuture[T]
  ): CompletableFuture[T] =
    running.thenCompose[T] { r =>
      if (r.process.isAlive())
        // syncing can register a new pending file, which needs a re-indexing
        reIndexed()
          .thenApply[(String, UnitText)](_ => sync(r.server, unit))
          .thenCompose[T] {
            case (uri, text) =>
              reIndexed().thenCompose[T](_ => f(r.server, uri, text))
          }
      else {
        val failed = new CompletableFuture[T]
        failed.completeExceptionally(
          new Exception(s"Plasmon exited with code ${r.process.exitCode()}")
        )
        failed
      }
    }

  private def map[A, B](future: CompletableFuture[A])(f: A => B): CompletableFuture[B] =
    future.thenApply[B](a => f(a))

  /** The source location of a location returned by Plasmon, unless it's in the code wrapping that
    * of units
    */
  private def sourceLocation(location: Location): Option[SourceLocation] = {
    val uri = location.getUri
    opened.get(uri).map((_, 0)).orElse(generatedPendingFile(uri)) match {
      case Some(((text, _), wrapperLines)) =>
        val range = location.getRange
        def textPosition(pos: Position) =
          new Position(pos.getLine - wrapperLines, pos.getCharacter)
        text
          .fromText(new Range(textPosition(range.getStart), textPosition(range.getEnd)))
          .map(SourceLocation.InUnit(_))
      case None =>
        val unitOpt = sources.inputs.reverseIterator
          .find(_.source.uri == uri)
          .map(SourceUnit.Evaluated(_))
        unitOpt match {
          case Some(unit) => Some(SourceLocation.InUnit(UnitRange(unit, location.getRange)))
          case None       => Some(SourceLocation.External(location))
        }
    }
  }

  /** The opened file of a pending unit whose generated code is at `uri`, along with the number of
    * lines of the code wrapping the code of the file there
    *
    * Plasmon points to the generated code of the files of pending units for definitions outside of
    * the code of the unit, like those of the cells before it.
    */
  private def generatedPendingFile(uri: String): Option[((UnitText, Int), Int)] = {
    // the presentation compiler of Plasmon gets the generated code as `<generated>.sc.scala`
    val uri0 = if (uri.endsWith(".sc.scala")) uri.stripSuffix(".sc.scala") + ".scala" else uri
    pendingFiles.valuesIterator
      .map(_._3)
      .flatMap(file => file.wrapped.filter(_.uri == uri0).map(file.uri -> _))
      .flatMap {
        case (fileUri, wrapped) => opened.get(fileUri).map((_, wrapped.topWrapperLines))
      }
      .find(_ => true)
  }

  private def textDocumentPosition(uri: String, text: UnitText, at: UnitPosition) =
    (new TextDocumentIdentifier(uri), text.toText(at.position))

  /** Converts the ranges of the edits of a completion item to ranges in the code of `text.unit`
    *
    * Edits elsewhere are left as is, so that the item gets dropped, except insertions before that
    * code, like those of auto-imports, which are moved to its beginning.
    */
  private def unitCompletionItem(text: UnitText, item: CompletionItem): CompletionItem = {
    val codeStart = text.toText(new Position(0, 0))
    def isBefore(pos: Position) =
      pos.getLine < codeStart.getLine ||
      (pos.getLine == codeStart.getLine && pos.getCharacter < codeStart.getCharacter)
    def unitRange(range: Range): Range =
      text.unitRange(range).getOrElse {
        if (range.getStart == range.getEnd && isBefore(range.getStart))
          new Range(new Position(0, 0), new Position(0, 0))
        else
          range
      }
    for (edit <- Option(item.getTextEdit))
      if (edit.isLeft) edit.getLeft.setRange(unitRange(edit.getLeft.getRange))
      else {
        edit.getRight.setInsert(unitRange(edit.getRight.getInsert))
        edit.getRight.setReplace(unitRange(edit.getRight.getReplace))
      }
    for (edits <- Option(item.getAdditionalTextEdits); edit <- edits.asScala)
      edit.setRange(unitRange(edit.getRange))
    item
  }

  def completion(at: UnitPosition): CompletableFuture[Seq[CompletionItem]] =
    onServer(at.unit) { (server, uri, text) =>
      val (doc, pos) = textDocumentPosition(uri, text, at)
      map(server.getTextDocumentService.completion(new CompletionParams(doc, pos))) { res =>
        val items =
          if (res == null) Nil
          else if (res.isLeft) res.getLeft.asScala.toVector
          else res.getRight.getItems.asScala.toVector
        items
          .filter(!_.getLabel.startsWith(UnitText.classNamePrefix))
          .map(unitCompletionItem(text, _))
      }
    }

  def hover(at: UnitPosition): CompletableFuture[Option[Hover]] =
    onServer(at.unit) { (server, uri, text) =>
      val (doc, pos) = textDocumentPosition(uri, text, at)
      map(server.getTextDocumentService.hover(new HoverParams(doc, pos))) { hover =>
        Option(hover).map { hover =>
          Option(hover.getRange).foreach(r => hover.setRange(text.unitRange(r).orNull))
          hover
        }
      }
    }

  def signatureHelp(at: UnitPosition): CompletableFuture[Option[SignatureHelp]] =
    onServer(at.unit) { (server, uri, text) =>
      val (doc, pos) = textDocumentPosition(uri, text, at)
      map(server.getTextDocumentService.signatureHelp(new SignatureHelpParams(doc, pos)))(
        Option(_)
      )
    }

  def definition(at: UnitPosition): CompletableFuture[Seq[SourceLocation]] =
    onServer(at.unit) { (server, uri, text) =>
      val (doc, pos) = textDocumentPosition(uri, text, at)
      map(server.getTextDocumentService.definition(new DefinitionParams(doc, pos))) { res =>
        val locations: Seq[Location] =
          if (res == null) Nil
          else if (res.isLeft) res.getLeft.asScala.toVector
          else
            res.getRight.asScala.toVector.map { link =>
              new Location(link.getTargetUri, link.getTargetSelectionRange)
            }
        locations.flatMap(sourceLocation)
      }
    }

  def references(
    at: UnitPosition,
    includeDeclaration: Boolean
  ): CompletableFuture[Seq[SourceLocation]] =
    onServer(at.unit) { (server, uri, text) =>
      val (doc, pos) = textDocumentPosition(uri, text, at)
      val params =
        new ReferenceParams(doc, pos, new ReferenceContext(includeDeclaration))
      map(server.getTextDocumentService.references(params)) { res =>
        Option(res).toSeq.flatMap(_.asScala).flatMap(l => sourceLocation(l))
      }
    }

  /** Files of the session directory, like the sources of dependencies Plasmon extracts there, that
    * definitions lead to
    */
  override lazy val files: Option[FileFeatures] = Some(sessionFiles)

  private object sessionFiles extends FileFeatures {
    def roots: Seq[os.Path] = Seq(workspace)

    /** Versions of the files opened in Plasmon, by URI */
    private val versions = TrieMap.empty[String, Int]

    private def uri(path: os.Path): String = path.toNIO.toUri.toASCIIString

    private def languageId(path: os.Path): String =
      if (path.last.endsWith(".java")) "java" else "scala"

    def didOpen(path: os.Path, text: String): Unit =
      running.thenAccept { r =>
        versions(uri(path)) = 1
        r.server.getTextDocumentService.didOpen(
          new DidOpenTextDocumentParams(new TextDocumentItem(uri(path), languageId(path), 1, text))
        )
      }
    def didChange(path: os.Path, text: String): Unit =
      running.thenAccept { r =>
        val version = versions.getOrElse(uri(path), 1) + 1
        versions(uri(path)) = version
        r.server.getTextDocumentService.didChange(
          new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier(uri(path), version),
            List(new TextDocumentContentChangeEvent(text)).asJava
          )
        )
      }
    def didClose(path: os.Path): Unit =
      running.thenAccept { r =>
        if (versions.remove(uri(path)).nonEmpty)
          r.server.getTextDocumentService.didClose(
            new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri(path)))
          )
      }

    private def onServer[T](f: LanguageServer => CompletableFuture[T]): CompletableFuture[T] =
      running.thenCompose[T](r => reIndexed().thenCompose[T](_ => f(r.server)))

    def hover(path: os.Path, position: Position): CompletableFuture[Option[Hover]] =
      onServer { server =>
        val params = new HoverParams(new TextDocumentIdentifier(uri(path)), position)
        map(server.getTextDocumentService.hover(params))(Option(_))
      }
    def signatureHelp(path: os.Path, position: Position)
      : CompletableFuture[Option[SignatureHelp]] =
      onServer { server =>
        val params = new SignatureHelpParams(new TextDocumentIdentifier(uri(path)), position)
        map(server.getTextDocumentService.signatureHelp(params))(Option(_))
      }
    def definition(path: os.Path, position: Position): CompletableFuture[Seq[SourceLocation]] =
      onServer { server =>
        val params = new DefinitionParams(new TextDocumentIdentifier(uri(path)), position)
        map(server.getTextDocumentService.definition(params)) { res =>
          val locations: Seq[Location] =
            if (res == null) Nil
            else if (res.isLeft) res.getLeft.asScala.toVector
            else
              res.getRight.asScala.toVector.map { link =>
                new Location(link.getTargetUri, link.getTargetSelectionRange)
              }
          locations.flatMap(sourceLocation)
        }
      }
    def references(
      path: os.Path,
      position: Position,
      includeDeclaration: Boolean
    ): CompletableFuture[Seq[SourceLocation]] =
      onServer { server =>
        val params = new ReferenceParams(
          new TextDocumentIdentifier(uri(path)),
          position,
          new ReferenceContext(includeDeclaration)
        )
        map(server.getTextDocumentService.references(params)) { res =>
          Option(res).toSeq.flatMap(_.asScala).flatMap(l => sourceLocation(l))
        }
      }
  }

  def diagnostics(
    notebookUri: String,
    units: Seq[SourceUnit]
  ): CompletableFuture[Seq[(SourceUnit, Diagnostic)]] =
    running.thenApply[Seq[(SourceUnit, Diagnostic)]] { r =>
      clearStalePendingFiles(r.server, notebookUri, units)
      for {
        unit <- units
        (uri, text) = sync(r.server, unit)
        diagnostic <- publishedDiagnostics.getOrElse(uri, Nil)
        // Plasmon reports errors in the code wrapping the cell too (like syntax errors after
        // incomplete code at its end), which users can't do anything about, and errors in the
        // code of the cells before pending cells, which these cells get diagnostics for
        start <- text.fromText(diagnostic.getRange.getStart)
        if start.unit == unit
        end = text.fromText(diagnostic.getRange.getEnd)
          .filter(_.unit == unit)
          .fold(start.position)(_.position)
      } yield unit -> withRange(diagnostic, new Range(start.position, end))
    }

  def close(): Unit = {
    closing = true
    tasks.close()
    // if Plasmon is still being downloaded, startPlasmon checks closing, and doesn't start it
    for (proc <- process)
      proc.destroy()
    if (started)
      running.thenAccept(_.pool.shutdown())
  }
}

object PlasmonFeatures {

  /** A copy of `diagnostic` with another range, as we keep the diagnostics of Plasmon */
  private def withRange(diagnostic: Diagnostic, range: Range): Diagnostic = {
    val copy = new Diagnostic
    copy.setRange(range)
    copy.setMessage(diagnostic.getMessage)
    copy.setSeverity(diagnostic.getSeverity)
    copy.setCode(diagnostic.getCode)
    copy.setCodeDescription(diagnostic.getCodeDescription)
    copy.setSource(diagnostic.getSource)
    copy.setTags(diagnostic.getTags)
    copy.setRelatedInformation(diagnostic.getRelatedInformation)
    copy.setData(diagnostic.getData)
    copy
  }

  private def startTask = LspTask("plasmon-start", "Starting Plasmon")

  /** Title of the tasks of a Plasmon component, for users
    *
    * Plasmon names components after their implementation (like "Interactive frame-0" for the
    * presentation compiler of the session), which means little to users of notebooks.
    */
  private def componentTitle(id: String, name: String): String =
    id match {
      case "indexer"                          => "Indexing"
      case "bsp"                              => "Loading session"
      case _ if id.startsWith("interactive-") => "Compiling"
      case _                                  => name
    }

  /** How many of the last lines of the output of Plasmon are reported if it exits */
  private def stderrTailSize = 20

  /** Language client for Plasmon, receiving the logs it sends along with its responses, and its
    * other notifications
    *
    * lsp4j logs unsupported notifications to stderr, which would end up in the output of cells.
    */
  trait PlasmonClient extends LanguageClient {
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("plasmon/log")
    def plasmonLog(params: com.google.gson.JsonObject): Unit
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("plasmon/statusUpdate")
    def plasmonStatusUpdate(uri: String, updates: AnyRef): Unit
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("plasmon/progress")
    def plasmonProgress(params: com.google.gson.JsonObject): Unit
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("plasmon/heartBeat")
    def plasmonHeartBeat(): Unit
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("plasmon/buildChangeDetected")
    def plasmonBuildChangeDetected(params: AnyRef): Unit
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("plasmon/executeClientCommand")
    def plasmonExecuteClientCommand(params: AnyRef): Unit
  }

  private final case class Running(
    process: os.SubProcess,
    server: LanguageServer,
    pool: ExecutorService
  )
}
