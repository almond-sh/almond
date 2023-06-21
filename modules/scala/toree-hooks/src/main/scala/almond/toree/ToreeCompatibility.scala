package almond.toree

import almond.interpreter.api.DisplayData
import ammonite.interp.api.InterpAPI

import java.io.{InputStream, PrintStream}
import java.net.URI
import java.nio.file.Paths

/** Import the members of this object to add source-compatibility for some Toree API calls, such as
  * 'kernel.display', or 'kernel.addJars'.
  */
object ToreeCompatibility {
  implicit class KernelToreeOps(private val kernel: almond.api.JupyterApi) {

    def display: ToreeDisplayMethodsLike =
      new ToreeDisplayMethodsLike {
        def content(mimeType: String, data: String) =
          kernel.publish.display(DisplayData(Map(mimeType -> data)))
        def clear(wait: Boolean = false) =
          // no-op, not sure what we're supposed to do here…
          ()
      }

    def out: PrintStream = System.out
    def err: PrintStream = System.err
    def in: InputStream  = System.in

    def addJars(uris: URI*)(implicit interp: InterpAPI): Unit = {
      val (fileUris, other) = uris.partition(_.getScheme == "file")
      for (uri <- other)
        System.err.println(s"Warning: ignoring $uri")
      val files = fileUris.map(Paths.get(_)).map(os.Path(_, os.pwd))
      interp.load.cp(files)
    }
  }
}
