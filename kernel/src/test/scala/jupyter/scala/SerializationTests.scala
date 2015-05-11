package jupyter.scala

object SerializationTests extends ammonite.shell.tests.SerializationTests(
  ScalaInterpreterChecker(),
  wrapperInstance = (ref, cur) => s"cmd$cur.INSTANCE.$$ref$$cmd$ref",
  expectReinitializing = false
)