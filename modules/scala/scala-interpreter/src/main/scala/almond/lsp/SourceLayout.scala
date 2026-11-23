package almond.lsp

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Where the files of the inputs of a session are
  *
  * Mirrors the `sources` directory of the frames of Ammonite session directories, with an
  * additional directory for code that wasn't evaluated.
  */
final case class SourceLayout(root: os.Path, pkgName: Seq[String]) {

  def evaluated(wrapperName: String): SourceFile =
    SourceFile(uri(root / "sources" / pkgName / s"$wrapperName.sc"), None)

  def pending(notebookUri: String, cell: Int): SourceFile =
    SourceFile(uri(root / "pending" / SourceLayout.hash(notebookUri) / s"cell$cell.sc"), None)

  private def uri(path: os.Path): String =
    path.toNIO.toUri.toASCIIString
}

object SourceLayout {

  /** A short hash of `s`, usable in file names */
  def hash(s: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(s.getBytes(StandardCharsets.UTF_8))
      .take(8)
      .map(b => f"${b & 0xff}%02x")
      .mkString
}
