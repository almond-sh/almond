package almond.lsp

import org.eclipse.lsp4j.{Hover, Position, SignatureHelp}

import java.util.concurrent.CompletableFuture

/** Language features on files that aren't cells, like the sources of dependencies that definitions
  * lead to
  *
  * Positions are those of the files themselves.
  */
trait FileFeatures {

  /** Directories whose files these features handle */
  def roots: Seq[os.Path]

  def accepts(path: os.Path): Boolean =
    roots.exists(path.startsWith)

  def didOpen(path: os.Path, text: String): Unit
  def didChange(path: os.Path, text: String): Unit
  def didClose(path: os.Path): Unit

  def hover(path: os.Path, position: Position): CompletableFuture[Option[Hover]]
  def signatureHelp(path: os.Path, position: Position): CompletableFuture[Option[SignatureHelp]]
  def definition(path: os.Path, position: Position): CompletableFuture[Seq[SourceLocation]]
  def references(
    path: os.Path,
    position: Position,
    includeDeclaration: Boolean
  ): CompletableFuture[Seq[SourceLocation]]
}
