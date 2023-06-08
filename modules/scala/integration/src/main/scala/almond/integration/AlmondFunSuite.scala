package almond.integration

import munit.{Location, TestOptions}

import java.util.concurrent.atomic.AtomicInteger

import scala.util.control.NonFatal

abstract class AlmondFunSuite extends munit.FunSuite {

  def mightRetry: Boolean = false

  override def test(options: TestOptions)(body: => Any)(implicit loc: Location): Unit =
    super.test(options) {

      def runBody(attempt: Int): Any = {
        val mightRetry0 =
          mightRetry &&
          attempt < AlmondFunSuite.retryAttempts &&
          AlmondFunSuite.retriedTestsCount.get() < AlmondFunSuite.maxRetriedTests
        if (mightRetry0) {
          val resOpt =
            try Right(body)
            catch {
              case NonFatal(e) =>
                Left(e)
            }
          resOpt match {
            case Right(res) => res
            case Left(e) =>
              if (attempt == 1)
                AlmondFunSuite.retriedTestsCount.incrementAndGet()
              System.err.println(
                s"Attempt $attempt of ${Console.RED}${options.name}${Console.RESET} failed, trying again"
              )
              runBody(attempt + 1)
          }
        }
        else
          body
      }

      System.err.println()
      System.err.println(s"Running ${Console.BLUE}${options.name}${Console.RESET}")
      var success = false
      var exOpt   = Option.empty[Throwable]
      try {
        runBody(1)
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

object AlmondFunSuite {
  val maxRetriedTests           = if (System.getenv("CI") == null) 1 else 6
  def retryAttempts             = 3
  private val retriedTestsCount = new AtomicInteger
}
