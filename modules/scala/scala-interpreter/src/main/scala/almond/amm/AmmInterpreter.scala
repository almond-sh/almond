package almond.amm

import java.nio.file.{Files, Path}

import almond.{Execute, JupyterApiImpl, ReplApiImpl, ScalaInterpreter}
import almond.logger.LoggerContext
import ammonite.interp.{CodeWrapper, CompilerLifecycleManager, Preprocessor}
import ammonite.runtime.{Frame, Storage}
import ammonite.util.{Colors, Name, PredefInfo, Ref, Res}
import coursier.almond.tmp.Tmp

object AmmInterpreter {

  private def predef =
    """import almond.api.JupyterAPIHolder.value.{
      |  publish,
      |  commHandler
      |}
      |import almond.api.JupyterAPIHolder.value.publish.display
      |import almond.interpreter.api.DisplayData.DisplayDataSyntax
      |import almond.display._
      |import almond.display.Display.{markdown, html, latex, text, js, svg}
      |import almond.input._
    """.stripMargin

  /**
    * Instantiate an [[ammonite.interp.Interpreter]] to be used from [[ScalaInterpreter]].
    */
  def apply(
    execute0: Execute,
    storage: Storage,
    replApi: ReplApiImpl,
    jupyterApi: JupyterApiImpl,
    predefCode: String,
    predefFiles: Seq[Path],
    frames0: Ref[List[Frame]],
    codeWrapper: CodeWrapper,
    extraRepos: Seq[String],
    forceMavenProperties: Map[String, String],
    mavenProfiles: Map[String, Boolean],
    autoUpdateLazyVals: Boolean,
    autoUpdateVars: Boolean,
    logCtx: LoggerContext
  ): ammonite.interp.Interpreter = {

    val predefFileInfos =
      predefFiles.zipWithIndex.map {
        case (path, idx) =>
          val suffix = if (idx <= 0) "" else s"-$idx"
          PredefInfo(
            Name("FilePredef" + suffix),
            // read with the local charset…
            new String(Files.readAllBytes(path)),
            hardcoded = false,
            Some(os.Path(path))
          )
      }

    val log = logCtx(getClass)

    try {

      log.info("Creating Ammonite interpreter")

      val ammInterp0: ammonite.interp.Interpreter =
        new ammonite.interp.Interpreter(
          execute0.printer,
          storage = storage,
          wd = ammonite.ops.pwd,
          basePredefs = Seq(
            PredefInfo(
              Name("defaultPredef"),
              predef + ammonite.main.Defaults.replPredef + ammonite.main.Defaults.predefString,
              true,
              None
            )
          ),
          customPredefs = predefFileInfos ++ Seq(
            PredefInfo(Name("CodePredef"), predefCode, false, None)
          ),
          extraBridges = Seq(
            (ammonite.repl.ReplBridge.getClass.getName.stripSuffix("$"), "repl", replApi),
            (almond.api.JupyterAPIHolder.getClass.getName.stripSuffix("$"), "kernel", jupyterApi)
          ),
          colors = Ref(Colors.Default),
          getFrame = () => frames0().head,
          createFrame = () => {
            val f = replApi.sess.childFrame(frames0().head); frames0() = f :: frames0(); f
          },
          replCodeWrapper = codeWrapper,
          scriptCodeWrapper = codeWrapper,
          alreadyLoadedDependencies = ammonite.main.Defaults.alreadyLoadedDependencies("almond/almond-user-dependencies.txt")
        ) {
          override val compilerManager: CompilerLifecycleManager =
            new CompilerLifecycleManager(storage, headFrame, Some(dependencyComplete)) {
              override def preprocess(fileName: String): Preprocessor =
                synchronized {
                  if (compiler == null) init(force = true)
                  new AlmondPreprocessor(
                    compiler.parse(fileName, _),
                    autoUpdateLazyVals,
                    autoUpdateVars
                  )
                }
            }
        }

      log.debug("Initializing interpreter predef")

      for ((e, _) <- ammInterp0.initializePredef())
        e match {
          case Res.Failure(msg) =>
            throw new PredefException(msg, None)
          case Res.Exception(t, msg) =>
            throw new PredefException(msg, Some(t))
          case Res.Skip =>
          case Res.Exit(v) =>
            log.warn(s"Ignoring exit request from predef (exit value: $v)")
        }

      log.debug("Loading base dependencies")

      ammInterp0.repositories() = ammInterp0.repositories() ++ extraRepos.map { repo =>
        coursier.MavenRepository(repo)
      }

      log.debug("Initializing Ammonite interpreter")

      ammInterp0.compilerManager.init()

      log.debug("Processing scalac args")

      ammInterp0.compilerManager.preConfigureCompiler(_.processArguments(Nil, processAll = true))

      log.debug("Processing dependency-related params")

      if (forceMavenProperties.nonEmpty)
        ammInterp0.resolutionHooks += { fetch =>
          val params0 = Tmp.resolutionParams(fetch)
          val params = params0
            .withForcedProperties(params0.forcedProperties ++ forceMavenProperties)
          fetch.withResolutionParams(params)
        }

      if (mavenProfiles.nonEmpty)
        ammInterp0.resolutionHooks += { fetch =>
          val mavenProfiles0 = mavenProfiles.toVector.map {
            case (p, true) => p
            case (p, false) => "!" + p
          }
          val params0 = Tmp.resolutionParams(fetch)
          val params = params0
            .withProfiles(params0.profiles ++ mavenProfiles0)
          fetch.withResolutionParams(params)
        }

      log.info("Ammonite interpreter initialized")

      ammInterp0
    } catch {
      case t: Throwable =>
        log.error(s"Caught exception while initializing interpreter", t)
        throw t
    }
  }

  final class PredefException(
    msg: String,
    causeOpt: Option[Throwable]
  ) extends Exception(msg, causeOpt.orNull) {
    def describe: String =
      if (causeOpt.isEmpty)
        s"Error while running predef: $msg"
      else
        s"Caught exception while running predef: $msg"
  }

}
