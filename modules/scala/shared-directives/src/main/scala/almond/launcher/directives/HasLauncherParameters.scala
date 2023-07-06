package almond.launcher.directives

trait HasLauncherParameters {
  def launcherParameters: LauncherParameters
}

object HasLauncherParameters {

  case object Ignore extends HasLauncherParameters {
    def launcherParameters: LauncherParameters =
      LauncherParameters()
  }

}
