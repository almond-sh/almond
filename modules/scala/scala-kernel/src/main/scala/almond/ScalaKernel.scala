package almond

import almond.channels.zeromq.ZeromqThreads
import almond.kernel.{Kernel, KernelThreads}
import almond.kernel.install.Install
import almond.util.OptionalLogger
import almond.util.ThreadUtil.singleThreadedExecutionContext
import caseapp._

import scala.language.reflectiveCalls

object ScalaKernel extends CaseApp[Options] {

  private lazy val log = OptionalLogger(getClass)

  private def loaderWithName(name: String, cl: ClassLoader): Option[ClassLoader] =
    if (cl == null)
      None
    else {

      val targetFound =
        try {
          val t = cl.asInstanceOf[ {
            def getIsolationTargets: Array[String]
          }].getIsolationTargets

          t.contains(name)
        } catch {
          case _: NoSuchMethodException =>
            false
        }

      if (targetFound)
        Some(cl)
      else
        loaderWithName(name, cl.getParent)
    }

  def run(options: Options, args: RemainingArgs): Unit = {

    if (options.install)
      Install.installOrError(
        defaultId = "scala",
        defaultDisplayName = "Scala",
        language = "scala",
        options = options.installOptions,
        defaultLogoOpt = Option(
          Thread.currentThread()
            .getContextClassLoader
            .getResource("almond/scala-logo-64x64.png")
        )
      ) match {
        case Left(e) =>
          Console.err.println(s"Error: $e")
          sys.exit(1)
        case Right(dir) =>
          println(s"Installed scala kernel under $dir")
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
      sys.props("scala-kernel.log.file") = f
      OptionalLogger.enable()
    }


    val autoDependencies = options.autoDependencyMap()
    val forceProperties = options.forceProperties()
    val mavenProfiles = options.mavenProfiles()
    val extraLinks = options.extraLinks()


    log.info(
      autoDependencies
        .flatMap {
          case (trigger, auto) =>
            Seq("Auto dependency:", s"  Trigger: $trigger") ++ auto.map(dep => s"  Adds: $dep")
        }
        .mkString("\n")
    )


    val interpreterEc = singleThreadedExecutionContext("scala-interpreter")
    val updateBackgroundVariablesEc = singleThreadedExecutionContext("update-background-variables")

    val zeromqThreads = ZeromqThreads.create("scala-kernel")
    val kernelThreads = KernelThreads.create("scala-kernel")

    val initialClassLoader = {

      val defaultLoader = Thread.currentThread().getContextClassLoader

      options.specialLoader match {
        case None => defaultLoader
        case Some(name) =>
          loaderWithName(name, defaultLoader).getOrElse {
            log.warn(s"--special-loader set to $name, but no classloader with that name can be found")
            defaultLoader
          }
      }
    }

    log.info("Creating interpreter")

    val interpreter = new ScalaInterpreter(
      updateBackgroundVariablesEcOpt = Some(updateBackgroundVariablesEc),
      extraRepos = options.extraRepository,
      extraBannerOpt = options.banner,
      extraLinks = extraLinks,
      predef = options.predef,
      automaticDependencies = autoDependencies,
      forceMavenProperties = forceProperties,
      mavenProfiles = mavenProfiles,
      initialClassLoader = initialClassLoader
    )
    log.info("Created interpreter")

    log.info("Running kernel")
    Kernel.create(interpreter, interpreterEc, kernelThreads)
      .flatMap(_.runOnConnectionFile(connectionFile, "scala", zeromqThreads))
      .unsafeRunSync()
  }

}
