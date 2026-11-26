package almond.lsp

/** Code language features are computed on, for a cell */
sealed abstract class SourceUnit extends Product with Serializable {
  def code: String
  def source: SourceFile
}

object SourceUnit {

  /** A cell whose code is that of an input evaluated by the kernel */
  final case class Evaluated(input: EvaluatedInput) extends SourceUnit {
    def code   = input.code
    def source = input.source
  }

  /** A cell that wasn't evaluated, or whose code changed since then
    *
    * Its code should be handled as if it were the next input of the session, run after the pending
    * cells before it.
    *
    * @param previous
    *   the pending cells before this one in the notebook, in order, whose definitions this cell
    *   should see, like it would if they were run first
    */
  final case class Pending(
    notebookUri: String,
    cell: Int,
    code: String,
    source: SourceFile,
    previous: Seq[Pending] = Nil
  ) extends SourceUnit
}
