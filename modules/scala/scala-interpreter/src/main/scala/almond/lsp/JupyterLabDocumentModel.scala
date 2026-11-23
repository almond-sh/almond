package almond.lsp

import org.eclipse.lsp4j._

import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters._

/** Document model of jupyterlab-lsp
  *
  * jupyterlab-lsp sends a single text document per notebook (its "virtual document"), identified by
  * the notebook URI, made of the code of all its cells. See [[VirtualDocumentLayout]] for how cells
  * are laid out in it.
  *
  * @param cellSeparatorPrefix
  *   prefix of the lines put before each cell by JupyterLab, if it supports them (we ask for them
  *   in `kernel_info_reply`, see [[almond.protocol.KernelInfo.Lsp]])
  */
final class JupyterLabDocumentModel(
  cellSeparatorPrefix: String = JupyterLabDocumentModel.cellSeparatorPrefix
) extends NotebookDocumentModel {

  import JupyterLabDocumentModel._

  private val documents = TrieMap.empty[String, Document]

  override def didOpen(params: DidOpenTextDocumentParams): Option[Notebook] = {
    val item = params.getTextDocument
    Some(update(item.getUri, item.getVersion, item.getText))
  }

  override def didChange(params: DidChangeTextDocumentParams): Option[Notebook] = {
    val uri = params.getTextDocument.getUri
    documents.get(uri).map { former =>
      val text = params.getContentChanges.asScala.foldLeft(former.text)(TextChanges.apply)
      update(uri, params.getTextDocument.getVersion, text)
    }
  }

  override def didClose(params: DidCloseTextDocumentParams): Option[String] = {
    val uri = params.getTextDocument.getUri
    documents.remove(uri).map(_ => uri)
  }

  def notebooks: Seq[Notebook] =
    documents.values.map(_.notebook).toVector

  def documentUris(notebook: Notebook): Seq[String] =
    Seq(notebook.uri)

  def cellPosition(
    document: TextDocumentIdentifier,
    position: Position
  ): Option[(Notebook, CellPosition)] =
    for {
      doc     <- documents.get(document.getUri)
      cellPos <- doc.layout.cellPosition(position)
    } yield (doc.notebook, cellPos)

  def location(notebook: Notebook, range: CellRange): Option[Location] =
    documents.get(notebook.uri)
      .filter(_.notebook.version == notebook.version)
      .map(doc => new Location(notebook.uri, doc.layout.range(range)))

  private def update(uri: String, version: Int, text: String): Notebook = {
    val (cells, layout) = VirtualDocumentLayout.splitWithHeaders(text, cellSeparatorPrefix)
      .getOrElse(VirtualDocumentLayout.split(text))
    val notebook = Notebook(uri, version, cells)
    documents(uri) = Document(text, notebook, layout)
    notebook
  }
}

object JupyterLabDocumentModel {

  /** Prefix of the lines we ask JupyterLab to put before each cell
    *
    * A line comment in Scala, so that the virtual document is still valid code.
    */
  def cellSeparatorPrefix: String = "//!!"
  private final case class Document(text: String, notebook: Notebook, layout: VirtualDocumentLayout)
}
