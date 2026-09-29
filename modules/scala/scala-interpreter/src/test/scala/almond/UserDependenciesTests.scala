package almond

import almond.amm.{AlmondCompilerLifecycleManager, UserDependencies}
import utest._

object UserDependenciesTests extends TestSuite {
  val tests = Tests {

    test("pick") {
      val known = Seq("3.9.0", "3.3.8", "2.13.18", "2.13.11", "2.13.3", "2.12.21", "2.12.8")
      test("known") {
        assert(UserDependencies.pick(known, "2.13.11") == Some("2.13.11"))
        assert(UserDependencies.pick(known, "3.3.8") == Some("3.3.8"))
      }
      test("closest earlier one") {
        assert(UserDependencies.pick(known, "2.13.12") == Some("2.13.11"))
        assert(UserDependencies.pick(known, "2.13.19") == Some("2.13.18"))
        assert(UserDependencies.pick(known, "3.5.2") == Some("3.3.8"))
        assert(UserDependencies.pick(known, "3.10.0-RC1") == Some("3.9.0"))
      }
      test("none") {
        assert(UserDependencies.pick(known, "2.13.2") == None)
        assert(UserDependencies.pick(known, "2.11.12") == None)
        assert(UserDependencies.pick(Nil, "2.13.18") == None)
      }
    }

    // The tests run with each Scala version scala-kernel-api ships a list for
    test("list for the current Scala version") {
      val sv = AlmondCompilerLifecycleManager.compilerVersion
      assert(UserDependencies.knownScalaVersions().contains(sv))
      val name = UserDependencies.resourceName(sv)
      assert(name == s"almond/almond-user-dependencies-$sv.txt")
      val deps = ammonite.main.Defaults.alreadyLoadedDependencies(name)
      val compilerModule =
        if (sv.startsWith("2.")) ("org.scala-lang", "scala-compiler")
        else ("org.scala-lang", "scala3-compiler_3")
      val compilerDeps = deps.filter { dep =>
        (dep.getModule.getOrganization, dep.getModule.getName) == compilerModule
      }
      assert(compilerDeps.map(_.getVersion) == Seq(sv))
    }
  }
}
