package almond.launcher

import java.io.ByteArrayInputStream
import java.util.{Properties => JProperties}

object Properties {

  private lazy val path = os.resource / "almond" / "launcher" / "launcher.properties"
  private lazy val props = {
    val content = os.read.bytes(path)

    val p = new JProperties
    p.load(new ByteArrayInputStream(content))
    p
  }

  private def prop(name: String) =
    Option(props.getProperty(name)).getOrElse {
      sys.error(s"$name property not found in $path")
    }

  lazy val version    = prop("version")
  lazy val commitHash = prop("commit-hash")

  lazy val ammoniteVersion = prop("ammonite-version")

  lazy val kernelMainClass        = prop("kernel-main-class")
  lazy val defaultScalaVersion    = prop("default-scala-version")
  lazy val defaultScala212Version = prop("default-scala212-version")
  lazy val defaultScala213Version = prop("default-scala213-version")

}
