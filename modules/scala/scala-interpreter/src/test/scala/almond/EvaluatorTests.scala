package almond

import almond.TestUtil.SessionRunner
import almond.amm.AlmondCompilerLifecycleManager
import almond.kernel.KernelThreads
import almond.util.SequentialExecutionContext
import almond.util.ThreadUtil.{
  attemptShutdownExecutionContext,
  singleThreadedExecutionContextExecutorService
}
import utest._

object EvaluatorTests extends TestSuite {

  val interpreterEc  = singleThreadedExecutionContextExecutorService("test-interpreter")
  val cancellablesEc = singleThreadedExecutionContextExecutorService("test-interpreter")
  val bgVarEc        = new SequentialExecutionContext

  val threads = KernelThreads.create("test")

  override def utestAfterAll() = {
    threads.attemptShutdown()
    if (!attemptShutdownExecutionContext(interpreterEc))
      println(s"Don't know how to shutdown $interpreterEc")
  }

  val runner = new SessionRunner(interpreterEc, cancellablesEc, bgVarEc, threads)

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
            // pprint doesn't print the field names of single-field case classes in Scala 3
            "C()" ->
              (if (TestUtil.isScala2 && !isScala212) "res5: C = C(i = 0)" else "res5: C = C(0)")
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

    test("pprint") {
      // versionNumberString is the version of the Scala 2.13 library in Scala 3
      val sv         = scala.util.Properties.versionNumberString
      val isScala212 = sv.startsWith("2.12.")
      val isScala213 = TestUtil.isScala2 && sv.startsWith("2.13.")
      runner.run(
        Seq(
          // field names are only printed from Scala 2.13 on, and not for single-field case
          // classes in Scala 3
          "case class A(i: Int); val a = A(2)" ->
            s"""defined class A
               |a: A = ${if (isScala213) "A(i = 2)" else "A(2)"}""".stripMargin,
          // field names that aren't identifiers are backquoted
          "case class B(`a b`: Int, c: Int); val b = B(1, 2)" ->
            s"""defined class B
               |b: B = ${if (isScala212) "B(1, 2)" else "B(`a b` = 1, c = 2)"}""".stripMargin,
          // common collection classes are printed with the name of their default factory
          "val m: Map[Int, Int] = scala.collection.immutable.HashMap(1 -> 2)" ->
            "m: Map[Int, Int] = Map(1 -> 2)",
          "val s: Set[Int] = scala.collection.immutable.HashSet(1)" ->
            "s: Set[Int] = Set(1)",
          "val it: Iterable[Int] = scala.collection.mutable.ArraySeq(1)" ->
            "it: Iterable[Int] = Seq(1)"
        )
      )
    }
  }

}
