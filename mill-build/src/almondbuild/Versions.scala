package almondbuild

object Versions {
  // Snapshot of our Ammonite fork, pinned to a specific build of it: a timestamped version,
  // rather than 3.1.1-SNAPSHOT that resolves to whatever was last published
  def ammonite      = "3.1.1-20261122.192052-33"
  def caseApp       = "2.1.1"
  def coursier      = "2.1.27"
  def jsoniterScala = "2.13.5"
  // GitHub release of Plasmon whose native launcher kernels use by default with --lsp, and whether
  // its launchers get updated (like those of the nightly release), so that coursier checks for
  // updates once its cached ones get older than its TTL
  def plasmonTag         = "nightly"
  def plasmonTagChanging = true
  def devJvm             = "21" // JVM the dev.jupyter* commands run the kernel with
  def scalafmt           = "3.9.6"
  def scalafmtJava8      = "3.7.15"
  def uv                 = "0.13.10"
}
