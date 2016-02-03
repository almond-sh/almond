package jupyter.scala

import ammonite.shell.tests

object LocalSpark11Tests extends tests.LocalSparkTests(
  ScalaInterpreterChecker(),
  "1.1.1",
  wrapper = wrapper
)