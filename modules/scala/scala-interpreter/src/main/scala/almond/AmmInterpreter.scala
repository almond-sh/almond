package almond

import java.nio.file.{Files, Path}

import almond.logger.LoggerContext
import ammonite.interp.{CodeWrapper, CompilerLifecycleManager, DefaultPreprocessor, Parsers, Preprocessor}
import ammonite.runtime.{Frame, Storage}
import ammonite.util.{Colors, Name, PredefInfo, Ref, Res}
import coursier.almond.tmp.Tmp
import fastparse.Parsed

import scala.util.Properties

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

  private[almond] val isAtLeast_2_12_7 = {
    val v = Properties.versionNumberString
    !v.startsWith("2.11.") && (!v.startsWith("2.12.") || {
      v.stripPrefix("2.12.").takeWhile(_.isDigit).toInt >= 7
    })
  }

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
            new CompilerLifecycleManager(storage, headFrame) {
              import scala.reflect.internal.Flags
              import scala.tools.nsc.{Global => G}
              def customPprintSignature(ident: String, customMsg: Option[String], modOpt: Option[String], modErrOpt: Option[String]) = {
                val customCode = customMsg.fold("_root_.scala.None")(x => s"""_root_.scala.Some("$x")""")
                val modOptCode = modOpt.fold("_root_.scala.None")(x => s"""_root_.scala.Some($x)""")
                val modErrOptCode = modErrOpt.fold("_root_.scala.None")(x => s"""_root_.scala.Some($x)""")
                s"""_root_.almond
                   |  .api
                   |  .JupyterAPIHolder
                   |  .value
                   |  .Internal
                   |  .printOnChange($ident, ${fastparse.internal.Util.literalize(ident)}, $customCode, $modOptCode, $modErrOptCode)""".stripMargin
              }
              override def preprocess(fileName: String): Preprocessor =
                synchronized {
                  if (compiler == null) init(force = true)
                  new DefaultPreprocessor(compiler.parse(fileName, _)) {

                    val CustomLazyDef = Processor {
                      case (_, code, t: G#ValDef)
                        if autoUpdateLazyVals &&
                          !DefaultPreprocessor.isPrivate(t) &&
                          !t.name.decoded.contains("$") &&
                          t.mods.hasFlag(Flags.LAZY) =>
                        val (code0, modOpt) = fastparse.parse(code, Parsers.PatVarSplitter(_)) match {
                          case Parsed.Success((lhs, rhs), _) if lhs.startsWith("lazy val ") =>
                            val mod = Name.backtickWrap(t.name.decoded + "$value")
                            val c = s"""val $mod = new _root_.almond.api.internal.Lazy(() => $rhs)
                                       |import $mod.{value => ${Name.backtickWrap(t.name.decoded)}}
                                       |""".stripMargin
                            (c, Some(mod + ".onChange"))
                          case _ =>
                            (code, None)
                        }
                        DefaultPreprocessor.Expanded(
                          code0,
                          Seq(customPprintSignature(Name.backtickWrap(t.name.decoded), Some("[lazy]"), None, modOpt))
                        )
                    }

                    val CustomVarDef = Processor {

                      case (_, code, t: G#ValDef)
                        if autoUpdateVars &&
                          isAtLeast_2_12_7 && // https://github.com/scala/bug/issues/10886
                          !DefaultPreprocessor.isPrivate(t) &&
                          !t.name.decoded.contains("$") &&
                          !t.mods.hasFlag(Flags.LAZY) =>
                        val (code0, modOpt) = fastparse.parse(code, Parsers.PatVarSplitter(_)) match {
                          case Parsed.Success((lhs, rhs), _) if lhs.startsWith("var ") =>
                            val mod = Name.backtickWrap(t.name.decoded + "$value")
                            val c = s"""val $mod = new _root_.almond.api.internal.Modifiable($rhs)
                                       |import $mod.{value => ${Name.backtickWrap(t.name.decoded)}}
                                       |""".stripMargin
                            (c, Some(mod + ".onChange"))
                          case _ =>
                            (code, None)
                        }
                        DefaultPreprocessor.Expanded(
                          code0,
                          Seq(customPprintSignature(Name.backtickWrap(t.name.decoded), None, modOpt, None))
                        )

                    }
                    override val decls = Seq[(String, String, G#Tree) => Option[DefaultPreprocessor.Expanded]](
                      CustomLazyDef, CustomVarDef,
                      // same as super.decls
                      ObjectDef, ClassDef, TraitDef, DefDef, TypeDef, PatVarDef, Import, Expr
                    )
                  }
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
