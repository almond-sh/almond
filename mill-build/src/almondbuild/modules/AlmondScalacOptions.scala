package almondbuild.modules

import mill.*
import mill.api.*
import mill.scalalib.*

trait AlmondScalacOptions extends ScalaModule {

  /** The options we pass to scalac in every module, whether published or not. */
  def almondScalacOptions = Task {
    // see http://tpolecat.github.io/2017/04/25/scalac-flags.html
    val sv = scalaVersion()
    val scala2Options =
      if (sv.startsWith("2.")) Seq("-explaintypes")
      else Nil
    scala2Options ++ Seq(
      "-deprecation",
      "-feature",
      "-encoding",
      "utf-8",
      "-language:higherKinds",
      "-unchecked"
    )
  }
  def scalacOptions = Task {
    super.scalacOptions() ++ almondScalacOptions()
  }
}
