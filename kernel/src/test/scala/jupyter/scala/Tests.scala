package jupyter.scala

import ammonite.shell.tests

object AdvancedTests extends tests.AdvancedTests(
  {
    val c = ScalaInterpreterChecker()
    c.captureOut = true // to capture the output of calls to `show`
    c
  },
  hasMacros = false,
  isAmmonite = false,
  wrapper = wrapper
)

object AutocompleteTests extends tests.AutocompleteTests(
  ScalaInterpreterChecker(),
  checkSignatures = false
)

object EulerTests extends tests.EulerTests(
  ScalaInterpreterChecker()
)

object EvaluatorTests extends tests.EvaluatorTests(
  ScalaInterpreterChecker(),
  wrapper = wrapper
)

object FailureTests extends tests.FailureTests(
  ScalaInterpreterChecker()
)

object LocalSpark12Tests extends tests.LocalSparkTests(
  ScalaInterpreterChecker(),
  (1, 2),
  wrapper = wrapper
)
object LocalSpark13Tests extends tests.LocalSparkTests(
  ScalaInterpreterChecker(),
  (1, 3),
  wrapper = wrapper
)
object LocalSpark14Tests extends tests.LocalSparkTests(
  ScalaInterpreterChecker(),
  (1, 4),
  wrapper = wrapper
)
object LocalSpark15Tests extends tests.LocalSparkTests(
  ScalaInterpreterChecker(),
  (1, 5),
  wrapper = wrapper
)
object LocalSpark16Tests extends tests.LocalSparkTests(
  ScalaInterpreterChecker(),
  (1, 6),
  wrapper = wrapper
)

object ReflectionTests extends tests.ReflectionTests(
  ScalaInterpreterChecker(),
  classWrap = true
)

object SerializationTests extends tests.SerializationTests(
  ScalaInterpreterChecker(),
  expectReinitializing = false,
  wrapper = wrapper
)
