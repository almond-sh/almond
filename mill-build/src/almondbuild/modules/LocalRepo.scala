package almondbuild.modules

import mill.*
import mill.api.*
import mill.javalib.*

trait LocalRepo extends Module {

  def stubsModules: Seq[PublishModule]
  def version: T[String]

  def repoRoot = os.sub / "out" / "repo" / AlmondPublishModule.computeBuildVersion()

  def localRepo = {
    val tasks = stubsModules.map(_.publishLocal(repoRoot.toString, doc = false))
    Task.sequence(tasks)
  }
}
