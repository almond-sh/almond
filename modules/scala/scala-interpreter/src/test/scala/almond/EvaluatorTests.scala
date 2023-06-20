package almond

import almond.TestUtil.SessionRunner
import almond.amm.AlmondCompilerLifecycleManager
import almond.kernel.KernelThreads
import almond.util.SequentialExecutionContext
import almond.util.ThreadUtil.{attemptShutdownExecutionContext, singleThreadedExecutionContext}
import utest._

object EvaluatorTests extends TestSuite {

  val interpreterEc = singleThreadedExecutionContext("test-interpreter")
  val bgVarEc       = new SequentialExecutionContext

  val threads = KernelThreads.create("test")

  override def utestAfterAll() = {
    threads.attemptShutdown()
    if (!attemptShutdownExecutionContext(interpreterEc))
      println(s"Don't know how to shutdown $interpreterEc")
  }

  val runner = new SessionRunner(interpreterEc, bgVarEc, threads)

  def ifVarUpdates(s: String): String =
    if (AlmondCompilerLifecycleManager.isAtLeast_2_12_7 && TestUtil.isScala2) s
    else ""

  def ifNotVarUpdates(s: String): String =
    if (AlmondCompilerLifecycleManager.isAtLeast_2_12_7 && TestUtil.isScala2) ""
    else s

  val tests = Tests {

    test("from Ammonite") {

      // These sessions were copy-pasted from ammonite.session.EvaluatorTests
      // Running them here to test our custom preprocessor.

      test("multistatement") {
        val sv         = scala.util.Properties.versionNumberString
        val isScala212 = sv.startsWith("2.12.")
        runner.run(
          Seq(
            ";1; 2L; '3';" ->
              """res1_0: Int = 1
                |res1_1: Long = 2L
                |res1_2: Char = '3'""".stripMargin,
            "val x = 1; x;" ->
              """x: Int = 1
                |res2_1: Int = 1""".stripMargin,
            "var x = 1; x = 2; x" ->
              """x: Int = 2
                |res3_2: Int = 2""".stripMargin,
            "var y = 1; case class C(i: Int = 0){ def foo = x + y }; new C().foo" ->
              """y: Int = 1
                |defined class C
                |res4_2: Int = 3""".stripMargin,
            "C()" -> (if (isScala212) "res5: C = C(0)" else "res5: C = C(i = 0)")
          )
        )
      }

      test("lazy vals") {
        runner.run(
          Seq(
            "lazy val x = 'h'"            -> (if (TestUtil.isScala2) "" else "x: Char = <lazy>"),
            "x"                           -> "res2: Char = 'h'",
            "var w = 'l'"                 -> ifNotVarUpdates("w: Char = 'l'"),
            "lazy val y = {w = 'a'; 'A'}" -> (if (TestUtil.isScala2) "" else "y: Char = <lazy>"),
            "lazy val z = {w = 'b'; 'B'}" -> (if (TestUtil.isScala2) "" else "z: Char = <lazy>"),
            "z"                           -> "res6: Char = 'B'",
            "y"                           -> "res7: Char = 'A'",
            "w"                           -> "res8: Char = 'a'"
          ),
          Seq(
            if (TestUtil.isScala2) "x: Char = [lazy]" else "",
            if (TestUtil.isScala2) "x: Char = 'h'" else "",
            ifVarUpdates("w: Char = 'l'"),
            if (TestUtil.isScala2) "y: Char = [lazy]" else "",
            if (TestUtil.isScala2) "z: Char = [lazy]" else "",
            ifVarUpdates("w: Char = 'b'"),
            if (TestUtil.isScala2) "z: Char = 'B'" else "",
            ifVarUpdates("w: Char = 'a'"),
            if (TestUtil.isScala2) "y: Char = 'A'" else ""
          ).filter(_.nonEmpty)
        )
      }

      test("vars") {
        runner.run(
          Seq(
            "var x: Int = 10" -> ifNotVarUpdates("x: Int = 10"),
            "x"               -> "res2: Int = 10",
            "x = 1"           -> "",
            "x"               -> "res4: Int = 1"
          ),
          Seq(
            ifVarUpdates("x: Int = 10"),
            ifVarUpdates("x: Int = 1")
          ).filter(_.nonEmpty)
        )
      }
    }

    test("type annotation") {
      if (AlmondCompilerLifecycleManager.isAtLeast_2_12_7 && TestUtil.isScala2)
        runner.run(
          Seq(
            "var x: Any = 2" -> "",
            "x = 'a'"        -> ""
          ),
          Seq(
            "x: Any = 2",
            ifVarUpdates("x: Any = 'a'")
          )
        )
    }

    test("pattern match still compile") {
      // no updates for var-s defined via pattern matching
      runner.run(
        Seq(
          "var (a, b) = (1, 'a')" ->
            """a: Int = 1
              |b: Char = 'a'""".stripMargin,
          "a = 2"   -> "",
          "b = 'c'" -> ""
        )
      )
    }
  }

}
