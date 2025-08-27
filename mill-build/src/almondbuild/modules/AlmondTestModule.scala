package almondbuild.modules

import almondbuild.Deps
import mill._
import mill.scalalib._

trait AlmondTestModule
    extends ScalaModule
    with TestModule
    with AlmondRepositories
    with AlmondScalacOptions {

  // originally based on https://github.com/com-lihaoyi/mill/blob/3335d2a2f7f33766a680e30df6a7d0dc0fbe08b3/scalalib/src/mill/scalalib/TestModule.scala#L80-L146
  // goal here is to use a coursier bootstrap rather than a manifest JAR when testUseArgsFile is true
  protected def testTask(
    args: Task[Seq[String]],
    globSelectors: Task[Seq[String]]
  ): Task[(String, Seq[mill.testrunner.TestResult])] =
    Task.Anon {
      val outputPath  = Task.dest / "out.json"
      val resultPath  = Task.dest / "results.log"
      val useArgsFile = testUseArgsFile()

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
        arguments = args(),
        sysProps = props,
        outputPath = outputPath,
        resultPath = resultPath,
        colored = Task.log.colored,
        testCp = Seq(compile().classes.path),
        home = Task.home,
        globSelectors = Left(globSelectors())
      )

      val testRunnerClasspathArg = jvmWorker().scalalibClasspath()
        .map(_.path.toNIO.toUri.toURL)
        .mkString(",")

      val argsFile = Task.dest / "testargs"
      os.write(argsFile, upickle.default.write(testArgs))
      val mainArgs = Seq(testRunnerClasspathArg, argsFile.toString)

      runSubprocess(
        mainClass = "mill.testrunner.entrypoint.TestRunnerMain",
        classPath = (runClasspath() ++ jvmWorker().testrunnerEntrypointClasspath()).map(_.path),
        jvmArgs = jvmArgs,
        envArgs = forkEnv(),
        mainArgs = mainArgs,
        workingDir = forkWorkingDir(),
        useCpPassingJar = useArgsFile
      )

      if (!os.exists(outputPath)) mill.api.Result.Failure("Test execution failed.")
      else
        try {
          val jsonOutput = ujson.read(outputPath.toIO)
          val (doneMsg, results) =
            upickle.default.read[(String, Seq[mill.testrunner.TestResult])](jsonOutput)
          TestModule.handleResults(doneMsg, results, Some(Task.ctx()))
        }
        catch {
          case e: Throwable =>
            mill.api.Result.Failure("Test reporting failed: " + e)
        }
    }

  private def createBootstrapJar(
    dest: os.Path,
    classPath: Seq[os.Path],
    mainClass: String
  ): Unit = {
    import coursier.launcher._
    val content = Seq(ClassLoaderContent.fromUrls(classPath.toSeq.map(_.toNIO.toUri.toASCIIString)))
    val params  = Parameters.Bootstrap(content, mainClass)
    BootstrapGenerator.generate(params, dest.toNIO)
  }

  // originally based on https://github.com/com-lihaoyi/mill/blob/3335d2a2f7f33766a680e30df6a7d0dc0fbe08b3/main/util/src/mill/util/Jvm.scala#L117-L154
  // goal is to use coursier bootstraps rather than manifest JARs
  // the former load JARs via standard URLClassLoader-s, which is better dealt with in Ammonite and almond
  private def runSubprocess(
    mainClass: String,
    classPath: Seq[os.Path],
    jvmArgs: Seq[String] = Seq.empty,
    envArgs: Map[String, String] = Map.empty,
    mainArgs: Seq[String] = Seq.empty,
    workingDir: os.Path = null,
    useCpPassingJar: Boolean = false
  )(implicit ctx: mill.api.Ctx): Unit = {

    val (cp, mainClass0) =
      if (useCpPassingJar && !classPath.iterator.isEmpty) {
        val passingJar = os.temp(prefix = "run-", suffix = ".jar", deleteOnExit = false)
        ctx.log.debug(
          s"Creating classpath passing jar '$passingJar' with Class-Path: ${classPath.iterator.map(_.toNIO.toUri.toURL.toExternalForm).mkString(" ")}"
        )
        createBootstrapJar(passingJar, classPath, mainClass)
        (Seq(passingJar), "coursier.bootstrap.launcher.Launcher")
      }
      else
        (classPath, mainClass)

    val args =
      Vector(mill.util.Jvm.javaExe) ++
        jvmArgs ++
        Vector("-cp", cp.iterator.mkString(java.io.File.pathSeparator), mainClass0) ++
        mainArgs

    ctx.log.debug(s"Run subprocess with args: ${args.map(a => s"'$a'").mkString(" ")}")

    os.proc(args)
      .call(env = envArgs, cwd = workingDir, stdin = os.Inherit, stdout = os.Inherit)
  }

  def ivyDeps       = Agg(Deps.utest)
  def testFramework = "utest.runner.Framework"

  // from https://github.com/VirtusLab/scala-cli/blob/cf77234ab981332531cbcb0d6ae565de009ae252/build.sc#L501-L522
  // pin scala3-library suffix, so that 2.13 modules can have us as moduleDep fine
  def mandatoryIvyDeps = Task {
    super.mandatoryIvyDeps().map { dep =>
      val isScala3Lib =
        dep.dep.module.organization.value == "org.scala-lang" &&
        dep.dep.module.name.value == "scala3-library" &&
        (dep.cross match {
          case _: CrossVersion.Binary => true
          case _                      => false
        })
      if (isScala3Lib)
        dep.copy(
          dep = dep.dep.withModule(
            dep.dep.module.withName(
              coursier.ModuleName(dep.dep.module.name.value + "_3")
            )
          ),
          cross = CrossVersion.empty(dep.cross.platformed)
        )
      else dep
    }
  }
}
