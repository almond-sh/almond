package almond.lsp

/** Frontend-independent view of a notebook: its code cells, in order
  *
  * @param uri
  *   URI identifying the notebook in LSP messages
  */
final case class Notebook(uri: String, version: Int, cells: Vector[CodeCell])
