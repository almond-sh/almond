package almond.kernel

import almond.kernel.install.Install
import utest._

object InstallArgsTests extends TestSuite {

  val tests = Tests {

    test("remove install flags") {
      val args = List("--install", "--log", "info", "-f", "--global=true", "--copy-launcher=false")
      val res  = Install.withoutInstallArgs(args)
      val expectedRes = List("--log", "info")
      assert(res == expectedRes)
    }

    test("remove install options and their values") {
      val args = List(
        "--env",
        "SECRET=foo",
        "--metabrowse",
        "--env=OTHER_SECRET=bar",
        "--id",
        "scala213",
        "--display-name",
        "Scala 2.13",
        "-N",
        "Scala",
        "--logo=logo.png",
        "--predef-code",
        "val n = 2"
      )
      val res         = Install.withoutInstallArgs(args)
      val expectedRes = List("--metabrowse", "--predef-code", "val n = 2")
      assert(res == expectedRes)
    }

    test("keep arguments after --") {
      val args        = List("--install", "--", "--env", "FOO=bar")
      val res         = Install.withoutInstallArgs(args)
      val expectedRes = List("--", "--env", "FOO=bar")
      assert(res == expectedRes)
    }

    test("keep other options starting like install ones") {
      val args = List("--environment", "foo", "--ids=a", "--install-dir", "d")
      val res  = Install.withoutInstallArgs(args)
      assert(res == args)
    }

  }

}
