package jupyter.scala

object EvaluatorTests extends ammonite.shell.tests.EvaluatorTests(
  ScalaInterpreterChecker(),
  wrapperInstance = (ref, cur) => s"cmd$cur.INSTANCE.$$ref$$cmd$ref"
)