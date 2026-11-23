package almond.lsp

/** A code cell of a notebook, as seen by the language server
  *
  * @param code
  *   source of the cell
  * @param id
  *   id of the cell, if the frontend document model provides it
  * @param executionCount
  *   execution count of the cell, if the frontend document model provides it
  */
final case class CodeCell(
  code: String,
  id: Option[String] = None,
  executionCount: Option[Int] = None
)
