package jupyter

import ammonite.TestRepl
import utest._

import _root_.scala.util.Try

class SparkTests(sparkVersion: String) extends TestSuite {

  val (sparkMajor, sparkMinor) =
    sparkVersion.split('.').take(2).map(s => Try(s.toInt).toOption) match {
      case Array(Some(maj), Some(min)) => (maj, min)
      case _ =>
        throw new Exception(s"Can't get major / minor from Spark version '$sparkVersion'")
    }

  val atLeastSpark13 = sparkMajor > 1 || sparkMajor == 1 && sparkMinor >= 3
  val atLeastSpark14 = sparkMajor > 1 || sparkMajor == 1 && sparkMinor >= 4

  def isLocal = true
  def hasSpark5281 = true // https://issues.apache.org/jira/browse/SPARK-5281
  def hasSpark6299 = !isLocal && !atLeastSpark13 // https://issues.apache.org/jira/browse/SPARK-6299
  def importSparkContextContent = !atLeastSpark13
  def hasDataFrames = atLeastSpark13
  def broadcastOk = !isLocal

  val margin = "          "

  val preamble = s"""
          @ import $$ivy.`org.slf4j:slf4j-nop:1.7.21`

          @ import $$ivy.`org.slf4j:log4j-over-slf4j:1.7.21`

          @ import $$exclude.`org.slf4j:slf4j-log4j12`

          @ import $$ivy.`org.apache.spark::spark-sql:$sparkVersion`

          @ import $$ivy.`org.jupyter-scala::spark:0.4.0-SNAPSHOT`

          @ import $$ivy.`org.jupyter-scala::spark-stubs-${sparkVersion.takeWhile(_ != '.')}:0.4.0-SNAPSHOT` // don't know why we explicitly need this one in the tests - sparkInit() below is supposed to import it

          @ import jupyter.spark._
          import jupyter.spark._

          @ @transient val sparkConf = new org.apache.spark.SparkConf().
          @   setAppName("test").
          @   setMaster("local")

          @ @transient val sc = new JupyterSparkContext(sparkConf)

          @ val sqlContext = new org.apache.spark.sql.SQLContext(sc)

          @ import sqlContext.implicits._
          import sqlContext.implicits._

      """

  /*
   * Most of these come from
   * https://github.com/apache/spark/blob/master/repl/scala-2.11/src/test/scala/org/apache/spark/repl/ReplSuite.scala
   * and were adapted to Ammonite/utest
   */

  val tests = TestSuite {
    val check = new TestRepl

    'simpleForeachWithAccum - {
      check.session(preamble +
        """
          @ val accum = sc.accumulator(0)
          accum: org.apache.spark.Accumulator[Int] = 0

          @ sc.parallelize(1 to 10).foreach(x => accum += x)

          @ val v = accum.value
          v: Int = 55

          @ sc.stop()

        """)
    }

