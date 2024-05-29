package almond.echo

import almond.util.ThreadUtil.singleThreadedExecutionContext
import almond.channels.zeromq.ZeromqThreads
import almond.kernel.install.Install
import almond.kernel.{Kernel, KernelThreads}
import almond.logger.{Level, LoggerContext}
import caseapp._
import cats.effect.unsafe.IORuntime

object EchoKernel extends CaseApp[Options] {

  def run(options: Options, args: RemainingArgs): Unit = {

    val logCtx = Level.fromString(options.log) match {
      case Left(err) =>
        Console.err.println(err)
        sys.exit(1)
      case Right(level) =>
        LoggerContext.stderr(level)
    }

    val log = logCtx(getClass)

    if (options.install)
      Install.installOrError(
        defaultId = "echo",
        defaultDisplayName = "Echo",
        language = "echo",
        options = options.installOptions,
        extraStartupClassPath = Nil
      ) match {
        case Left(e) =>
          log.debug("Cannot install kernel", e)
          Console.err.println(s"Error: ${e.getMessage}")
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

    val zeromqThreads = ZeromqThreads.create("echo-kernel")
    val kernelThreads = KernelThreads.create("echo-kernel")
    val interpreterEc = singleThreadedExecutionContext("echo-interpreter")

    log.debug("Running kernel")
    Kernel.create(new EchoInterpreter, interpreterEc, kernelThreads, logCtx)
      .flatMap(_.runOnConnectionFile(
        connectionFile,
        "echo",
        zeromqThreads,
        Nil,
        autoClose = true,
        lingerDuration = options.lingerDuration
      ))
      .unsafeRunSync()(IORuntime.global)
  }
}
