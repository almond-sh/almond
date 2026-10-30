package almondbuild

object Versions {
  // Snapshot of our Ammonite fork, pinned to a specific build of it: a timestamped version,
  // rather than 3.1.1-SNAPSHOT that resolves to whatever was last published
  def ammonite      = "3.1.1-20261016.194654-7"
  def caseApp       = "2.1.1"
  def coursier      = "2.1.26"
  def jsoniterScala = "2.13.5"
  def devJvm        = "21" // JVM the dev.jupyter* commands run the kernel with
  def scalafmt      = "3.9.10"
  def scalafmtJava8 = "3.7.15"
  def uv            = "0.12.18"
}