    'externalVars - {
      check.session(preamble +
        """
          @ var v = 7
          v: Int = 7

          @ val r1 = sc.parallelize(1 to 10).map(x => v).collect().reduceLeft(_+_)
          r1: Int = 70

          @ v = 10

          @ val r2 = sc.parallelize(1 to 10).map(x => v).collect().reduceLeft(_+_)
          r2: Int = 100

          @ sc.stop()

        """)
    }

    'externalClasses{
      check.session(preamble +
        """
          @ class C {
          @   def foo = 5
          @ }
          defined class C

          @ val n = sc.parallelize(1 to 10).map(x => (new C).foo).collect().reduceLeft(_+_)
          n: Int = 50

          @ sc.stop()
        """)
    }

    'externalFunctions{
      check.session(preamble +
        """
          @ def double(x: Int) = x + x
          defined function double

          @ val n = sc.parallelize(1 to 10).map(x => double(x)).collect().reduceLeft(_+_)
          n: Int = 110

          @ sc.stop()
        """)
    }

    'externalFunctionsThatAccessVar{
      check.session(preamble +
        """
          @ var v = 7
          v: Int = 7

          @ def getV() = v
          defined function getV

          @ val n = sc.parallelize(1 to 10).map(x => getV()).collect().reduceLeft(_+_)
          n: Int = 70

          @ v = 10

          @ val m = sc.parallelize(1 to 10).map(x => getV()).collect().reduceLeft(_+_)
          m: Int = 100

          @ sc.stop()
        """)
    }

    'broadcastVars{
      check.session(preamble +
       s"""
          @ var array = new Array[Int](5)
          array: Array[Int] = Array(0, 0, 0, 0, 0)

          @ val broadcastArray = sc.broadcast(array)
          broadcastArray: org.apache.spark.broadcast.Broadcast[Array[Int]] = Broadcast(0)

          @ val n = sc.parallelize(0 to 4).map(x => broadcastArray.value(x)).collect()
          n: Array[Int] = Array(0, 0, 0, 0, 0)

          @ array(0) = 5

          @ val m = sc.parallelize(0 to 4).map(x => broadcastArray.value(x)).collect()
          m: Array[Int] = Array(${if (broadcastOk) 0 else 5 /* Values should be broadcasted only once, they should not change */}, 0, 0, 0, 0)

          @ sc.stop()
        """)
    }

    // TODO? interacting with files

    'sparkIssue1199{
      check.session(
       s"""
          @ case class Sum(exp: String, exp2: String)
          defined class Sum

          @ val a = Sum("A", "B")
          a: Sum = Sum("A", "B")

          @ def b(a: Sum): String = a match { case Sum(_, _) => "Found Sum" }
          defined function b

          @ val s = b(a)
          s: String = "Found Sum"
        """)
    }

    'sparkIssue2452{
      check.session(
        """
          @ val x = 4 ; def f() = x
          x: Int = 4
          defined function f

          @ val n = f()
          n: Int = 4
        """)
    }

    'sparkIssue2576{
      if (!hasSpark5281) {
        val imp = if (hasDataFrames) "sqlContext.implicits._" else "sqlContext.createSchemaRDD"
        val toFrameMethod = if (hasDataFrames) "toDF()" else "toSchemaRDD"
        val repr = if (hasDataFrames) (1 to 10).map(i => s"[$i]").mkString(", ") else (1 to 10).map(i => s"  GenericRow($i)").mkString("\n" + margin, ",\n" + margin, "\n" + margin)

        check.session(preamble +
         s"""
          @ import org.apache.spark.sql.Row
          import org.apache.spark.sql.Row

          @ import $imp
          import $imp

          @ case class TestCaseClass(value: Int)
          defined class TestCaseClass

          @ val a = sc.parallelize(1 to 10).map(x => TestCaseClass(x)).$toFrameMethod.collect()
          a: Array[Row] = Array($repr)

          @ sc.stop()
         """)
      }
    }

    'sparkIssue2632{
      check.session(preamble +
       s"""
          @ class TestClass() { def testMethod = 3; override def toString = "TestClass" }
          defined class TestClass

          @ val t = new TestClass
          t: TestClass = TestClass

          @ import t.testMethod
          import t.testMethod

          @ case class TestCaseClass(value: Int)
          defined class TestCaseClass

          @ val a = sc.parallelize(1 to 10).map(x => TestCaseClass(x)).collect()
          a: Array[TestCaseClass] = Array(${(1 to 10).map(i => s"  TestCaseClass($i)").mkString("\n" + margin, ",\n" + margin, "\n" + margin)})

          @ sc.stop()
        """)
    }

    'collectingObjClsDefinedInRepl{
      check.session(preamble +
       s"""
          @ case class Foo(i: Int)
          defined class Foo

          @ val a = sc.parallelize((1 to 100).map(Foo), 10).collect().take(5)
          a: Array[Foo] = Array(Foo(1), Foo(2), Foo(3), Foo(4), Foo(5))

          @ sc.stop()
        """)
    }

    'collectingObjClsDefinedInReplShuffling{
      check.session(preamble +
       s"""
          @ case class Foo(i: Int)
          defined class Foo

          @ val list = List((1, Foo(1)), (1, Foo(2)))
          list: List[(Int, Foo)] = List((1, Foo(1)), (1, Foo(2)))
        """ + (if (!hasSpark6299) s"""

          @ val a = sc.parallelize(list).groupByKey().collect()
          a: Array[(Int, Iterable[Foo])] = Array((1, CompactBuffer(Foo(1), Foo(2))))
        """ else "") +
        """

          @ sc.stop()
        """)
    }
  }

}

object Spark16Tests extends SparkTests("1.6.3")
object Spark20Tests extends SparkTests("2.0.2")