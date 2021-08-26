
import java.nio.file._

def writeKernelJson(launcher: Path, jupyterDir: Path): Unit = {
  val launcherPath = launcher.toAbsolutePath.toString
  val dir = jupyterDir.resolve("kernels/scala-debug")
  Files.createDirectories(dir)
  val kernelJson = s"""{
    "language": "scala",
    "display_name": "Scala (sources)",
    "argv": [
      "$launcherPath",
      "--log", "info",
      "--connection-file", "{connection_file}",
      "--variable-inspector"
    ]
  }"""
  Files.write(dir.resolve("kernel.json"), kernelJson.getBytes("UTF-8"))
  System.err.println(s"JUPYTER_PATH=$jupyterDir")
}

def jupyterServer(launcher: Path, jupyterDir: Path, args: Seq[String]): Unit = {

  writeKernelJson(launcher, jupyterDir)

  os.makeDir.all(os.pwd / "notebooks")
  val jupyterCommand = Seq("jupyter", "lab", "--notebook-dir", "notebooks")
  val b = new ProcessBuilder(jupyterCommand ++ args: _*).inheritIO()
  val env = b.environment()
  env.put("JUPYTER_PATH", jupyterDir.toAbsolutePath.toString)
  val p = b.start()
  val hook: Thread = new Thread("jupyter-stop") {
    override def run() =
      if (p.isAlive)
        p.destroy()
  }
  Runtime.getRuntime.addShutdownHook(hook)
  val retCode = p.waitFor()
  Runtime.getRuntime.removeShutdownHook(hook)
  if (retCode != 0)
    System.err.println(s"Jupyter command exited with code $retCode")
}
