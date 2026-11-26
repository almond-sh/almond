package almond

import almond.interpreter.api.{DisplayData, ExecuteResult, OutputHandler}
import almond.launcher.directives.CustomGroup
import almond.testkit.TestLogging.logCtx
import almond.TestUtil._
import utest._

object DirectiveTests extends TestSuite {

  private def newInterpreter(): ScalaInterpreter =
    new ScalaInterpreter(
      params = interpreterParams.copy(
        launcherDirectiveGroups = Seq(CustomGroup("spark", ""))
      ),
      logCtx = logCtx
    )

  /** Runs a cell, and returns its result along with what it printed on stderr */
  private def run(interp: ScalaInterpreter, code: String): (ExecuteResult, String) = {
    val err = new StringBuilder
    val handler = new OutputHandler {
      def stdout(s: String): Unit                       = ()
      def stderr(s: String): Unit                       = err.append(s)
      def display(displayData: DisplayData): Unit       = ()
      def clearOutput(waitForNewOutput: Boolean): Unit  = ()
      def updateDisplay(displayData: DisplayData): Unit = ()
      def canOutput(): Boolean                          = true
      def messageIdOpt: Option[String]                  = None
      def addPayload(payload: String): Unit             = ()
    }
    val res = interp.execute(code, outputHandler = Some(handler))
    (res, err.toString)
  }

  private def errorMessage(res: ExecuteResult): String =
    res.asError.map(_.message).getOrElse(sys.error(s"Expected an error, got $res"))

  val tests = Tests {

    test("unrecognized directive") {
      val interp = newInterpreter()
      val (res, _) = run(
        interp,
        """//> using option -deprecation
          |//> using nope 1
          |val n = 2
          |""".stripMargin
      )
      assert(errorMessage(res).contains("(console):2:11: unrecognized directive '//> using nope'"))
      // none of the directives of the cell were taken into account
      val (res0, _) = run(interp, "interp.scalacOptions.contains(\"-deprecation\")")
      // failed cells get an execution count too
      assert(res0 == ExecuteResult.Success(DisplayData.text("res2: Boolean = false")))
    }

    test("launcher directives") {
      val interp        = newInterpreter()
      val (res, stderr) = run(interp, "//> using scala 2.13\n//> using jvm 17\nval n = 2")
      assert(res == ExecuteResult.Success(DisplayData.text("n: Int = 2")))
      val expectedStderr = Seq("scala", "jvm")
        .map(key =>
          s"Warning: ignoring '//> using $key', that can only be used prior to any code" +
            System.lineSeparator()
        )
        .mkString
      assert(stderr == expectedStderr)
    }

    test("custom group directives") {
      val interp = newInterpreter()
      val (res, stderr) =
        run(interp, "//> using spark.version 3.5.8\n//> using spark\n//> using sparkle 1")
      // keys starting with the prefix and a dot, or equal to it, belong to the group
      assert(
        errorMessage(res).contains("(console):3:11: unrecognized directive '//> using sparkle'")
      )
      val (res0, stderr0) = run(interp, "//> using spark.version 3.5.8\n//> using spark")
      assert(res0 == ExecuteResult.Success())
      assert(stderr0.contains("ignoring '//> using spark.version'"))
      assert(stderr0.contains("ignoring '//> using spark'"))
    }
  }
}
