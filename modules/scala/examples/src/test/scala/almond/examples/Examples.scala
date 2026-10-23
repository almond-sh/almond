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
      ExampleProperties.repoRoot.toNIO.toUri.toASCIIString
    ).call(
      cwd = ExampleProperties.directory,
      env = ExampleProperties.jvmEnv ++ Map("ALMOND_USE_RANDOM_IDS" -> "false")
    )

    path
  }

  def update = sys.props.get("almond.examples.update").contains("true")

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

  private def scalaVersion       = ExampleProperties.scalaVersion
  private def isReferenceVersion = scalaVersion == ExampleProperties.referenceScalaVersion

  /** Copies of the example notebooks for our binary Scala version, if it's not the reference one.
    *
    * The example notebooks in [[ExampleProperties.directory]] are run with, and record the outputs
    * of, the reference Scala version. With other Scala versions, the notebooks that don't run the
    * same way (different outputs, or sources that need to be adapted) have a copy in this
    * directory, that's run and compared with instead.
    */
  private lazy val variantDir = {
    val binaryVersion =
      if (scalaVersion.startsWith("3.")) "3"
      else scalaVersion.split('.').take(2).mkString(".")
    ExampleProperties.directory / s"scala-$binaryVersion"
  }
  private lazy val variants =
    if (isReferenceVersion || !os.isDir(variantDir)) Nil
    else os.list(variantDir).filter(_.last.endsWith(".ipynb")).filter(os.isFile(_))

  private def withLineSep(notebook: os.Path): os.Path =
    if (shouldUpdateLineSep) {
      val dest = inputDir / notebook.relativeTo(ExampleProperties.directory)
      os.write.over(dest, updateLineSep(os.read(notebook)), createFolders = true)
      dest
    }
    else
      notebook

  /** Renders an executed notebook like the ones we commit, recording `scalaVersion` as version */
  private def render(json: ujson.Value, scalaVersion: String): String = {
    json("metadata")("language_info")("version") = scalaVersion
    val rendered = json.render(1)
    if (Properties.isWin) rendered.replace("\n", "\r\n")
    else rendered
  }

  private def recordedScalaVersion(notebook: os.Path): String =
    ujson.read(os.read(notebook))("metadata")("language_info")("version").str

  test("variants have a reference notebook") {
    val orphans = variants.filter(variant => !os.exists(ExampleProperties.directory / variant.last))
    assert(
      orphans.isEmpty,
      s"Found notebooks in $variantDir with no counterpart in ${ExampleProperties.directory}: " +
        orphans.map(_.last).mkString(", ")
    )
  }

  for (notebook <- notebooks)
    test(notebook.last.stripSuffix(".ipynb")) {

      val variant = variantDir / notebook.last
      val source  = if (variants.contains(variant)) variant else notebook
      val input   = withLineSep(source)
      val output  = outputDir / notebook.last
      // Run jupyter from the uv-managed environment described by examples/pyproject.toml:
      // uv (downloaded by the build) fetches Python and the pinned Jupyter packages from
      // examples/uv.lock on the fly, so nothing needs to be installed beforehand.
      os.proc(
        ExampleProperties.uv,
        "run",
        "--project",
        ExampleProperties.directory,
        "--frozen",
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
        // Jupyter starts the kernel with the java command of that JVM
        env = ExampleProperties.jvmEnv ++ Map(
          "JUPYTER_PATH"          -> jupyterPath.toString,
          "ALMOND_USE_RANDOM_IDS" -> "false"
        ),
        stdin = os.Inherit,
        stdout = os.Inherit,
        stderr = os.Inherit
      )

      if (!os.exists(output)) {
        val otherOutput = output / os.up / s"${output.last.stripSuffix(".ipynb")}.nbconvert.ipynb"
        if (os.exists(otherOutput))
          os.move(otherOutput, output)
      }
      val rawOutput = os.read(output, Charset.defaultCharset())

      // Clear metadata, that usually looks like
      // "metadata": {
      //  "execution": {
      //   "iopub.execute_input": "2022-08-17T10:35:13.619221Z",
      //   "iopub.status.busy": "2022-08-17T10:35:13.614065Z",
      //   "iopub.status.idle": "2022-08-17T10:35:16.310834Z",
      //   "shell.execute_reply": "2022-08-17T10:35:16.311111Z"
      //  }
      // }
      val json = ujson.read(rawOutput)
      for (cell <- json("cells").arr if cell("cell_type").str == "code")
        cell("metadata") = ujson.Obj()

      // writing the updated notebook on disk for the diff below
      os.write.over(output, render(json, scalaVersion).getBytes(Charset.defaultCharset()))

      // The notebook we compare with may have been recorded with another Scala version: the
      // reference one (when we're not running with it), or, for variants, another patch version
      // of ours, that we may have been bumped from since.
      val result =
        if (isReferenceVersion) os.read(output)
        else {
          val normalized = outputDir / "normalized" / notebook.last
          os.write.over(
            normalized,
            render(json, recordedScalaVersion(source)).getBytes(Charset.defaultCharset()),
            createFolders = true
          )
          os.read(normalized)
        }
      // Whether we get the same outputs as the reference notebook, in which case our Scala version
      // doesn't need a variant of it
      lazy val sameAsReference =
        !isReferenceVersion &&
        render(json, recordedScalaVersion(notebook)) == os.read(withLineSep(notebook))

      val expected = os.read(input)

      if (result != expected) {
        def explicitCrLf(input: String): String =
          input
            .replace("\r", "\\r\r")
            .replace("\n", "\\n\n")
            .replace("\\r\r\\n\n", "\\r\\n\r\n")
        System.err.println(s"${source.relativeTo(ExampleProperties.directory)} differs:")
        System.err.println(s"Expected ${expected.length} chars, got ${result.length}")
        System.err.println()
        val compared = if (isReferenceVersion) output else outputDir / "normalized" / notebook.last
        os.proc("diff", "-u", input, compared)
          .call(cwd = ExampleProperties.directory, check = false, stdin = os.Inherit, stdout = os.Inherit)
        if (update) {
          if (shouldUpdateLineSep)
            System.err.println(
              "Warning: the current system uses CRLF as line separator, " +
              "only notebooks using LF as line separator should be committed."
            )
          if (sameAsReference) {
            System.err.println(s"Removing ${variant.relativeTo(ExampleProperties.directory)}")
            os.remove(variant)
          }
          else {
            val dest = if (isReferenceVersion) notebook else variant
            System.err.println(s"Updating ${dest.relativeTo(ExampleProperties.directory)}")
            os.copy.over(output, dest, createFolders = true)
          }
        }
        sys.error("Output notebook differs from original")
      }

      if (source == variant && sameAsReference) {
        val msg =
          s"${variant.relativeTo(ExampleProperties.directory)} isn't needed anymore, " +
            s"we get the same outputs as ${notebook.relativeTo(ExampleProperties.directory)}"
        if (update) {
          System.err.println(s"$msg, removing it")
          os.remove(variant)
        }
        sys.error(msg)
      }
    }

}
