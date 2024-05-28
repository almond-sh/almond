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

  for (notebook <- notebooks)
    test(notebook.last.stripSuffix(".ipynb")) {

      val output = outputDir / notebook.last
      val res = os.proc(
        "jupyter",
        "nbconvert",
        "--to",
        "notebook",
        "--execute",
        s"--ExecutePreprocessor.kernel_name=$kernelId",
        notebook,
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
      if (Properties.isWin)
        updatedOutput = updatedOutput.replace("\r\n", "\n").replace("\\r\\n", "\\n")

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

      // writing the updated notebook on disk for the diff below
      os.write.over(output, updatedOutput.getBytes(Charset.defaultCharset()))

      val result   = os.read(output, Charset.defaultCharset())
      val expected = os.read(notebook)

      if (result != expected) {
        System.err.println(s"${notebook.last} differs:")
        System.err.println()
        os.proc("diff", "-u", notebook, output)
          .call(cwd = ExampleProperties.directory, check = false, stdin = os.Inherit, stdout = os.Inherit)
        if (update) {
          System.err.println(s"Updating ${notebook.last}")
          os.copy.over(output, notebook)
        }
        sys.error("Output notebook differs from original")
      }
    }

}
