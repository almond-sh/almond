package almond.launcher.directives

case class CustomGroup(
  prefix: String,
  command: String
) {
  private lazy val prefix0 = prefix + "."
  def matches(key: String): Boolean =
    key == prefix || key.startsWith(prefix0)
}
