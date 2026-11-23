package almond.lsp

import almond.logger.LoggerContext
import org.eclipse.lsp4j._
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import org.eclipse.lsp4j.services.{
  LanguageClient,
  LanguageClientAware,
  LanguageServer,
  TextDocumentService,
  WorkspaceService
}

import java.util.{Collections, List => JList}
import java.util.concurrent.CompletableFuture

import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Language server for the notebooks of the Scala kernel
  *
  * Converts the documents and positions of LSP messages to source units (see [[SessionSources]])
  * via `models`, has `features` compute results on those units, and converts these results back.
  *
  * @param models
  *   document models of the clients, tried in order
  */
final class AlmondLanguageServer(
  sources: SessionSources,
  features: AlmondLspFeatures,
  models: Seq[NotebookDocumentModel],
  logCtx: LoggerContext
) extends LanguageServer with LanguageClientAware {

  import AlmondLanguageServer._

  private val log = logCtx(getClass)

  @volatile private var clientOpt = Option.empty[LanguageClient]

  def connect(client: LanguageClient): Unit =
    clientOpt = Some(client)

  /** Whether the client accepts progress notifications that we start ourselves */
  @volatile private var workDoneProgress = false

  @volatile private var tasksListener = Option.empty[AutoCloseable]

  def initialize(params: InitializeParams): CompletableFuture[InitializeResult] = {
    workDoneProgress = Option(params.getCapabilities)
      .flatMap(c => Option(c.getWindow))
      .flatMap(w => Option(w.getWorkDoneProgress))
      .exists(_.booleanValue)
    val capabilities = new ServerCapabilities
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
    capabilities.setCompletionProvider(new CompletionOptions(false, List(".").asJava))
    capabilities.setHoverProvider(java.lang.Boolean.TRUE)
    capabilities.setSignatureHelpProvider(new SignatureHelpOptions(List("(", ",").asJava))
    capabilities.setDefinitionProvider(java.lang.Boolean.TRUE)
    capabilities.setReferencesProvider(java.lang.Boolean.TRUE)
    val info = new ServerInfo("almond", almond.api.Properties.version)
    CompletableFuture.completedFuture(new InitializeResult(capabilities, info))
  }

  override def initialized(params: InitializedParams): Unit =
    if (workDoneProgress && tasksListener.isEmpty)
      tasksListener = Some(features.onTasksChange(progress.update))

  // the units of cells change when cells are run, which frontends say nothing about
  private val sourcesListener = sources.onChange { () =>
    for (model <- models; notebook <- model.notebooks)
      publishDiagnostics(model, notebook)
  }

  def shutdown(): CompletableFuture[AnyRef] =
    CompletableFuture.completedFuture(null)

  // Diagnostics are computed in the background
  private val diagnosticsListener = features.onDiagnosticsChange { () =>
    for (model <- models; notebook <- model.notebooks)
      publishDiagnostics(model, notebook)
  }

  def exit(): Unit = {
    sourcesListener.close()
    diagnosticsListener.close()
    tasksListener.foreach(_.close())
  }

  /** Reports the tasks of `features` to the client, as work done progress */
  private object progress {

    /** Tasks reported so far, by identifier, along with their token, and a future that completes
      * once the client knows about that token
      */
    private var reported =
      Map.empty[String, (LspTask, JEither[String, Integer], CompletableFuture[Void])]

    private var tokenCount = 0

    private def notify(token: JEither[String, Integer], value: WorkDoneProgressNotification)
      : Unit =
      for (client <- clientOpt)
        client.notifyProgress(
          new ProgressParams(token, JEither.forLeft[WorkDoneProgressNotification, AnyRef](value))
        )

    def update(tasks: Seq[LspTask]): Unit = synchronized {
      for (client <- clientOpt) {
        val ids = tasks.map(_.id).toSet
        for ((id, (_, token, created)) <- reported if !ids(id)) {
          created.thenRun(() => notify(token, new WorkDoneProgressEnd))
          reported -= id
        }
        for (task <- tasks)
          reported.get(task.id) match {
            case None =>
              tokenCount += 1
              val token = JEither.forLeft[String, Integer](s"almond-$tokenCount")
              // Notifications are only allowed once the client answered
              val created = client
                .createProgress(new WorkDoneProgressCreateParams(token))
                .thenRun { () =>
                  val begin = new WorkDoneProgressBegin
                  begin.setTitle(task.title)
                  begin.setCancellable(false)
                  task.message.foreach(begin.setMessage)
                  notify(token, begin)
                }
              reported += task.id -> (task, token, created)
            case Some((former, token, created)) if former != task =>
              created.thenRun { () =>
                val report = new WorkDoneProgressReport
                report.setMessage(task.message.getOrElse(""))
                notify(token, report)
              }
              reported += task.id -> (task, token, created)
            case Some(_) =>
          }
      }
    }
  }

  def getTextDocumentService: TextDocumentService = textDocuments
  def getWorkspaceService: WorkspaceService       = workspace

  /** Files other than notebooks opened by the client, by client URI */
  private val openedFiles = TrieMap.empty[String, (FileFeatures, os.Path)]

  /** Prefix of the client URIs of the files under `.lsp_symlink`, once we saw one */
  @volatile private var lspSymlinkPrefix = Option.empty[String]

  /** The file of a client URI, if it's one that `features` handles
    *
    * JupyterLab only opens files under its root directory, so jupyterlab-lsp opens the other ones
    * via a `.lsp_symlink` symbolic link to `/` there, and sends us URIs like
    * `file:///<root>/.lsp_symlink/<absolute path>`.
    */
  private def fileOf(uri: String): Option[(FileFeatures, os.Path)] =
    for {
      fileFeatures <- features.files
      uri0         <- Try(new java.net.URI(uri)).toOption
      if uri0.getScheme == "file"
      path = uri0.getPath
      idx  = path.indexOf(lspSymlinkSegment)
      realPath <- Try {
        if (idx >= 0) os.Path(path.substring(idx + lspSymlinkSegment.length - 1))
        else os.Path(path)
      }.toOption
      if fileFeatures.accepts(realPath)
    } yield {
      val rawIdx = uri.indexOf(lspSymlinkSegment)
      if (rawIdx >= 0 && lspSymlinkPrefix.isEmpty)
        lspSymlinkPrefix = Some(uri.substring(0, rawIdx + lspSymlinkSegment.length - 1))
      (fileFeatures, realPath)
    }

  /** Client URI of a file, via `.lsp_symlink` if the client opened files that way */
  private def clientUri(path: os.Path): String =
    lspSymlinkPrefix match {
      case Some(prefix) => prefix + path.toNIO.toUri.getRawPath
      case None         => path.toNIO.toUri.toASCIIString
    }

  /** Client location of a location returned by the features for a file other than notebooks */
  private def fileClientLocation(fileFeatures: FileFeatures, location: SourceLocation)
    : Option[Location] =
    location match {
      case SourceLocation.InUnit(UnitRange(unit, range)) =>
        val inNotebooks = models.iterator
          .flatMap(model => model.notebooks.iterator.map(model -> _))
          .flatMap { case (model, notebook) => clientLocation(model, notebook, unit, range) }
        inNotebooks.find(_ => true).orElse {
          // units of notebooks this session doesn't know about are pointed to via their file
          val pathOpt = Try(os.Path(java.nio.file.Paths.get(new java.net.URI(unit.source.uri))))
            .toOption
          Some(new Location(pathOpt.fold(unit.source.uri)(clientUri), range))
        }
      case SourceLocation.External(location) =>
        val pathOpt = Try(os.Path(java.nio.file.Paths.get(new java.net.URI(location.getUri))))
          .toOption
          .filter(fileFeatures.accepts)
        Some(pathOpt.fold(location)(path => new Location(clientUri(path), location.getRange)))
    }

  private def onFile[T](document: TextDocumentIdentifier)(
    f: (FileFeatures, os.Path) => CompletableFuture[T]
  ): Option[CompletableFuture[T]] =
    openedFiles.get(document.getUri).map { case (fileFeatures, path) => f(fileFeatures, path) }

  private object textDocuments extends TextDocumentService {

    def didOpen(params: DidOpenTextDocumentParams): Unit = {
      val item = params.getTextDocument
      fileOf(item.getUri) match {
        case Some((fileFeatures, path)) =>
          openedFiles(item.getUri) = (fileFeatures, path)
          fileFeatures.didOpen(path, item.getText)
        case None =>
          synced(models.iterator.map(m => m.didOpen(params).map(m -> _)))
      }
    }

    def didChange(params: DidChangeTextDocumentParams): Unit =
      openedFiles.get(params.getTextDocument.getUri) match {
        case Some((fileFeatures, path)) =>
          // we ask for full text changes
          for (change <- params.getContentChanges.asScala.lastOption)
            fileFeatures.didChange(path, change.getText)
        case None =>
          synced(models.iterator.map(m => m.didChange(params).map(m -> _)))
      }

    def didClose(params: DidCloseTextDocumentParams): Unit =
      openedFiles.remove(params.getTextDocument.getUri) match {
        case Some((fileFeatures, path)) =>
          fileFeatures.didClose(path)
        case None =>
          didCloseNotebook(params)
      }

    private def didCloseNotebook(params: DidCloseTextDocumentParams): Unit =
      for {
        uri    <- models.iterator.map(_.didClose(params)).collectFirst { case Some(uri) => uri }
        client <- clientOpt
      }
        client.publishDiagnostics(new PublishDiagnosticsParams(
          uri,
          Collections.emptyList[Diagnostic]()
        ))

    def didSave(params: DidSaveTextDocumentParams): Unit = ()

    override def completion(params: CompletionParams)
      : CompletableFuture[JEither[JList[CompletionItem], CompletionList]] =
      // the files we handle besides notebooks are read-only sources
      if (openedFiles.contains(params.getTextDocument.getUri))
        CompletableFuture.completedFuture(emptyCompletions)
      else onCell(params.getTextDocument, params.getPosition, emptyCompletions) { at =>
        map(features.completion(at.unitPosition)) { items =>
          val items0 = items.flatMap(at.clientCompletionItem)
          JEither.forRight[JList[CompletionItem], CompletionList](
            new CompletionList(false, items0.asJava)
          )
        }
      }

    override def hover(params: HoverParams): CompletableFuture[Hover] =
      onFile(params.getTextDocument) { (fileFeatures, path) =>
        map(fileFeatures.hover(path, params.getPosition))(_.orNull)
      }.getOrElse(onCell(params.getTextDocument, params.getPosition, null: Hover) { at =>
        map(features.hover(at.unitPosition)) { hoverOpt =>
          hoverOpt.map { hover =>
            Option(hover.getRange).foreach(r => hover.setRange(at.clientRange(r).orNull))
            hover
          }.orNull
        }
      })

    override def signatureHelp(params: SignatureHelpParams): CompletableFuture[SignatureHelp] =
      onFile(params.getTextDocument) { (fileFeatures, path) =>
        map(fileFeatures.signatureHelp(path, params.getPosition))(_.orNull)
      }.getOrElse(onCell(params.getTextDocument, params.getPosition, null: SignatureHelp) { at =>
        map(features.signatureHelp(at.unitPosition))(_.orNull)
      })

    override def definition(params: DefinitionParams)
      : CompletableFuture[JEither[JList[_ <: Location], JList[_ <: LocationLink]]] =
      onFile(params.getTextDocument) { (fileFeatures, path) =>
        map(fileFeatures.definition(path, params.getPosition)) { locations =>
          JEither.forLeft[JList[_ <: Location], JList[_ <: LocationLink]](
            locations.flatMap(fileClientLocation(fileFeatures, _)).asJava
          )
        }
      }.getOrElse(onCell(params.getTextDocument, params.getPosition, noDefinitions) { at =>
        map(features.definition(at.unitPosition)) { locations =>
          JEither.forLeft[JList[_ <: Location], JList[_ <: LocationLink]](
            locations.flatMap(at.clientLocation).asJava
          )
        }
      })

    override def references(params: ReferenceParams): CompletableFuture[JList[_ <: Location]] = {
      val includeDeclaration0 = Option(params.getContext).exists(_.isIncludeDeclaration)
      onFile(params.getTextDocument) { (fileFeatures, path) =>
        map[Seq[SourceLocation], JList[_ <: Location]](
          fileFeatures.references(path, params.getPosition, includeDeclaration0)
        ) { locations =>
          locations.flatMap(fileClientLocation(fileFeatures, _)).asJava
        }
      }.getOrElse(onCell[JList[_ <: Location]](
        params.getTextDocument,
        params.getPosition,
        Collections.emptyList[Location]()
      ) { at =>
        val includeDeclaration = Option(params.getContext).exists(_.isIncludeDeclaration)
        map[Seq[SourceLocation], JList[_ <: Location]](
          features.references(at.unitPosition, includeDeclaration)
        ) { locations =>
          locations.flatMap(at.clientLocation).asJava
        }
      })
    }
  }

  private object workspace extends WorkspaceService {
    def didChangeConfiguration(params: DidChangeConfigurationParams): Unit = ()
    def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit   = ()
  }

  private def synced(updates: Iterator[Option[(NotebookDocumentModel, Notebook)]]): Unit =
    for ((model, notebook) <- updates.collectFirst { case Some(update) => update })
      publishDiagnostics(model, notebook)

  private def publishDiagnostics(model: NotebookDocumentModel, notebook: Notebook): Unit =
    for (client <- clientOpt) {
      val units = sources.units(notebook)
      features.diagnostics(notebook.uri, units).thenAccept { diagnostics =>
        val isCurrent = model.notebooks.exists { n =>
          n.uri == notebook.uri && n.version == notebook.version
        }
        if (isCurrent) {
          val byUri = diagnostics
            .flatMap {
              case (unit, diagnostic) =>
                clientLocation(model, notebook, unit, diagnostic.getRange).map { location =>
                  // a copy, as features may keep their diagnostics, and return them again later
                  location.getUri -> withRange(diagnostic, location.getRange)
                }
            }
            .groupBy(_._1)
          for (uri <- model.documentUris(notebook)) {
            val uriDiagnostics = byUri.getOrElse(uri, Nil).map(_._2)
            client.publishDiagnostics(
              new PublishDiagnosticsParams(uri, uriDiagnostics.asJava, Int.box(notebook.version))
            )
          }
        }
      }
    }

  /** Client location of a range of a unit, if the unit is a cell of `notebook` */
  private def clientLocation(
    model: NotebookDocumentModel,
    notebook: Notebook,
    unit: SourceUnit,
    range: Range
  ): Option[Location] =
    sources.cell(notebook, unit)
      .flatMap(cell => model.location(notebook, CellRange(cell, range)))

  /** Runs `f` on the cell of a client position, or returns `default` if it's not in a cell */
  private def onCell[T](document: TextDocumentIdentifier, position: Position, default: => T)(
    f: Cell => CompletableFuture[T]
  ): CompletableFuture[T] = {
    val cellOpt = models.iterator
      .map { model =>
        model.cellPosition(document, position).map {
          case (notebook, cellPos) => Cell(model, notebook, cellPos.cell, cellPos.position)
        }
      }
      .collectFirst { case Some(cell) => cell }
    cellOpt match {
      case Some(cell) => f(cell)
      case None =>
        log.debug(s"No cell at ${document.getUri}:$position")
        CompletableFuture.completedFuture(default)
    }
  }

  /** A position in a cell, with conversions between client and source unit positions */
  private final case class Cell(
    model: NotebookDocumentModel,
    notebook: Notebook,
    cell: Int,
    position: Position
  ) {
    lazy val unit: SourceUnit = sources.unit(notebook, cell)

    /** Cells and units have the same code, so positions are the same in both */
    def unitPosition: UnitPosition = UnitPosition(unit, position)

    /** Client range of a range of the unit of this cell */
    def clientRange(range: Range): Option[Range] =
      AlmondLanguageServer.this.clientLocation(model, notebook, unit, range).map(_.getRange)

    def clientLocation(location: SourceLocation): Option[Location] =
      location match {
        case SourceLocation.InUnit(UnitRange(unit, range)) =>
          // units whose cell isn't in this notebook are pointed to via their own file
          AlmondLanguageServer.this.clientLocation(model, notebook, unit, range)
            .orElse(Some(new Location(unit.source.uri, range)))
        case SourceLocation.External(location) =>
          Some(location)
      }

    /** Completion item with its edits converted, if they can all be */
    def clientCompletionItem(item: CompletionItem): Option[CompletionItem] = {
      val mainEditOk = Option(item.getTextEdit).forall { edit =>
        if (edit.isLeft) {
          val range = clientRange(edit.getLeft.getRange)
          range.foreach(edit.getLeft.setRange)
          range.nonEmpty
        }
        else {
          val insert  = clientRange(edit.getRight.getInsert)
          val replace = clientRange(edit.getRight.getReplace)
          insert.foreach(edit.getRight.setInsert)
          replace.foreach(edit.getRight.setReplace)
          insert.nonEmpty && replace.nonEmpty
        }
      }
      val additionalEdits = Option(item.getAdditionalTextEdits).map(_.asScala.toList)
      val additionalEditsOk = additionalEdits.forall(_.forall { edit =>
        val range = clientRange(edit.getRange)
        range.foreach(edit.setRange)
        range.nonEmpty
      })
      if (mainEditOk && additionalEditsOk) Some(item) else None
    }
  }
}

object AlmondLanguageServer {

  private def lspSymlinkSegment = "/.lsp_symlink/"

  private def emptyCompletions: JEither[JList[CompletionItem], CompletionList] =
    JEither.forRight(new CompletionList(false, Collections.emptyList[CompletionItem]()))

  private def noDefinitions: JEither[JList[_ <: Location], JList[_ <: LocationLink]] =
    JEither.forLeft(Collections.emptyList[Location]())

  private def map[A, B](future: CompletableFuture[A])(f: A => B): CompletableFuture[B] =
    future.thenApply[B](a => f(a))

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
}
