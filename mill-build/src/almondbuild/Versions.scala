package almondbuild

object Versions {
  // Snapshot of our Ammonite fork, pinned to a specific build of it: a timestamped version,
  // rather than 3.1.1-SNAPSHOT that resolves to whatever was last published
  def ammonite = "3.1.1-20261125.180358-36"
  // Same snapshot build as ammonite above, but snapshot build numbers are per module, and
  // ammonite-directives was added to the fork later than the other modules
  def ammoniteDirectives = "3.1.1-20261125.180358-3"
  def caseApp            = "2.1.1"
  def coursier           = "2.1.27"
  def jsoniterScala      = "2.13.5"
  def devJvm             = "21" // JVM the dev.jupyter* commands run the kernel with
  def scalafmt           = "3.9.6"
  def scalafmtJava8      = "3.7.15"
  def uv                 = "0.13.10"
}
