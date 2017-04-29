import sbt._
import sbt.Keys._

object Aliases {

  def libs = libraryDependencies

  def root = file(".")

}
