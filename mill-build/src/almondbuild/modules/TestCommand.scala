package almondbuild.modules

import mill._
import mill.javalib._

trait TestCommand extends TestModule {

  // based on https://github.com/com-lihaoyi/mill/blob/cfbafb806351c3e1664de4e2001d3d1ddda045da/scalalib/src/TestModule.scala#L98-L164
  def testCommand(args: String*) =
    Task.Command {
      import mill.testrunner.TestRunner

      val globSelectors = Nil
      val outputPath    = Task.workspace / "test-output.json"
      val resultPath    = Task.dest / "results.log"
      val useArgsFile   = testUseArgsFile()

      val (jvmArgs, props: Map[String, String]) =
        if (useArgsFile) {
          val (props, jvmArgs) = forkArgs().partition(_.startsWith("-D"))
          val sysProps =
            props
              .map(_.drop(2).split("[=]", 2))
              .map {
                case Array(k, v) => k -> v
                case Array(k)    => k -> ""
              }
              .toMap

          jvmArgs -> sysProps
        }
        else
          forkArgs() -> Map()

      val testArgs = mill.testrunner.TestArgs(
        framework = testFramework(),
        classpath = runClasspath().map(_.path),
        arguments = args,
        sysProps = props,
        outputPath = outputPath,
        resultPath = resultPath,
        colored = Task.log.colored,
        testCp = Seq(compile().classes.path),
        home = Task.home,
        globSelectors = Left(globSelectors)
      )

      val testRunnerClasspathArg = jvmWorker().scalalibClasspath()
        .map(_.path.toNIO.toUri.toURL)
        .mkString(",")

      val argsFile = Task.dest / "testargs"
      os.write(argsFile, upickle.default.write(testArgs))
      val mainArgs   = Seq(testRunnerClasspathArg, argsFile.toString)
      val envArgs    = forkEnv()
      val workingDir = forkWorkingDir()

      val args0 = jvmSubprocessCommand(
        mainClass = "mill.testrunner.entrypoint.TestRunnerMain",
        classPath = (runClasspath() ++ jvmWorker().testrunnerEntrypointClasspath()).map(_.path),
        jvmArgs = jvmArgs,
        envArgs = envArgs,
        mainArgs = mainArgs,
        workingDir = workingDir,
        useCpPassingJar = useArgsFile
      )

      val env = envArgs.toVector.map { case (k, v) => s"$k=$v" }.toSet
        .--(sys.env.toVector.map { case (k, v) => s"$k=$v" })
        .toVector
        .sorted

      Map(
        "args" -> args0,
        "cwd"  -> Seq(workingDir.toString),
        "env"  -> env
      )
    }

  def jvmSubprocessCommand(
    mainClass: String,
    classPath: Seq[os.Path],
    jvmArgs: Seq[String] = Seq.empty,
    envArgs: Map[String, String] = Map.empty,
    mainArgs: Seq[String] = Seq.empty,
    workingDir: os.Path = null,
    background: Boolean = false,
    useCpPassingJar: Boolean = false
  )(implicit ctx: mill.api.Ctx): Seq[String] = {

    import mill.util.Jvm

    val cp =
      if (useCpPassingJar && !classPath.iterator.isEmpty) {
        val passingJar = os.temp(prefix = "run-", suffix = ".jar", deleteOnExit = false)
        ctx.log.debug(
          s"Creating classpath passing jar '$passingJar' with Class-Path: ${classPath.iterator.map(
              _.toNIO.toUri().toURL().toExternalForm()
            ).mkString(" ")}"
        )
        Jvm.createClasspathPassingJar(passingJar, classPath)
        Seq(passingJar)
      }
      else
        classPath

    Vector(Jvm.javaExe) ++
      jvmArgs ++
      Vector("-cp", cp.iterator.mkString(java.io.File.pathSeparator), mainClass) ++
      mainArgs
  }
}
