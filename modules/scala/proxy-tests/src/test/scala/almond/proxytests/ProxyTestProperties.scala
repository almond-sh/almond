package almond.proxytests

/** What the build passes to the tests, as Java properties */
object ProxyTestProperties {

  private def prop(name: String): String =
    sys.props.getOrElse(
      s"almond.proxy-tests.$name",
      sys.error(s"almond.proxy-tests.$name Java property not set")
    )
  private def pathProp(name: String): os.Path =
    os.Path(prop(name), os.pwd)

  /** Maven repository with the Almond modules under test, as the build published them */
  lazy val localRepo: os.Path = pathProp("local-repo")

  /** Version of the Almond modules in `localRepo` */
  lazy val almondVersion: String = prop("version")

  /** Main class of the newer launcher */
  lazy val launcherMainClass: String = prop("launcher-main-class")

  /** Scala version the kernels are installed for */
  lazy val scalaVersion: String = prop("scala-version")

  /** Version of the cs launcher put in the runner image */
  lazy val csVersion: String = prop("cs-version")

  /** Version of uv the runner image installs Jupyter with */
  lazy val uvVersion: String = prop("uv-version")

  /** Directory of the example notebooks, whose uv project provides Jupyter */
  lazy val examplesDir: os.Path = pathProp("examples-dir")

  /** Directory with the Docker files of the tests */
  lazy val dockerDir: os.Path = pathProp("docker-dir")

  /** Where the tests create their temporary directories - under the workspace rather than under the
    * system temporary directory, so that Docker can mount them on every platform
    */
  lazy val tmpDir: os.Path = pathProp("tmp-dir")

  /** Whether the kernels need the Maven snapshot repository, see the integration tests */
  lazy val useMavenSnapshots: Boolean = prop("maven-snapshots") match {
    case "true"  => true
    case "false" => false
    case other   => sys.error(s"Unrecognized almond.proxy-tests.maven-snapshots value '$other'")
  }
}
