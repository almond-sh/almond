package almond.testkit

import almond.logger.{Level, LoggerContext}
import io.github.alexarchambault.testutil.TestOutput

object TestLogging {
  private lazy val loggingLevel =
    sys.env.get("ALMOND_TEST_LOGGING")
      .orElse(sys.props.get("almond.test.logging"))
      .map { s =>
        Level.fromString(s) match {
          case Left(e) =>
            sys.error(s"Parsing log level '$s': $e")
          case Right(l) => l
        }
      }
      .getOrElse(
        Level.Debug
      )

  def logCtxForOutput(output: TestOutput) =
    LoggerContext.printStream(loggingLevel, output.printStream)
  val logCtx =
    LoggerContext.stderr(loggingLevel)
}
