package almond.lsp

/** An input evaluated by the kernel, that is a cell in Almond
  *
  * @param wrapperName
  *   name of the wrapper of that input, like `cmd3`
  * @param cellId
  *   id of the cell this input comes from, if the frontend sent it
  */
final case class EvaluatedInput(
  executionCount: Int,
  wrapperName: String,
  code: String,
  cellId: Option[String],
  source: SourceFile
)
