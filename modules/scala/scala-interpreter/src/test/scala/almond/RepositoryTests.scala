package almond

import almond.interpreter.api.ExecuteResult
import almond.testkit.TestLogging.logCtx
import almond.TestUtil._
import utest._

object RepositoryTests extends TestSuite {

  private def newInterpreter(extraRepos: Seq[String] = Nil): ScalaInterpreter =
    new ScalaInterpreter(
      params = interpreterParams.copy(extraRepos = extraRepos),
      logCtx = logCtx
    )

  // URLs of the session repositories, Ivy ones being prefixed with "ivy:"
  private def repositories(interp: ScalaInterpreter): Seq[String] = {
    val res = interp.execute(
      """almond.RepositoryTestsHelper.repositories.set(
        |  interp.repositories().map {
        |    case m: coursierapi.MavenRepository => m.getBase
        |    case i: coursierapi.IvyRepository   => "ivy:" + i.getPattern
        |    case other                          => other.toString
        |  }
        |)
        |""".stripMargin
    )
    assert(res.isInstanceOf[ExecuteResult.Success])
    RepositoryTestsHelper.repositories.get()
  }

  private def addedRepositories(interp: ScalaInterpreter, code: String): Seq[String] = {
    val before = repositories(interp)
    val res    = interp.execute(code)
    assert(res.isInstanceOf[ExecuteResult.Success])
    val after = repositories(interp)
    assert(after.startsWith(before))
    after.drop(before.length)
  }

  private lazy val m2Local =
    new java.io.File(sys.props("user.home"), ".m2/repository").toURI.toASCIIString
      .stripSuffix("/")

  val tests = Tests {

    test("using repository URL") {
      val interp = newInterpreter()
      val added  = addedRepositories(interp, "//> using repository https://foo.com/maven")
      assert(added == Seq("https://foo.com/maven"))
    }

    test("using repository ivy pattern") {
      val interp = newInterpreter()
      val added = addedRepositories(
        interp,
        "//> using repository ivy:https://foo.com/ivy/[defaultPattern]"
      )
      assert(added.length == 1)
      assert(added.head.startsWith("ivy:https://foo.com/ivy/"))
      assert(!added.head.contains("[defaultPattern]"))
    }

    test("using repository predefined") {
      val interp = newInterpreter()
      val added = addedRepositories(
        interp,
        "//> using repository m2Local jitpack sonatype:snapshots"
      )
      val expected = Seq(
        m2Local,
        "https://jitpack.io",
        "https://oss.sonatype.org/content/repositories/snapshots"
      )
      assert(added == expected)
    }

    test("using repository invalid") {
      val interp = newInterpreter()
      val before = repositories(interp)
      val res    = interp.execute("//> using repository not-a-repository")
      val msg = res match {
        case e: ExecuteResult.Error => e.message
        case other                  => sys.error(s"Expected error, got $other")
      }
      assert(msg.contains("Error parsing repository 'not-a-repository'"))
      assert(repositories(interp) == before)
    }

    test("extra repositories") {
      val interp = newInterpreter(
        extraRepos = Seq("https://foo.com/maven", "m2Local", "sonatype:snapshots")
      )
      val repos = repositories(interp)
      assert(repos.contains("https://foo.com/maven"))
      assert(repos.contains(m2Local))
      assert(repos.contains("https://oss.sonatype.org/content/repositories/snapshots"))
      assert(!repos.contains("m2Local"))
      assert(!repos.contains("sonatype:snapshots"))
    }

    test("extra repositories invalid") {
      val interp = newInterpreter(
        extraRepos = Seq("not-a-repository", "https://foo.com/maven")
      )
      val repos = repositories(interp)
      assert(repos.contains("https://foo.com/maven"))
      assert(!repos.exists(_.contains("not-a-repository")))
    }
  }
}

object RepositoryTestsHelper {
  val repositories = new java.util.concurrent.atomic.AtomicReference[Seq[String]](Nil)
}
