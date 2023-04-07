package almond.logger

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.regex.Pattern

import utest._

object LoggerTests extends TestSuite {

  private def noCrLf(input: String): String =
    input.replace("\r\n", "\n")

  val tests = Tests {

    test("nop") {

      val log = Logger.nop

      log.debug(sys.error("not called"))
      log.info(sys.error("not called"))
      log.warn(sys.error("not called"))
      log.error(sys.error("not called"))
    }

    test("PrintStream") {

      test("nop") {
        val b   = new ByteArrayOutputStream
        val ps  = new PrintStream(b)
        val log = Logger.printStream(Level.None, ps, colored = false)

        log.debug(sys.error("not called"))
        log.info(sys.error("not called"))
        log.warn(sys.error("not called"))
        log.error(sys.error("not called"))

        ps.close()

        val res = b.toString
        assert(res.isEmpty)
      }

      test("warn") {
        val b   = new ByteArrayOutputStream
        val ps  = new PrintStream(b)
        val log = Logger.printStream(Level.Warning, ps, colored = false)

        log.debug(sys.error("not called"))
        log.info(sys.error("not called"))
        log.warn("/o\\ warn")
        log.error("/o\\ Errr")

        ps.close()

        val expectedRes =
          """WARN /o\ warn
            |ERROR /o\ Errr
            |""".stripMargin

        val res = b.toString
        assert(noCrLf(res) == noCrLf(expectedRes))
      }

      test("debug") {
        val b   = new ByteArrayOutputStream
        val ps  = new PrintStream(b)
        val log = Logger.printStream(Level.Debug, ps, colored = false)

        val n = 2

        log.debug(s"n=$n")
        log.info("test ok")
        log.warn("/o\\ warn")
        log.error("/o\\ Errr")

        ps.close()

        val expectedRes =
          """DEBUG n=2
            |INFO test ok
            |WARN /o\ warn
            |ERROR /o\ Errr
            |""".stripMargin

        val res = b.toString
        assert(noCrLf(res) == noCrLf(expectedRes))
      }

      test("with exceptions") {
        val b   = new ByteArrayOutputStream
        val ps  = new PrintStream(b)
        val log = Logger.printStream(Level.Error, ps, colored = false)

        val n = 2

        val ex0 = new Exception("first")
        val ex  = new Exception("nope", ex0)

        log.error("/o\\ Errr", ex)

        ps.close()

        val expectedRes =
          if (scala.util.Properties.versionNumberString.startsWith("2.11."))
            """ERROR /o\ Errr
              |java.lang.Exception: nope
              |  almond.logger.LoggerTests(LoggerTests.scala:94)
              |  almond.logger.LoggerTests(LoggerTests.scala:10)
              |java.lang.Exception: first
              |  almond.logger.LoggerTests(LoggerTests.scala:93)
              |  almond.logger.LoggerTests(LoggerTests.scala:10)
              |""".stripMargin
          else
            """ERROR /o\ Errr
              |java.lang.Exception: nope
              |  almond.logger.LoggerTests(LoggerTests.scala:97)
              |java.lang.Exception: first
              |  almond.logger.LoggerTests(LoggerTests.scala:96)
              |""".stripMargin

        val res = b
          .toString
          .linesIterator
          .flatMap { s =>
            if (s.startsWith("  almond."))
              Iterator(s.replaceFirst(
                Pattern.quote("LoggerTests$") + ".*" + Pattern.quote("("),
                "LoggerTests("
              ))
            else if (s.startsWith("  "))
              Iterator.empty
            else
              Iterator(s)
          }
          .mkString("", "\n", "\n")

        assert(noCrLf(res) == noCrLf(expectedRes))
      }

    }

  }

}
