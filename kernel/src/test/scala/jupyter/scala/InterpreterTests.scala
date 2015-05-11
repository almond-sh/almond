package jupyter.scala

import utest._

object InterpreterTests extends TestSuite {
  val tests = TestSuite{
    val check = ScalaInterpreterChecker()

    'simpleExpressions{
      check.session("""
        @ 1 + 2
        res0: Int = 3

        @ res0
        res1: Int = 3

        @ res0 + res1
        res2: Int = 6
      """)
    }
    'vals{
      check.session("""
        @ val x = 10L
        x: Long = 10L

        @ x
        res1: Long = 10L

        @ val y = x + 1
        y: Long = 11L

        @ x * y
        res3: Long = 110L

        @ val `class` = "class"
        `class`: java.lang.String = "class"

        @ `class`
        res5: java.lang.String = "class"
                    """)
    }
  }

}

object EvaluatorTests extends ammonite.shell.tests.EvaluatorTests(ScalaInterpreterChecker())