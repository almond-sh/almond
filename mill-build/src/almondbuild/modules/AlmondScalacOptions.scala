package almondbuild.modules

import mill._
import mill.scalalib._

trait AlmondScalacOptions extends ScalaModule {
  def scalacOptions = Task {
    // see http://tpolecat.github.io/2017/04/25/scalac-flags.html
    val sv = scalaVersion()
    val scala2Options =
      if (sv.startsWith("2.")) Seq("-explaintypes")
      else Nil
    super.scalacOptions() ++ scala2Options ++ Seq(
      "-deprecation",
      "-feature",
      "-encoding",
      "utf-8",
      "-language:higherKinds",
      "-unchecked"
    )
  }
}
