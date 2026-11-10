package almond.kernel.install

/** Names of the groups the options of the kernels and launchers are sorted in.
  *
  * These are passed to case-app's `@Group` annotation. They appear in the `--help` output of the
  * kernel and of the launcher (as "<group> options:"), and the options page of the documentation
  * lists the options group per group.
  */
object OptionGroups {

  // the group case-app puts the --help and --usage options in
  final val help         = "Help"
  final val installation = "Installation"
  final val scala        = "Scala"
  final val dependency   = "Dependency"
  final val internal     = "Internal"
  final val jupyter      = "Jupyter"
  final val toree        = "Toree"
  // options of the newer launcher, that the kernel doesn't accept
  final val launcher = "Launcher"

  /** The order the groups are shown in */
  val all: Seq[String] = Seq(
    help,
    launcher,
    installation,
    scala,
    dependency,
    internal,
    jupyter,
    toree
  )
}
