package almond

/** Ad-hoc logging library.
  *
  * Its motivations, compared to logback and all, are two-fold:
  *   - easier to adjust its configuration programmatically (e.g. from command-line options),
  *     without tangling with Java properties, etc.
  *   - easier to have it log to a given [[java.io.PrintStream]]: when user code is running in the
  *     Scala kernel, [[System.out]] and all are set to ad hoc [[java.io.PrintStream]] instances,
  *     that capture the user code output. If the logging framework also sends its logs there, these
  *     appear in the notebook, which is not what we want (we'd like the logs to still go in the
  *     console). With this logging library, we can initially fix the [[java.io.PrintStream]], so
  *     that logs are indeed always sent to the console.
  */
package object logger
