package almond

import almond.TestUtil.SessionRunner
import almond.amm.AlmondPreprocessor
import almond.kernel.KernelThreads
import almond.util.ThreadUtil.{attemptShutdownExecutionContext, singleThreadedExecutionContext}
import utest._

object EvaluatorTests extends TestSuite {

  val interpreterEc = singleThreadedExecutionContext("test-interpreter")
  val bgVarEc = singleThreadedExecutionContext("test-bg-var")

  val threads = KernelThreads.create("test")

  override def utestAfterAll() = {
    threads.attemptShutdown()
    if (!attemptShutdownExecutionContext(interpreterEc))
      println(s"Don't know how to shutdown $interpreterEc")
  }

  val runner = new SessionRunner(interpreterEc, bgVarEc, threads)

  def ifVarUpdates(s: String): String =
    if (AlmondPreprocessor.isAtLeast_2_12_7) s
    else ""


  val tests = Tests {

    "from Ammonite" - {

      // These sessions were copy-pasted from ammonite.session.EvaluatorTests
      // Running them here to test our custom preprocessor.

      "multistatement" - {
        runner.run(
          ";1; 2L; '3';" ->
            """res0_0: Int = 1
              |res0_1: Long = 2L
              |res0_2: Char = '3'""".stripMargin,
          "val x = 1; x;" ->
            """x: Int = 1
              |res1_1: Int = 1""".stripMargin,
          "var x = 1; x = 2; x" ->
            """x: Int = 2
              |res2_2: Int = 2""".stripMargin,
          "var y = 1; case class C(i: Int = 0){ def foo = x + y }; new C().foo" ->
            """y: Int = 1
              |defined class C
              |res3_2: Int = 3""".stripMargin,
          "C()" ->
            "res4: C = C(0)"
        )
      }

      "lazy vals" - {
        runner.run(
          "lazy val x = 'h'" -> "x: Char = [lazy]",
          "x" ->
            """x: Char = 'h'
              |res1: Char = 'h'""".stripMargin,
          "var w = 'l'" -> "w: Char = 'l'",
          "lazy val y = {w = 'a'; 'A'}" -> "y: Char = [lazy]",
          "lazy val z = {w = 'b'; 'B'}" -> "z: Char = [lazy]",
          "z" ->
            Seq(ifVarUpdates("w: Char = 'b'"), "z: Char = 'B'", "res5: Char = 'B'").mkString("\n"),
          "y" ->
            Seq(ifVarUpdates("w: Char = 'a'"), "y: Char = 'A'", "res6: Char = 'A'").mkString("\n"),
          "w" -> "res7: Char = 'a'"
        )
      }

      "vars" - {
        runner.run(
          "var x: Int = 10" -> "x: Int = 10",
          "x" -> "res1: Int = 10",
          "x = 1" -> ifVarUpdates("x: Int = 1"),
          "x" -> "res3: Int = 1"
        )
      }
    }
  }

}
