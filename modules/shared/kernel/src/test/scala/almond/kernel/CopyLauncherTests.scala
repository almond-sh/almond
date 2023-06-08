package almond.kernel

import almond.kernel.install.Install
import utest._

import java.io.File

object CopyLauncherTests extends TestSuite {

  val tests = Tests {

    test("java -jar") {
      val cmd         = List("java", "-jar", "foo", "arg1", "arg2")
      val res         = Install.launcherPos(cmd)
      val expectedRes = Some(("foo", 2))
      assert(res == expectedRes)
    }

    test("java -jar with options") {
      val cmd         = List("java", "-Xmx15T", "-Dmode=lightweight", "-jar", "foo", "arg1", "arg2")
      val res         = Install.launcherPos(cmd)
      val expectedRes = Some(("foo", 4))
      assert(res == expectedRes)
    }

    test("java -cp") {
      val sep         = File.pathSeparator
      val cmd         = List("java", "-cp", Seq("foo", "other").mkString(sep), "arg1", "arg2")
      val res         = Install.launcherPos(cmd)
      val expectedRes = Some(("other", 2))
      assert(res == expectedRes)
    }

    test("no java: nope") {
      val cmd         = List("zava", "-cp", "foo:other", "arg1", "arg2")
      val res         = Install.launcherPos(cmd)
      val expectedRes = None
      assert(res == expectedRes)
    }

  }

}
