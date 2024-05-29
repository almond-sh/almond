package almond.integration

import munit.{Location, TestOptions}

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal

abstract class AlmondFunSuite extends munit.FunSuite {

  def mightRetry: Boolean   = false
  override def munitTimeout = 5.minutes

  override def test(options: TestOptions)(body: => Any)(implicit loc: Location): Unit = {
    val className = getClass.getName
    val (classNameInit, classNameLast) = {
      val a = className.split('.')
      (a.init, a.last)
    }
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
                s"Attempt $attempt of ${Console.RED}${classNameInit.mkString(".")}" + "." +
                  s"${Console.BOLD}$classNameLast${Console.RESET}${Console.RED}" + "." +
                  s"${Console.BOLD}${options.name}${Console.RESET} failed, trying again"
              )
              e.printStackTrace(System.err)
              runBody(attempt + 1)
          }
        }
        else
          body
      }

      System.err.println()
      System.err.println(
        s"${Console.BLUE}Running ${classNameInit.mkString(".")}" + "." +
          s"${Console.BOLD}$classNameLast${Console.RESET}${Console.BLUE}" + "." +
          s"${Console.BOLD}${options.name}${Console.RESET}"
      )
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
          System.err.println(
            s"${Console.CYAN}Done: ${classNameInit.mkString(".")}" + "." +
              s"${Console.BOLD}$classNameLast${Console.RESET}${Console.CYAN}" + "." +
              s"${Console.BOLD}${options.name}${Console.RESET}"
          )
        else {
          System.err.println(
            s"${Console.RED}Failed: ${classNameInit.mkString(".")}" + "." +
              s"${Console.BOLD}$classNameLast${Console.RESET}${Console.RED}" + "." +
              s"${Console.BOLD}${options.name}${Console.RESET}"
          )
          exOpt.foreach(_.printStackTrace(System.err))
        }
        System.err.println()
      }
    }(loc)
  }
}

object AlmondFunSuite {
  val maxRetriedTests           = if (System.getenv("CI") == null) 1 else 6
  def retryAttempts             = if (System.getenv("CI") == null) 1 else 3
  private val retriedTestsCount = new AtomicInteger
}
