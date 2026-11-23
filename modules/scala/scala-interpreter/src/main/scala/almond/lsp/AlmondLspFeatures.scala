package almond.lsp

import org.eclipse.lsp4j.{CompletionItem, Diagnostic, Hover, SignatureHelp}

import java.util.concurrent.CompletableFuture

/** Language features, computed on source units
  *
  * Positions and ranges, including those of the returned LSP objects (text edits of completion
  * items, hover and diagnostic ranges), are relative to the user code of units.
  */
trait AlmondLspFeatures {

  /** Completion items, whose edits apply to the unit of `at` */
  def completion(at: UnitPosition): CompletableFuture[Seq[CompletionItem]]

  def hover(at: UnitPosition): CompletableFuture[Option[Hover]]

  def signatureHelp(at: UnitPosition): CompletableFuture[Option[SignatureHelp]]

  def definition(at: UnitPosition): CompletableFuture[Seq[SourceLocation]]

  def references(at: UnitPosition, includeDeclaration: Boolean)
    : CompletableFuture[Seq[SourceLocation]]

  /** Diagnostics of the units of a notebook, `units` being the units of its cells, in order
    *
    * Called whenever the notebook changes.
    */
  def diagnostics(
    notebookUri: String,
    units: Seq[SourceUnit]
  ): CompletableFuture[Seq[(SourceUnit, Diagnostic)]]

  /** Features on files that aren't cells, if these are handled */
  def files: Option[FileFeatures] = None

  /** Calls `listener` whenever diagnostics might have changed, until the returned value is closed
    *
    * For features that compute diagnostics in the background, after the code changes.
    */
  def onDiagnosticsChange(listener: () => Unit): AutoCloseable =
    () => ()

  /** Calls `listener` with the tasks in progress worth showing to users, now and whenever they
    * change, until the returned value is closed
    */
  def onTasksChange(listener: Seq[LspTask] => Unit): AutoCloseable = {
    listener(Nil)
    () => ()
  }
}

object AlmondLspFeatures {

  /** Placeholder, computing nothing */
  object Unimplemented extends AlmondLspFeatures {
    private def done[T](value: T): CompletableFuture[T] =
      CompletableFuture.completedFuture(value)

    // TODO Compile the units with the session class path, and compute actual results
    def completion(at: UnitPosition): CompletableFuture[Seq[CompletionItem]] =
      done(Nil)
    def hover(at: UnitPosition): CompletableFuture[Option[Hover]] =
      done(None)
    def signatureHelp(at: UnitPosition): CompletableFuture[Option[SignatureHelp]] =
      done(None)
    def definition(at: UnitPosition): CompletableFuture[Seq[SourceLocation]] =
      done(Nil)
    def references(
      at: UnitPosition,
      includeDeclaration: Boolean
    ): CompletableFuture[Seq[SourceLocation]] =
      done(Nil)
    def diagnostics(
      notebookUri: String,
      units: Seq[SourceUnit]
    ): CompletableFuture[Seq[(SourceUnit, Diagnostic)]] =
      done(Nil)
  }
}
