package almondbuild

import coursier.version.Version

object ScalaVersions {
  def scala3Latest = "3.9.0"
  def scala3Compat = "3.3.8" // scala-steward:off
  def scala213     = "2.13.18"
  def scala212     = "2.12.21"

  // Published modules are built once per binary Scala version, with the oldest full Scala
  // version we support for it: Scala 2 patch releases are only forward binary compatible, so
  // building scala-interpreter_2.13 with 2.13.3 gives an artifact all the 2.13.x versions we
  // support can run, while building it with 2.13.18 wouldn't. For Scala 3, the LTS plays that
  // role, its TASTy files being readable by all the later Scala 3 versions we support.
  //
  // These are the oldest Scala 2 versions our Ammonite fork supports, whose modules are built
  // with them too (see the scala2_12Versions / scala2_13Versions of its build.mill).
  def scala213Oldest = "2.13.3"
  def scala212Oldest = "2.12.8"

  val binaries       = Seq(scala3Compat, scala213Oldest, scala212Oldest)
  val scala2Binaries = Seq(scala213Oldest, scala212Oldest)

  /** All the patch versions of a Scala 2 binary version between two of them, latest first */
  private def patchVersions(oldest: String, latest: String): Seq[String] = {
    val binary = oldest.split('.').take(2).mkString(".")
    assert(latest.startsWith(binary + "."), s"$latest is not a $binary version")
    val from = oldest.stripPrefix(binary + ".").toInt
    val to   = latest.stripPrefix(binary + ".").toInt
    (to to from by -1).map(patch => s"$binary.$patch")
  }

  // We support all the Scala 2 patch versions between the oldest and the latest of each binary
  // version - our Ammonite fork does too, and the kernel runs with the one users pick.
  val all = Seq(
    scala3Latest,
    scala3Compat
  ) ++ patchVersions(scala213Oldest, scala213) ++ patchVersions(scala212Oldest, scala212)

  // The Scala versions CI runs the full-Scala-version-crossed tests with: all the Scala 3 ones,
  // and, for each Scala 2 binary version, the two latest patch versions, along with the oldest
  // one, that the modules published for that binary version are built with.
  val ci = {
    val (scala2, scala3) = all.partition(_.startsWith("2."))
    val scala2Subset = scala2
      .groupBy(binary)
      .values
      .flatMap { versions =>
        val sorted = versions.sortBy(Version(_))(using Ordering[Version].reverse)
        sorted.take(2) :+ sorted.last
      }
      .toSeq
      .distinct
    (scala3 ++ scala2Subset).sortBy(Version(_))(using Ordering[Version].reverse)
  }

  /** The Scala versions the example notebooks of `examples` are run with.
    *
    * The notebooks there record the outputs of the first one, the reference version, that's not
    * bumped along with [[scala212]] so that they don't need to be updated every time. The other
    * ones get the same outputs, but for the notebooks that have a copy for their binary version in
    * `examples/scala-2.13` or `examples/scala-3`, that are run and compared with instead.
    */
  val examples = Seq("2.12.20", scala213, scala3Latest)

  /** The [[binaries]] entry a full Scala version's modules are built under. */
  def binary(sv: String) =
    if (sv.startsWith("2.12.")) scala212Oldest
    else if (sv.startsWith("2.13.")) scala213Oldest
    else scala3Compat

  /** The suffix the modules we publish carry for a full Scala version. */
  def binarySuffix(sv: String) =
    if (sv.startsWith("3.")) "3"
    else sv.split('.').take(2).mkString(".")

  /** The full Scala versions the tests of a [[binaries]] module instance run with. */
  def fullVersionsFor(binaryScalaVersion: String): Seq[String] =
    all.filter(binary(_) == binaryScalaVersion)

  /** The JVM to run the compiler of a full Scala version on, as a coursier JVM id, when the one the
    * build runs on is too recent for it: the Scala 2.12.x before 2.12.18 and the 2.13.x before
    * 2.13.11 can't read the class files of JDK 21 (they fail with "bad constant pool index" as soon
    * as they load java.lang.String). We build and test them on JDK 17, the oldest JVM Mill runs
    * scalac on: its compiler worker is built for Java 17, so for older JVMs, like JDK 8, Mill runs
    * scalac on its own JVM and only passes it -release. The kernels running these versions need a
    * JDK at most 17 too.
    *
    * 2.12.8 needs more than that: its macro class loader calls toUri on the jrt:/packages
    * directory, which JDK 15+ reject, so it can't expand macros there - see
    * [[almondbuild.Scala212_8JrtPatch]] for the work-around.
    */
  def jvmVersionFor(sv: String): Option[String] = {
    val needsJvm17 =
      (sv.startsWith("2.12.") && Version(sv) < Version("2.12.18")) ||
      (sv.startsWith("2.13.") && Version(sv) < Version("2.13.11"))
    if (needsJvm17) Some("17")
    else None
  }

  /** The oldest major Java version kernels running a full Scala version support, as a coursier JVM
    * id (that gets the JVM of coursier's default vendor). The tests run their kernels on it, so
    * that they check that version is actually supported.
    *
    * The modules we publish target Java 8 (see [[almondbuild.modules.AlmondJvmTarget]]), but some
    * of the classes the kernels load need more recent JVMs: those of Scala 3.8 and later need Java
    * 17, and so do the classes of our Ammonite fork specific to Scala 3.4.2 and later, while its
    * other Scala 3 classes, and those specific to Scala 2.13.12 and later, need Java 11.
    */
  def oldestJvmFor(sv: String): String = {
    val v = Version(sv)
    if (sv.startsWith("3.") && v >= Version("3.4.2")) "17"
    else if (sv.startsWith("3.") || (sv.startsWith("2.13.") && v >= Version("2.13.12"))) "11"
    else "8"
  }

}
