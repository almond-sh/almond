package almond.internals

import ammonite.interp.{CompilerLifecycleManager, Interpreter}
import ammonite.runtime.{Frame, Storage}

import scala.collection.JavaConverters._

object AmmCompat {

  type History = ammonite.repl.api.History
  type FrontEnd = ammonite.repl.api.FrontEnd
  type ReplAPI = ammonite.repl.api.ReplAPI
  type ReplLoad = ammonite.repl.api.ReplLoad

  class CustomCompilerLifecycleManager(
    storage: Storage,
    headFrame: => Frame,
    dependencyCompleteOpt: => Option[String => (Int, Seq[String])]
  ) extends CompilerLifecycleManager(storage, headFrame, dependencyCompleteOpt, Set.empty, headFrame.classloader)

  def addMavenRepositories(interp: Interpreter, repo: Seq[String]): Unit =
    interp.repositories() = interp.repositories() ++ repo.map(coursierapi.MavenRepository.of(_))

  def forceMavenProperties(interp: Interpreter, props: Map[String, String]): Unit = {
    interp.resolutionHooks += { fetch =>
      fetch.withResolutionParams(
        fetch
          .getResolutionParams
          .forceProperties(props.asJava)
      )
    }
  }

  def mavenProfiles(interp: Interpreter, mavenProfiles: Map[String, Boolean]): Unit = {
    interp.resolutionHooks += { fetch =>
      val mavenProfiles0 = mavenProfiles.toVector.map {
        case (p, true) => p
        case (p, false) => "!" + p
      }
      fetch.withResolutionParams(
        mavenProfiles0.foldLeft(fetch.getResolutionParams)(_.addProfile(_))
      )
    }
  }

  def addAutomaticDependencies(interp: Interpreter, automaticDependencies: Map[coursier.core.Module, scala.Seq[coursier.core.Dependency]]): Unit = {
    interp.resolutionHooks += { f =>
      val extraDependencies = f.getDependencies
        .asScala
        .toVector
        .flatMap { dep =>
          val mod = coursier.core.Module(
            coursier.core.Organization(dep.getModule.getOrganization),
            coursier.core.ModuleName(dep.getModule.getName),
            Map.empty // ignored for now
          )
          automaticDependencies.getOrElse(mod, Nil)
        }
        .map { dep =>
          // other fields ignored for now
          coursierapi.Dependency.of(
            dep.module.organization.value,
            dep.module.name.value,
            dep.version
          )
        }
      f.addDependencies(extraDependencies: _*)
    }
  }

}
