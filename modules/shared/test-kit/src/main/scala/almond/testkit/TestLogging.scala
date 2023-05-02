package almond.testkit

import almond.logger.{Level, LoggerContext}

object TestLogging {
  val logCtx = LoggerContext.stderr(
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
  )
}
