package almond.lsp

import org.eclipse.lsp4j._

/** How a frontend represents notebooks in LSP messages
  *
  * Converts the documents and positions of LSP messages to and from [[Notebook]]s and cell
  * positions. Instances keep track of the notebooks opened by a client.
  *
  * Synchronization methods return `None` for messages that aren't about documents of this model.
  * jupyterlab-lsp sends `textDocument/...` messages about a single document per notebook (see
  * [[JupyterLabDocumentModel]]), while the LSP notebook support (used by VS Code) sends
  * `notebookDocument/...` messages, with one text document per cell.
  */
trait NotebookDocumentModel {

  def didOpen(params: DidOpenTextDocumentParams): Option[Notebook]         = None
  def didChange(params: DidChangeTextDocumentParams): Option[Notebook]     = None
  def didClose(params: DidCloseTextDocumentParams): Option[String]         = None
  def didOpen(params: DidOpenNotebookDocumentParams): Option[Notebook]     = None
  def didChange(params: DidChangeNotebookDocumentParams): Option[Notebook] = None
  def didClose(params: DidCloseNotebookDocumentParams): Option[String]     = None

  /** The notebooks opened by the client */
  def notebooks: Seq[Notebook]

  /** URIs of the client documents of a notebook, those diagnostics are published for */
  def documentUris(notebook: Notebook): Seq[String]

  /** Notebook and cell position of a position sent by the client
    *
    * @return
    *   `None` if the document doesn't belong to this model, or if the position isn't in a cell
    */
  def cellPosition(
    document: TextDocumentIdentifier,
    position: Position
  ): Option[(Notebook, CellPosition)]

  /** Client document and range of a range in a cell */
  def location(notebook: Notebook, range: CellRange): Option[Location]
}
