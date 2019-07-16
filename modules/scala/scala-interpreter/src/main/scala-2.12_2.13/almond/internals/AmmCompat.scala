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

  private def module(mod: coursierapi.Module): coursier.core.Module =
    coursier.core.Module(
      coursier.core.Organization(mod.getOrganization),
      coursier.core.ModuleName(mod.getName),
      Map.empty // ignored for now
    )

  def addAutomaticDependencies(
    interp: Interpreter,
    automaticDependencies: Map[coursier.core.Module, scala.Seq[coursier.core.Dependency]],
    automaticDependenciesMatchers: Seq[(coursier.util.ModuleMatcher, scala.Seq[coursier.core.Dependency])],
    automaticVersions: Map[coursier.core.Module, String]
  ): Unit =
    interp.resolutionHooks += { f =>
      val extraDependencies = f.getDependencies
        .asScala
        .toVector
        .flatMap { dep =>
          val mod = module(dep.getModule)
          automaticDependencies.getOrElse(
            mod,
            automaticDependenciesMatchers
              .find(_._1.matches(mod))
              .map(_._2)
              .getOrElse(Nil)
          )
        }
        .map { dep =>
          // other fields ignored for now
          coursierapi.Dependency.of(
            dep.module.organization.value,
            dep.module.name.value,
            dep.version
          )
        }
      val f0 = f.addDependencies(extraDependencies: _*)

      val deps = f0.getDependencies.asScala.toVector
      if (deps.exists(_.getVersion == "_")) {
        val dependencies0 = deps.map { dep =>
          if (dep.getVersion == "_") {
            automaticVersions.get(module(dep.getModule)) match {
              case None =>
                System.err.println(
                  s"Warning: version ${"\"_\""} specified for ${dep.getModule}, " +
                    "but no automatic version available for it"
                )
                dep
              case Some(ver) =>
                dep.withVersion(ver)
            }
          } else
            dep
        }
        f0.withDependencies(dependencies0: _*)
      } else
        f0
    }

}
