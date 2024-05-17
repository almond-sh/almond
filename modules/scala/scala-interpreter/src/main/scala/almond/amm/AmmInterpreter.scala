package almond.amm

import java.nio.file.{Files, Path}

import almond.{Execute, JupyterApiImpl, ReplApiImpl, ScalaInterpreter}
import almond.cslogger.NotebookCacheLogger
import almond.logger.LoggerContext
import ammonite.compiler.iface.CodeWrapper
import ammonite.runtime.{Evaluator, Frame, Storage}
import ammonite.util.{ImportData, Imports, Name, PredefInfo, Ref, Res}
import coursierapi.{Dependency, Module}
import coursier.util.ModuleMatcher

import scala.jdk.CollectionConverters._
import scala.language.reflectiveCalls

object AmmInterpreter {

  private def almondImports = Imports(
    ImportData("""almond.api.JupyterAPIHolder.value.{
      publish,
      commHandler
    }"""),
    ImportData("almond.api.JupyterAPIHolder.value.publish.display"),
    ImportData("""almond.display.{
      Data,
      Display,
      FileLink,
      Html,
      IFrame,
      Image,
      Javascript,
      Json,
      Latex,
      Markdown,
      Math,
      PrettyPrint,
      ProgressBar,
      Svg,
      Text,
      TextDisplay,
      UpdatableDisplay
    }"""),
    ImportData("""almond.display.Display.{
      html,
      js,
      latex,
      markdown,
      svg,
      text
    }"""),
    ImportData("almond.interpreter.api.DisplayData.DisplayDataSyntax"),
    ImportData("almond.input.Input")
  )

  private def toreeApiCompatibilityImports = Imports(
    ImportData("almond.toree.ToreeCompatibility.KernelToreeOps")
  )

  /* Spark 3.5.1 expects `cmd` in `org.apache.spark.sql.catalyst.encoders.OuterScopes`.
   * This name is confusing to users and `cell` is more obvious. However, that change depends
   * on customizing the `CodeClassWrapper` so
   * `org.apache.spark.sql.catalyst.encoders.OuterScopes.addOuterScope(this)`
   * calls are automatically added. Or, less preferably, changing the regex in Spark.
   */
  private def ammoniteWrapperNamePrefix = "cmd"

