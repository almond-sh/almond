package almond.integration

import munit.{Location, TestOptions}

import scala.util.control.NonFatal

abstract class AlmondFunSuite extends munit.FunSuite {

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

}
