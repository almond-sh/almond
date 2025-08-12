package almondbuild.modules

import mill._
import mill.javalib._

trait LocalRepo extends Module {

  def stubsModules: Seq[PublishModule]
  def version: T[String]

  def repoRoot = os.sub / "out" / "repo" / AlmondPublishModule.compileBuildVersion()

  def localRepo = {
    val tasks = stubsModules.map(_.publishLocal(repoRoot.toString, doc = false))
    Task.sequence(tasks)
  }
}
