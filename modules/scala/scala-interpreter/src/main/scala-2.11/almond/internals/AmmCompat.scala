package almond.internals

import ammonite.interp.{CompilerLifecycleManager, Interpreter}
import ammonite.runtime.{Frame, Storage}
import coursier.almond.tmp.Tmp

object AmmCompat {

  type History = ammonite.runtime.History
  type FrontEnd = ammonite.repl.FrontEnd
  type ReplAPI = ammonite.repl.ReplAPI
  type ReplLoad = ammonite.repl.ReplLoad

  class CustomCompilerLifecycleManager(
    storage: Storage,
    headFrame: => Frame,
    dependencyCompleteOpt: => Option[String => (Int, Seq[String])]
  ) extends CompilerLifecycleManager(storage, headFrame, dependencyCompleteOpt)

  def addMavenRepositories(interp: Interpreter, repo: Seq[String]): Unit =
    interp.repositories() = interp.repositories() ++ repo.map(coursier.maven.MavenRepository(_))

  def forceMavenProperties(interp: Interpreter, props: Map[String, String]): Unit = {
    interp.resolutionHooks += { fetch =>
      val params0 = Tmp.resolutionParams(fetch)
      val params = params0
        .withForcedProperties(params0.forcedProperties ++ props)
      fetch.withResolutionParams(params)
    }
  }

  def mavenProfiles(interp: Interpreter, mavenProfiles: Map[String, Boolean]): Unit = {
    interp.resolutionHooks += { fetch =>
      val mavenProfiles0 = mavenProfiles.toVector.map {
        case (p, true) => p
        case (p, false) => "!" + p
      }
      val params0 = Tmp.resolutionParams(fetch)
      val params = params0
        .withProfiles(params0.profiles ++ mavenProfiles0)
      fetch.withResolutionParams(params)
    }
  }

  def addAutomaticDependencies(
    interp: Interpreter,
    automaticDependencies: Map[coursier.core.Module, scala.Seq[coursier.core.Dependency]],
    automaticDependenciesMatchers: Seq[(coursier.util.ModuleMatcher, scala.Seq[coursier.core.Dependency])],
    automaticVersions: Map[coursier.core.Module, String]
  ): Unit = {
    interp.resolutionHooks += { f =>
      val extraDependencies = f.dependencies.flatMap { dep =>
        automaticDependencies.getOrElse(
          dep.module,
          automaticDependenciesMatchers
            .find(_._1.matches(dep.module))
            .map(_._2)
            .getOrElse(Nil)
        )
      }
      val f0 = f.addDependencies(extraDependencies: _*)
      val deps = f0.dependencies
      if (deps.exists(_.version == "_")) {
        val dependencies0 = deps.map { dep =>
          if (dep.version == "_") {
            automaticVersions.get(dep.module) match {
              case None =>
                System.err.println(
                  s"Warning: version ${"\"_\""} specified for ${dep.module.repr}, " +
                    "but no automatic version available for it"
                )
                dep
              case Some(ver) =>
                dep.withVersion(ver)
            }
          } else
            dep
        }
        f0.withDependencies(dependencies0)
      } else
        f0
    }
  }

}
