package almond.kernel.install

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.Locale
import java.util.concurrent.TimeUnit

import almond.kernel.util.OS
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

import scala.util.control.NonFatal

/** Directories that a Jupyter installation relies on, as reported by the `jupyter` command
  *
  * @param jupyter
  *   the `jupyter` executable these directories were obtained from
  * @param dataDir
  *   the Jupyter data directory of the current user (`jupyter --data-dir`)
  * @param dataPaths
  *   the data directories Jupyter looks into, by decreasing priority (`jupyter --paths`)
  */
final case class JupyterDirectories(
  jupyter: Path,
  dataDir: Path,
  dataPaths: Seq[Path]
) {

  /** Directory where `jupyter kernelspec install --user` installs kernels */
  def userKernelsDir: Path =
    dataDir.resolve("kernels")

  /** Directories Jupyter looks for kernels into, by decreasing priority */
  def kernelsDirs: Seq[Path] =
    dataPaths.map(_.resolve("kernels"))
}

object JupyterDirectories {

  def defaultCommand: String = "jupyter"

  private final case class PathsOutput(data: List[String] = Nil)
  private implicit lazy val pathsOutputCodec: JsonValueCodec[PathsOutput] =
    JsonCodecMaker.make

  /** Looks for an executable in the `PATH`, like a shell would */
  private def findInPath(name: String): Option[Path] = {
    val extensions =
      if (OS.current == OS.Windows)
        sys.env
          .get("PATHEXT")
          .getOrElse(".COM;.EXE;.BAT;.CMD")
          .split(";")
          .toSeq
          .map(_.trim)
          .filter(_.nonEmpty)
      else
        Seq("")
    val pathDirs = sys.env
      .get("PATH")
      .orElse(sys.env.get("Path"))
      .toSeq
      .flatMap(_.split(java.util.regex.Pattern.quote(File.pathSeparator)))
      .filter(_.nonEmpty)
    val candidates =
      for {
        dir <- pathDirs.iterator
        ext <- extensions.iterator
        name0 =
          if (ext.isEmpty || name.toLowerCase(Locale.ROOT).endsWith(ext.toLowerCase(Locale.ROOT)))
            name
          else
            name + ext
        candidate = Paths.get(dir).resolve(name0)
        if Files.isRegularFile(candidate) && Files.isExecutable(candidate)
      } yield candidate.toAbsolutePath
    candidates.find(_ => true)
  }

  private def executable(command: String): Either[String, Path] =
    if (command.contains("/") || command.contains(File.separator)) {
      val path = Paths.get(command).toAbsolutePath
      if (Files.isRegularFile(path)) Right(path)
      else Left(s"$path not found")
    }
    else
      findInPath(command).toRight(s"$command not found in PATH")

  private def run(
    jupyter: Path,
    args: Seq[String],
    timeoutSeconds: Long
  ): Either[String, String] = {
    val command      = (jupyter.toString +: args).mkString(" ")
    var stdout: Path = null
    var stderr: Path = null
    try {
      stdout = Files.createTempFile("almond-jupyter-stdout", ".txt")
      stderr = Files.createTempFile("almond-jupyter-stderr", ".txt")
      val builder = new ProcessBuilder((jupyter.toString +: args): _*)
        .redirectInput(ProcessBuilder.Redirect.INHERIT)
        .redirectOutput(stdout.toFile)
        .redirectError(stderr.toFile)
      // jupyter --data-dir prints paths as is, ensure non-ASCII characters
      // don't depend on the Windows code page
      builder.environment().put("PYTHONIOENCODING", "utf-8")
      val proc = builder.start()
      if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        proc.destroyForcibly()
        Left(s"'$command' didn't complete within $timeoutSeconds seconds")
      }
      else {
        val exitCode = proc.exitValue()
        if (exitCode == 0)
          Right(new String(Files.readAllBytes(stdout), StandardCharsets.UTF_8))
        else {
          val errOutput = new String(Files.readAllBytes(stderr), StandardCharsets.UTF_8).trim
          val details =
            if (errOutput.isEmpty) ""
            else System.lineSeparator() + errOutput
          Left(s"'$command' exited with code $exitCode$details")
        }
      }
    }
    catch {
      case NonFatal(e) =>
        Left(s"Error running '$command': $e")
    }
    finally {
      if (stdout != null) Files.deleteIfExists(stdout)
      if (stderr != null) Files.deleteIfExists(stderr)
    }
  }

  /** Asks the `jupyter` command for the directories it relies on
    *
    * @param command
    *   `jupyter` command, either a command name to look for in the `PATH` or a path to it
    * @return
    *   either an error message, or the Jupyter directories
    */
  def get(
    command: String = defaultCommand,
    timeoutSeconds: Long = 60L
  ): Either[String, JupyterDirectories] =
    try get0(command, timeoutSeconds)
    catch {
      case NonFatal(e) =>
        Left(s"Error getting Jupyter directories via $command: $e")
    }

  private def get0(
    command: String,
    timeoutSeconds: Long
  ): Either[String, JupyterDirectories] =
    for {
      jupyter    <- executable(command)
      dataDirOut <- run(jupyter, Seq("--data-dir"), timeoutSeconds)
      dataDir <- Some(dataDirOut.trim).filter(_.nonEmpty).toRight(
        s"Empty output from '$jupyter --data-dir'"
      )
      pathsOutput <- run(jupyter, Seq("--paths", "--json"), timeoutSeconds)
      paths <- {
        try Right(readFromString[PathsOutput](pathsOutput.trim))
        catch {
          case e: JsonReaderException =>
            Left(s"Malformed output from '$jupyter --paths --json': ${e.getMessage}")
        }
      }
    } yield JupyterDirectories(
      jupyter,
      Paths.get(dataDir),
      paths.data.filter(_.nonEmpty).map(Paths.get(_))
    )
}
