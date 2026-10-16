package almond

import almond.kernel.install.{Install, Options => InstallOptions}
import caseapp.core.parser.Parser
import caseapp.core.util.NameOps.toNameOps
import utest._

/** Checks that [[Install.withoutInstallArgs]] is in sync with the install options */
object InstallArgsTests extends TestSuite {

  private val installParser: Parser[InstallOptions] = Parser.derive
  private val parser                                = Parser[Options]

  private def options(parser: Parser[_]) =
    for {
      arg  <- parser.args
      name <- arg.names
    } yield (name.option(parser.defaultNameFormatter), arg.isFlag)

  private val installOptions = options(installParser) ++
    options(parser).filter(_._1 == "--install")

  val tests = Tests {

    test("install options are removed") {
      assert(installOptions.exists(_._1 == "--install"))
      for ((opt, isFlag) <- installOptions) {
        val args =
          if (isFlag) Seq(List(opt, "--keep"), List(s"$opt=true", "--keep"))
          else Seq(List(opt, "value", "--keep"), List(s"$opt=value", "--keep"))
        for (args0 <- args) {
          val res = Install.withoutInstallArgs(args0)
          assert(res == List("--keep"))
        }
      }
    }

    test("other options are kept") {
      val installOptionNames = installOptions.map(_._1).toSet
      val otherOptions       = options(parser).filter(t => !installOptionNames(t._1))
      assert(otherOptions.nonEmpty)
      for ((opt, _) <- otherOptions) {
        val args = List(opt, "value")
        val res  = Install.withoutInstallArgs(args)
        assert(res == args)
      }
    }

  }

}
