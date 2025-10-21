package almondbuild.modules

import mill.*
import mill.api.*
import mill.scalalib.*

trait AlmondRepositories extends CoursierModule {
  def repositories = Task {
    super.repositories() ++ Seq(
      "jitpack"
    )
  }
}
