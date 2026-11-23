package almond.lsp

/** A source file of the session, laid out like the Ammonite session directories
  *
  * Like Ammonite's `FrameOutputs`, each input gets its own file, with its user code as is (`.sc`),
  * and possibly a file with the code actually passed to the compiler (`.scala`), that is the user
  * code wrapped by Ammonite.
  *
  * @param uri
  *   URI of the file with the user code
  * @param wrapped
  *   details about the code passed to the compiler, if it wraps the user code
  */
final case class SourceFile(uri: String, wrapped: Option[WrappedSource])
