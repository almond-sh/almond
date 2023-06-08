package almond.integration

import almond.testkit.Dsl._
import munit.{Location, TestOptions}
import scala.util.control.NonFatal

abstract class KernelTestsDefinitions extends munit.FunSuite {

  def kernelLauncher: KernelLauncher

  override def test(options: TestOptions)(body: => Any)(implicit loc: Location): Unit =
    super.test(options) {
      System.err.println()
      System.err.println(s"Running ${Console.BLUE}${options.name}${Console.RESET}")
      var success = false
      var exOpt   = Option.empty[Throwable]
      try {
        body
        success = true
      }
      catch {
        case NonFatal(e) =>
          exOpt = Some(e)
          throw e
      }
      finally {
        if (success)
          System.err.println(s"Done: ${Console.CYAN}${options.name}${Console.RESET}")
        else {
          System.err.println(s"Failed: ${Console.RED}${options.name}${Console.RESET}")
          exOpt.foreach(_.printStackTrace(System.err))
        }
        System.err.println()
      }
    }(loc)

  test("jvm-repr") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.jvmRepr()
    }
  }

  test("updatable display") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.updatableDisplay()
    }
  }

  test("auto-update Future results upon completion") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.autoUpdateFutureUponCompletion(kernelLauncher.defaultScalaVersion)
    }
  }

  test("auto-update Future results in background upon completion") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.autoUpdateFutureInBackgroundUponCompletion(
        kernelLauncher.defaultScalaVersion
      )
    }
  }

  test("toree AddJar file") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarFile(kernelLauncher.defaultScalaVersion, sameCell = false)
    }
  }

  test("toree AddJar file same cell") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarFile(kernelLauncher.defaultScalaVersion, sameCell = true)
    }
  }

  test("toree AddJar URL") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarURL(kernelLauncher.defaultScalaVersion, sameCell = false)
    }
  }

  test("toree AddJar URL same cell") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarURL(kernelLauncher.defaultScalaVersion, sameCell = true)
    }
  }

  test("toree AddJar custom protocol") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarCustomProtocol(kernelLauncher.defaultScalaVersion)
    }
  }

  test("toree custom cell magic") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeCustomCellMagic()
    }
  }

  test("compile only") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.compileOnly()
    }
  }

  if (kernelLauncher.defaultScalaVersion.startsWith("2."))
    test("extra class path") {
      kernelLauncher.withKernel { implicit runner =>
        implicit val sessionId: SessionId = SessionId()
        almond.integration.Tests.extraCp(kernelLauncher.defaultScalaVersion)
      }
    }

  if (kernelLauncher.defaultScalaVersion.startsWith("2."))
    test("inspections") {
      kernelLauncher.withKernel { implicit runner =>
        implicit val sessionId: SessionId = SessionId()
        almond.integration.Tests.inspections(kernelLauncher.defaultScalaVersion)
      }
    }

}
