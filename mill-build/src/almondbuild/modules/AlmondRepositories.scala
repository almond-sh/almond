package almondbuild.modules

import mill._
import mill.scalalib._

trait AlmondRepositories extends CoursierModule {
  def repositoriesTask = Task.Anon {
    super.repositoriesTask() ++ Seq(
      coursier.Repositories.jitpack
    )
  }
}
