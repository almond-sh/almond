package almondbuild.modules

import mill.*
import mill.api.*
import mill.scalalib.*

/** Ships the classes and sources of unpublished modules alongside ours.
  *
  * Rather than being published on their own, the modules in [[foldedModules]] have their classes
  * and resources added to our local compile class path - which puts them in our JAR, and on the
  * compile and run class paths of the modules depending on us - and their sources added to our
  * source JAR. Their dependencies become ours.
  *
  * The modules that those depend on are expected to be among our `moduleDeps`.
  */
trait FoldedModules extends TransitiveSources {

  def foldedModules: Seq[JavaModule]

  def compileModuleDeps = super.compileModuleDeps ++ foldedModules

  def localCompileClasspath = Task {
    super.localCompileClasspath() ++
      Task.traverse(foldedModules)(_.localRunClasspath)().flatten
  }
  // Added here rather than to mvnDeps, so that the latter can be defined without super.mvnDeps(),
  // and so that we can leave out the dependencies that it already has
  def mandatoryMvnDeps = Task {
    val mvnDeps0 = mvnDeps().toSet
    super.mandatoryMvnDeps() ++
      Task.traverse(foldedModules)(_.mvnDeps)().flatten.distinct.filterNot(mvnDeps0)
  }

  def sourceJar = Task {
    val foldedSources = Task.traverse(foldedModules) { mod =>
      Task.Anon(mod.allSources() ++ mod.resources() ++ mod.compileResources())
    }().flatten
    PathRef(
      mill.util.Jvm.createJar(
        Task.dest / "out.jar",
        (allSources() ++ resources() ++ compileResources() ++ foldedSources)
          .map(_.path)
          .filter(os.exists),
        manifest()
      )
    )
  }
  def transitiveSources = Task {
    super.transitiveSources() ++ Task.traverse(foldedModules)(_.sources)().flatten
  }
}
