package jupyter.scala

object AdvancedTests extends ammonite.shell.tests.AdvancedTests(
  ScalaInterpreterChecker(),
  hasMacros = false,
  isAmmonite = false
)