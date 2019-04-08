package almond

object TestUtil {

  def isScala211 =
    scala.util.Properties.versionNumberString.startsWith("2.11.")
  def isScala212 =
    scala.util.Properties.versionNumberString.startsWith("2.12.")

}
