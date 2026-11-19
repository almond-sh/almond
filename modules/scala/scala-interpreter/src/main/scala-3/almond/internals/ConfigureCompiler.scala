package almond.internals

import ammonite.compiler.CompilerExtensions._
import ammonite.compiler.CompilerLifecycleManager
import ammonite.interp.api.InterpAPI
import dotty.tools.dotc.ScalacCommand
import dotty.tools.dotc.config.ScalaSettings
import dotty.tools.dotc.config.Settings.SettingGroup

object ConfigureCompiler {

  /** The compiler settings, without any state (that lives in the `SettingsState`-s passed along)
    *
    * Up to Scala 3.3, `ScalaSettings` is a class that we instantiate. Later on, it's abstract, and
    * its companion object, that extends it, holds the settings.
    */
  private lazy val settingGroup: SettingGroup = {
    val loader = classOf[ScalaSettings].getClassLoader
    Class.forName(classOf[ScalaSettings].getName + "$", true, loader)
      .getField("MODULE$")
      .get(null) match {
      case group: SettingGroup => group
      case _                   => classOf[ScalaSettings].getConstructor().newInstance()
    }
  }

  /** Adds options to the compiler, if they're valid
    *
    * The options are checked on settings of their own first, so that invalid ones are reported
    * right away (rather than when the compiler is next initialized).
    *
    * @return
    *   the errors of the options if they're invalid, or the warnings about unknown options
    *   otherwise (like the compiler, we only warn about those)
    */
  def addOptions(interpApi: InterpAPI)(options: Seq[String]): Either[String, Seq[String]] =
    if (options.isEmpty) Right(Nil)
    else {
      val summary =
        settingGroup.processArguments(options.toList, true, settingGroup.defaultState)
      if (summary.errors.isEmpty) {
        interpApi.preConfigureCompiler { ctx =>
          val summary =
            ScalacCommand.distill(options.toArray, ctx.settings)(ctx.settingsState)(using ctx)
          ctx.setSettings(summary.sstate)
        }
        // Only the warnings about unknown options: the others (like deprecated aliases) were
        // never reported, and would be printed again each time the options are used
        Right(summary.warnings.filter(_.startsWith("bad option")))
      }
      else
        Left(summary.errors.mkString(System.lineSeparator()))
    }
}
