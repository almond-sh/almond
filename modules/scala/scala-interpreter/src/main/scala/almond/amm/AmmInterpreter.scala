package almond.amm

import java.nio.file.{Files, Path}

import almond.{Execute, JupyterApiImpl, ReplApiImpl, ScalaInterpreter}
import almond.logger.LoggerContext
import ammonite.compiler.iface.{CodeWrapper, Preprocessor}
import ammonite.compiler.CompilerLifecycleManager
import ammonite.runtime.{Frame, Storage}
import ammonite.util.{Colors, ImportData, Imports, Name, PredefInfo, Ref, Res}
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

  /**
    * Instantiate an [[ammonite.interp.Interpreter]] to be used from [[ScalaInterpreter]].
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
    initialClassLoader: ClassLoader,
    logCtx: LoggerContext,
    variableInspectorEnabled: () => Boolean
  ): ammonite.interp.Interpreter = {

    val automaticDependenciesMatchers = automaticDependencies
      .iterator
      .collect {
        case (m, l) if m.getOrganization.contains("*") || m.getName.contains("*") =>
          ModuleMatcher(coursier.Module(coursier.Organization(m.getOrganization), coursier.ModuleName(m.getName))) -> l
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

      log.info("Creating Ammonite interpreter")

      val ammInterp0: ammonite.interp.Interpreter =
        new ammonite.interp.Interpreter(
          ammonite.compiler.CompilerBuilder,
          ammonite.compiler.Parsers,
          printer = execute0.printer,
          storage = storage0,
          wd = ammonite.ops.pwd,
          colors = replApi.colors,
          verboseOutput = true, // ???
          getFrame = () => frames0().head,
          createFrame = () => {
            val f = replApi.sess.childFrame(frames0().head); frames0() = f :: frames0(); f
          },
          replCodeWrapper = codeWrapper,
          scriptCodeWrapper = codeWrapper,
          alreadyLoadedDependencies = ammonite.main.Defaults.alreadyLoadedDependencies("almond/almond-user-dependencies.txt")
        ) {
          override val compilerManager = new AlmondCompilerLifecycleManager(
            storage0.dirOpt.map(_.toNIO),
            headFrame,
            Some(dependencyComplete),
            Set.empty,
            headFrame.classloader,
            autoUpdateLazyVals,
            autoUpdateVars,
            variableInspectorEnabled,
            logCtx
          )
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
        almondImports
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

      ammInterp0.repositories() = ammInterp0.repositories() ++
        extraRepos.map { r =>
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
            if (dep.getVersion == "_") {
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
            } else
              dep
          }
          f0.withDependencies(dependencies0: _*)
        } else
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
            case (p, true) => p
            case (p, false) => "!" + p
          }
          fetch.withResolutionParams(
            mavenProfiles0.foldLeft(fetch.getResolutionParams)(_.addProfile(_))
          )
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
