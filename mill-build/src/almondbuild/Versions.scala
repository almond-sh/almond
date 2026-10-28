package almondbuild

object Versions {
  // Snapshot of our Ammonite fork, pinned to a specific build of it: a timestamped version,
  // rather than 3.1.1-SNAPSHOT that resolves to whatever was last published
  def ammonite      = "3.1.1-20261016.194654-7"
  def caseApp       = "2.1.1"
  def coursier      = "2.1.26"
  def jsoniterScala = "2.13.5"
  def devJvm        = "21" // JVM the dev.jupyter* commands run the kernel with
  def scalafmt      = "3.9.6"
  def scalafmtJava8 = "3.7.15"
  def uv            = "0.12.18"

  // widget classes are generated from the model schema of that version (see WidgetModelSchema)
  def ipywidgets = "8.1.7"
  // checksum of the model schema of ipywidgets, to update along with its version
  def ipywidgetsSchemaSha256 = "9b74e3760501675fab399e0c8f0339327296d2308482d5c739cc5c4b60abf104"
}
