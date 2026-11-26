package almond.lsp

/** The inputs evaluated in a session, and how they relate to the cells of notebooks
  *
  * Cells are matched with evaluated inputs having the same code, using the cell id or execution
  * count when the document model provides them, else picking the latest input with that code. Cells
  * with no such input become [[SourceUnit.Pending]] units, that know about the pending cells before
  * them.
  */
final class SessionSources(layout: SourceLayout) {

  @volatile private var inputs0 = Vector.empty[EvaluatedInput]

  private val listeners = new java.util.concurrent.CopyOnWriteArrayList[() => Unit]

  /** Calls `listener` whenever an input is added, until the returned value is closed */
  def onChange(listener: () => Unit): AutoCloseable = {
    listeners.add(listener)
    () => listeners.remove(listener)
  }

  def inputs: Vector[EvaluatedInput] = inputs0

  /** Records an input successfully compiled by the kernel
    *
    * @param source
    *   the file the code of the input was written to, if it was written somewhere
    */
  def add(
    executionCount: Int,
    wrapperName: String,
    code: String,
    cellId: Option[String],
    source: Option[SourceFile] = None
  ): Unit =
    synchronized {
      val source0 = source.getOrElse(layout.evaluated(wrapperName))
      inputs0 = inputs0 :+ EvaluatedInput(executionCount, wrapperName, code, cellId, source0)
      listeners.forEach(_())
    }

  /** The input whose code is that of a cell, if any */
  private def input(notebookCell: CodeCell): Option[EvaluatedInput] = {
    val candidates = inputs0.reverseIterator.filter(_.code == notebookCell.code).toVector
    notebookCell.id.flatMap(id => candidates.find(_.cellId.contains(id)))
      .orElse(notebookCell.executionCount.flatMap(n => candidates.find(_.executionCount == n)))
      .orElse(
        if (notebookCell.id.isEmpty && notebookCell.executionCount.isEmpty) candidates.headOption
        else None
      )
  }

  /** The units of the first `count` cells of `notebook` */
  private def units(notebook: Notebook, count: Int): Vector[SourceUnit] = {
    val units    = Vector.newBuilder[SourceUnit]
    var previous = Vector.empty[SourceUnit.Pending]
    for ((notebookCell, cell) <- notebook.cells.iterator.zipWithIndex.take(count))
      input(notebookCell) match {
        case Some(input) =>
          units += SourceUnit.Evaluated(input)
        case None =>
          val unit = SourceUnit.Pending(
            notebook.uri,
            cell,
            notebookCell.code,
            layout.pending(notebook.uri, cell),
            previous
          )
          previous = previous :+ unit
          units += unit
      }
    units.result()
  }

  def unit(notebook: Notebook, cell: Int): SourceUnit = {
    notebook.cells(cell) // throws if there's no such cell
    units(notebook, cell + 1).last
  }

  def units(notebook: Notebook): Vector[SourceUnit] =
    units(notebook, notebook.cells.length)

  /** The cell of `notebook` whose source unit is `unit`, if any */
  def cell(notebook: Notebook, unit: SourceUnit): Option[Int] =
    unit match {
      case p: SourceUnit.Pending =>
        Some(p.cell).filter { idx =>
          p.notebookUri == notebook.uri && notebook.cells.lift(idx).exists(_.code == p.code)
        }
      case e: SourceUnit.Evaluated =>
        Some(units(notebook).indexOf(e)).filter(_ >= 0)
    }

}
