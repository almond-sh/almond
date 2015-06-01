package jupyter.scala

object SerializationTests extends ammonite.shell.tests.SerializationTests(
  ScalaInterpreterChecker(),
  expectReinitializing = false
)