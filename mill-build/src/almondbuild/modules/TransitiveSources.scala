package almondbuild.modules

import mill._
import mill.scalalib._

trait TransitiveSources extends SbtModule {
  def transitiveJars: T[Seq[PathRef]] = Task {
    Seq(jar()) ++ Task.traverse(moduleDeps) {
      case mod: TransitiveSources => mod.transitiveJars
      case mod                    => mod.jar.map(Seq(_))
    }().flatten
  }
  def transitiveSourceJars: T[Seq[PathRef]] = Task {
    Seq(sourceJar()) ++ Task.traverse(moduleDeps) {
      case mod: TransitiveSources => mod.transitiveSourceJars
      case mod                    => mod.sourceJar.map(Seq(_))
    }().flatten
  }
  def transitiveSources: T[Seq[PathRef]] = Task {
    sources() ++ Task.traverse(moduleDeps) {
      case mod: TransitiveSources => mod.transitiveSources
      case mod                    => mod.sources
    }().flatten
  }
}
