package almond.echo

import almond.util.ThreadUtil.singleThreadedExecutionContext
import almond.channels.zeromq.ZeromqThreads
import almond.kernel.install.Install
import almond.kernel.{Kernel, KernelThreads}
import almond.util.OptionalLogger
import caseapp._

object EchoKernel extends CaseApp[Options] {

  private lazy val log = OptionalLogger(getClass)

  def run(options: Options, args: RemainingArgs): Unit = {

    if (options.install)
      Install.installOrError(
        defaultId = "echo",
        defaultDisplayName = "Echo",
        language = "echo",
        options = options.installOptions
      ) match {
        case Left(e) =>
          Console.err.println(s"Error: $e")
          sys.exit(1)
        case Right(dir) =>
          println(s"Installed echo kernel under $dir")
          sys.exit(0)
      }

    val connectionFile = options.connectionFile.getOrElse {
      Console.err.println(
        "No connection file passed, and installation not asked. Run with --install to install the kernel, " +
          "or pass a connection file via --connection-file to run the kernel."
      )
      sys.exit(1)
    }

    for (f <- options.logTo) {
      sys.props("echo-kernel.log.file") = f
      OptionalLogger.enable()
    }

    val zeromqThreads = ZeromqThreads.create("echo-kernel")
    val kernelThreads = KernelThreads.create("echo-kernel")
    val interpreterEc = singleThreadedExecutionContext("echo-interpreter")

    log.info("Running kernel")
    Kernel.create(new EchoInterpreter, interpreterEc, kernelThreads)
      .flatMap(_.runOnConnectionFile(connectionFile, "echo", zeromqThreads))
      .unsafeRunSync()
  }
}
