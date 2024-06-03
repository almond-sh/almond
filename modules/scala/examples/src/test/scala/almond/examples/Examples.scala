package almond.examples

import java.nio.charset.Charset

import scala.concurrent.duration.DurationInt
import scala.util.Properties

class Examples extends munit.FunSuite {

  def kernelId = "almond-examples"

  override def munitTimeout = 5.minutes

  lazy val notebooks = os.list(ExampleProperties.directory)
    .filter(_.last.endsWith(".ipynb"))
    .filter(os.isFile(_))

  lazy val jupyterPath: os.Path = {

    val path = ExampleProperties.jupyterPath

    os.proc(
      ExampleProperties.launcher,
      "--jupyter-path",
      path / "kernels",
      "--id",
      kernelId,
      "--install",
      "--force",
      "--trap-output",
      "--extra-repository",
      s"ivy:${ExampleProperties.repoRoot.toNIO.toUri.toASCIIString}/[defaultPattern]"
    ).call(cwd = ExampleProperties.directory, env = Map("ALMOND_USE_RANDOM_IDS" -> "false"))

    path
  }

  def update = false

  lazy val outputDir = {
    val dir = ExampleProperties.outputDirectory
    os.makeDir.all(dir)
    dir
  }

  lazy val inputDir = outputDir / "input"

  private val nl = System.lineSeparator()
  private lazy val escapedNl =
    if (nl == "\n") "\\n"
    else if (nl == "\r\n") "\\r\\n"
    else ???
  private val shouldUpdateLineSep = System.lineSeparator() == "\r\n"

  private def traverseAndUpdateLineSep(content: ujson.Value, deep: Boolean = false): Option[ujson.Value] =
    content.arrOpt match {
      case Some(arr) =>
        for ((elem, idx) <- arr.zipWithIndex)
          for (updatedElem <- traverseAndUpdateLineSep(elem, deep = deep))
            content(idx) = updatedElem
        None
      case None =>
        content.objOpt match {
          case Some(obj) =>
            for ((k, v) <- obj)
              for (updatedElem <- traverseAndUpdateLineSep(v, deep = deep))
                content(k) = updatedElem
            None
          case None =>
            content.strOpt.map { str =>
              if (deep)
                str
                  .linesWithSeparators
                  .map { line =>
                    if (deep)
                      line.replace("\\n", escapedNl)
                    else
                      line
                  }
                  .mkString
              else
                str
                  .linesWithSeparators
                  .flatMap { line =>
                    if (line.endsWith("\n") && !line.endsWith(nl))
                      Iterator(line.stripSuffix("\n"), nl)
                    else
                      Iterator(line)
                  }
                  .mkString
            }
        }
    }

  private def updateLineSep(content: String): String = {

    val json = ujson.read(content)
    for (cell <- json("cells").arr if cell("cell_type").str == "code") {
      if (cell.obj.contains("outputs"))
        for (output <- cell("outputs").arr if output("output_type").strOpt.exists(s => s == "display_data" || s == "execute_result"))
          for ((k, v) <- output("data").obj) {
            val shouldUpdate =
              (k.startsWith("text/") && !v.arr.exists(_.str.contains("function(Plotly)"))) ||
                k == "application/vnd.plotly.v1+json"
            if (shouldUpdate)
              traverseAndUpdateLineSep(v)
            if ((k == "text/html" && v.arr.exists(_.str.contains("function(Plotly)"))))
              traverseAndUpdateLineSep(v, deep = true)
          }
      if (cell.obj.contains("source"))
        traverseAndUpdateLineSep(cell("source"))
    }

    json.render(1).replace("\n", nl)
  }

  for (notebook <- notebooks)
    test(notebook.last.stripSuffix(".ipynb")) {

      val input =
        if (shouldUpdateLineSep) {
          val dest = inputDir / notebook.last
          val updatedContent = updateLineSep(os.read(notebook))
          os.write.over(dest, updatedContent, createFolders = true)
          dest
        }
        else
          notebook
      val output = outputDir / notebook.last
      val res = os.proc(
        "jupyter",
        "nbconvert",
        "--to",
        "notebook",
        "--execute",
        s"--ExecutePreprocessor.kernel_name=$kernelId",
        input,
        s"--output=$output"
      ).call(
        cwd = ExampleProperties.directory,
        env = Map(
          "JUPYTER_PATH"          -> jupyterPath.toString,
          "ALMOND_USE_RANDOM_IDS" -> "false"
        )
      )

      if (!os.exists(output)) {
        val otherOutput = output / os.up / s"${output.last.stripSuffix(".ipynb")}.nbconvert.ipynb"
        if (os.exists(otherOutput))
          os.move(otherOutput, output)
      }
      val rawOutput = os.read(output, Charset.defaultCharset())

      var updatedOutput = rawOutput

      // Clear metadata, that usually looks like
      // "metadata": {
      //  "execution": {
      //   "iopub.execute_input": "2022-08-17T10:35:13.619221Z",
      //   "iopub.status.busy": "2022-08-17T10:35:13.614065Z",
      //   "iopub.status.idle": "2022-08-17T10:35:16.310834Z",
      //   "shell.execute_reply": "2022-08-17T10:35:16.311111Z"
      //  }
      // }
      val json = ujson.read(updatedOutput)
      for (cell <- json("cells").arr if cell("cell_type").str == "code")
        cell("metadata") = ujson.Obj()
      updatedOutput = json.render(1)

      if (Properties.isWin)
        updatedOutput = updatedOutput.replace("\n", "\r\n")

      val result = updatedOutput

      // writing the updated notebook on disk for the diff below
      os.write.over(output, result.getBytes(Charset.defaultCharset()))

      val expected = os.read(input)

      if (result != expected) {
        def explicitCrLf(input: String): String =
          input
            .replace("\r", "\\r\r")
            .replace("\n", "\\n\n")
            .replace("\\r\r\\n\n", "\\r\\n\r\n")
        System.err.println(s"${notebook.last} differs:")
        System.err.println(s"Expected ${expected.length} chars, got ${result.length}")
        System.err.println()
        os.proc("diff", "-u", input, output)
          .call(cwd = ExampleProperties.directory, check = false, stdin = os.Inherit, stdout = os.Inherit)
        if (update) {
          System.err.println(s"Updating ${notebook.last}")
          if (shouldUpdateLineSep)
            System.err.println(
              "Warning: the current system uses CRLF as line separator, " +
              "only notebooks using LF as line separator should be committed."
            )
          os.copy.over(output, notebook)
        }
        sys.error("Output notebook differs from original")
      }
    }

}