  /** Instantiate an [[ammonite.interp.Interpreter]] to be used from [[ScalaInterpreter]].
    */
  def apply(
    execute0: Execute,
    storage0: Storage,
    replApi: ReplApiImpl,
    jupyterApi: JupyterApiImpl,
    predefCode: String,
    predefFiles: Seq[Path],
    frames0: Ref[List[Frame]],
    codeWrapper: CodeWrapper,
    extraRepos: Seq[String],
    automaticDependencies: Map[Module, Seq[Dependency]],
    automaticVersions: Map[Module, String],
    forceMavenProperties: Map[String, String],
    mavenProfiles: Map[String, Boolean],
    autoUpdateLazyVals: Boolean,
    autoUpdateVars: Boolean,
    useNotebookCoursierLogger: Boolean,
    silentImports: Boolean,
    logCtx: LoggerContext,
    variableInspectorEnabled: () => Boolean,
    outputDir: Either[os.Path, Boolean],
    compileOnly: Boolean,
    addToreeApiCompatibilityImport: Boolean,
    initialSettings: Seq[String]
  ): ammonite.interp.Interpreter = {

    val automaticDependenciesMatchers = automaticDependencies
      .iterator
      .collect {
        case (m, l) if m.getOrganization.contains("*") || m.getName.contains("*") =>
          ModuleMatcher(coursier.Module(
            coursier.Organization(m.getOrganization),
            coursier.ModuleName(m.getName)
          )) -> l
      }
      .toVector

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

      val addToreeApiCompatibilityImport0 =
        addToreeApiCompatibilityImport && {
          val loader = frames0().head.classloader
          val clsOpt =
            try Some[Class[_]](loader.loadClass("almond.toree.ToreeCompatibility$"))
            catch {
              case _: ClassNotFoundException =>
                None
            }
          if (clsOpt.isEmpty)
            log.error(
              "Ignoring Toree API compatibility option, as sh.almond::toree-hooks isn't part of the user class path"
            )
          clsOpt.nonEmpty
        }

      log.info("Creating Ammonite interpreter")

      val interpParams = ammonite.interp.Interpreter.Parameters(
        printer = execute0.printer,
        storage = storage0,
        wd = os.pwd,
        colors = replApi.colors,
        verboseOutput = true, // ???
        alreadyLoadedDependencies =
          ammonite.main.Defaults.alreadyLoadedDependencies("almond/almond-user-dependencies.txt"),
        wrapperNamePrefix = ammoniteWrapperNamePrefix
      )
      val outputDir0 = outputDir match {
        case Left(path)   => Some(path.toNIO)
        case Right(true)  => Some(os.temp.dir(prefix = "almond-output").toNIO)
        case Right(false) => None
      }
      val ammInterp0: ammonite.interp.Interpreter =
        new ammonite.interp.Interpreter(
          ammonite.compiler.CompilerBuilder(
            outputDir = outputDir0
          ),
          () => ammonite.compiler.Parsers,
          getFrame = () => frames0().head,
          createFrame = () => {
            val f = replApi.sess.childFrame(frames0().head); frames0() = f :: frames0(); f
          },
          replCodeWrapper = codeWrapper,
          scriptCodeWrapper = codeWrapper,
          parameters = interpParams
        ) {
          override val compilerManager = new AlmondCompilerLifecycleManager(
            storage0.dirOpt.map(_.toNIO),
            headFrame,
            Some(dependencyComplete),
            Set.empty,
            headFrame.classloader,
            autoUpdateLazyVals,
            autoUpdateVars,
            silentImports,
            variableInspectorEnabled,
            outputDir0,
            initialSettings,
            logCtx
          )

          override val eval: Evaluator = {
            val baseEval = Evaluator(headFrame) // super.eval basically
            if (compileOnly)
              new CompileOnlyEvaluator(() => headFrame, baseEval)
            else
              baseEval
          }
        }

      if (useNotebookCoursierLogger)
        ammInterp0.resolutionHooks.append { fetch =>
          val cache  = fetch.getCache
          val logger = new NotebookCacheLogger(jupyterApi.publish, logCtx)
          val cache0 = cache.withLogger(logger)
          fetch.withCache(cache0)
        }

      val customPredefs = predefFileInfos ++ {
        if (predefCode.isEmpty) Nil
        else Seq(PredefInfo(Name("CodePredef"), predefCode, false, None))
      }
      val extraBridges = Seq(
        (ammonite.repl.ReplBridge.getClass.getName.stripSuffix("$"), "repl", replApi),
        (almond.api.JupyterAPIHolder.getClass.getName.stripSuffix("$"), "kernel", jupyterApi)
      )

      log.debug("Initializing interpreter predef")

      val imports = ammonite.main.Defaults.replImports ++
        ammonite.interp.Interpreter.predefImports ++
        almondImports ++
        (if (addToreeApiCompatibilityImport0) toreeApiCompatibilityImports else Imports())
      for ((e, _) <- ammInterp0.initializePredef(Nil, customPredefs, extraBridges, imports))
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

      // TODO: remove jitpack once jvm-repr is published to central
      val allExtraRepos = extraRepos ++ Seq(coursier.Repositories.jitpack.root)
      ammInterp0.repositories() = ammInterp0.repositories() ++
        allExtraRepos.map { r =>
          if (r.startsWith("ivy:"))
            coursierapi.IvyRepository.of(r.stripPrefix("ivy:"))
          else
            coursierapi.MavenRepository.of(r)
        }

      ammInterp0.resolutionHooks += { f =>
        val extraDependencies = f.getDependencies
          .asScala
          .toVector
          .flatMap { dep =>
            val mod = coursier.Module(
              coursier.Organization(dep.getModule.getOrganization),
              coursier.ModuleName(dep.getModule.getName)
            )
            automaticDependencies.getOrElse(
              dep.getModule,
              automaticDependenciesMatchers
                .find(_._1.matches(mod))
                .map(_._2)
                .getOrElse(Nil)
            )
          }
        val f0 = f.addDependencies(extraDependencies: _*)

        val deps = f0.getDependencies.asScala.toVector
        if (deps.exists(_.getVersion == "_")) {
          val dependencies0 = deps.map { dep =>
            if (dep.getVersion == "_")
              automaticVersions.get(dep.getModule) match {
                case None =>
                  System.err.println(
                    s"Warning: version ${"\"_\""} specified for ${dep.getModule}, " +
                      "but no automatic version available for it"
                  )
                  dep
                case Some(ver) =>
                  dep.withVersion(ver)
              }
            else
              dep
          }
          f0.withDependencies(dependencies0: _*)
        }
        else
          f0
      }

      log.debug("Initializing Ammonite interpreter")

      ammInterp0.compilerManager.init()

      log.debug("Processing scalac args")

      ammInterp0
        .compilerManager
        .asInstanceOf[AlmondCompilerLifecycleManager]
        .preConfigure()

      log.debug("Processing dependency-related params")

      if (forceMavenProperties.nonEmpty)
        ammInterp0.resolutionHooks += { fetch =>
          fetch.withResolutionParams(
            fetch
              .getResolutionParams
              .forceProperties(forceMavenProperties.asJava)
          )
        }

      if (mavenProfiles.nonEmpty)
        ammInterp0.resolutionHooks += { fetch =>
          val mavenProfiles0 = mavenProfiles.toVector.map {
            case (p, true)  => p
            case (p, false) => "!" + p
          }
          fetch.withResolutionParams(
            mavenProfiles0.foldLeft(fetch.getResolutionParams)(_.addProfile(_))
          )
        }

      log.info("Ammonite interpreter initialized")

      ammInterp0
    }
    catch {
      case t: Throwable =>
        log.error("Caught exception while initializing interpreter", t)
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
