import sbt._

object PluginDef extends Build {
  override def projects = Seq(root)

  lazy val root = Project("plugins", file(".")) dependsOn(sbtPack)

  // Trick from
  // https://github.com/sbt/sbt-boilerplate/issues/12#issuecomment-84257889

  lazy val sbtPack = uri(
    "https://github.com/alexarchambault/sbt-pack.git" +
    "#topic/launch-bat-javapath"
  )
}
