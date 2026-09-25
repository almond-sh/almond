package almond

import utest._

object RepositoryParsingTests extends TestSuite {

  val tests = Tests {

    test("url") {
      val repo = Execute.parseRepository("https://jitpack.io")
      assert(repo == Right(coursierapi.MavenRepository.of("https://jitpack.io")))
    }

    test("predefined") {
      // those are accepted by Coursier and Scala CLI, and documented by the
      // //> using repository directive
      for (input <- Seq("m2Local", "ivy2Local", "jitpack", "sonatype:snapshots"))
        assert(Execute.parseRepository(input).isRight)
    }

    test("ivy prefix") {
      val repo = Execute.parseRepository("ivy:file:///tmp/repo/[defaultPattern]")
      assert(repo.exists(_.isInstanceOf[coursierapi.IvyRepository]))
    }

    test("invalid") {
      val res = Execute.parseRepository("not-a-repository")
      assert(res.isLeft)
    }
  }
}
