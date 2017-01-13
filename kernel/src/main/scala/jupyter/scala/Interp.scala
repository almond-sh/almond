package jupyter.scala

import ammonite.interp.{Interpreter, Parsers, Preprocessor}
import ammonite.repl.{RuntimeApiImpl, SessionApiImpl}
import ammonite.runtime._
import ammonite.util._
import com.typesafe.scalalogging.LazyLogging
import fastparse.core.Parsed
import jupyter.api.{CommChannelMessage, JupyterApi, Publish}
import jupyter.kernel.protocol.ShellReply.KernelInfo.LanguageInfo
import jupyter.kernel.protocol.ParsedMessage
import jupyter.kernel.interpreter.Interpreter.IsComplete

import scala.util.Try

class Interp extends jupyter.kernel.interpreter.Interpreter with LazyLogging {


  def defaultPredef = true
  val augmentedPredef = if (defaultPredef) Interp.predefString else ""

  var history = new History(Vector())

  val colors = Ref[Colors](Colors.Default)

  var publishFunc = Option.empty[ParsedMessage[_] => Publish]
  var currentPublish = Option.empty[Publish]
  val currentPublish0 = new Publish {
    def commHandler(target: String)(handler: (CommChannelMessage) => Unit) =
      currentPublish.get.commHandler(target)(handler)
    def comm(id: String) =
      currentPublish.get.comm(id)
    def stdout(text: String) =
      currentPublish.get.stdout(text)
    def stderr(text: String) =
      currentPublish.get.stderr(text)
    def display(items: (String, String)*) =
      currentPublish.get.display(items: _*)
  }

  val interp: Interpreter = new Interpreter(
    Printer(
      s => currentPublish.foreach(_.stdout(s)),
      s => currentPublish.foreach(_.stderr(s)),
      s => currentPublish.foreach(_.stderr(s)),
      s => currentPublish.foreach(_.stdout(s))
    ),
    new Storage.Folder(ammonite.ops.home / ".jupyter-scala" / "ammonite-cache" / jupyter.scala.BuildInfo.version),
    Seq(
      Name("HardcodedPredef") -> Interp.pprintPredef,
      Name("predef") -> augmentedPredef
    ),
    i => {
      val kernelApi = new RuntimeApiImpl(
        i,
        80,
        25,
        colors,
        history,
        new SessionApiImpl(i.eval),
        Nil
      ) with JupyterApi {
        def publish = currentPublish0
      }
      Seq(("jupyter.api.JupyterAPIHolder", "kernel", kernelApi))
    },
    ammonite.ops.pwd,
    eval = new EvaluatorImpl(Interpreter.initClassLoader, 0) {
      override def process(printer: Printer, isExec: Boolean, value: AnyRef): Any =
        if (isExec) {
          currentPublish.foreach { pub =>
            for (s <- value.asInstanceOf[Iterator[String]])
              pub.stdout(s)
          }
          Vector.empty[String]
        } else
          value.asInstanceOf[Iterator[String]].toVector

      override def specialLocalClasses = super.specialLocalClasses ++ Set(
        "jupyter.api.JupyterAPIHolder",
        "jupyter.api.JupyterAPIHolder$"
      )
    }
  ) {
    override def printBridge = "_root_.jupyter.api.JupyterAPIHolder.value"
  }

  interp.interpApi.load.ivy(
    ("org.jupyter-scala", "ammonite-runtime_" + scala.util.Properties.versionNumberString, BuildInfo.ammoniumVersion)
  )

  interp.interpApi.load.ivy(
    ("org.jupyter-scala", "scala-api_" + scala.util.Properties.versionNumberString, BuildInfo.version)
  )

  interp.init()

  private def capturingOutput[T](t: => T): T =
    Capture(
      currentPublish.map(p => p.stdout(_)),
      currentPublish.map(p => p.stderr(_))
    )(t)

  private def error(exOpt: Option[Throwable], msg: String) =
    jupyter.kernel.interpreter.Interpreter.Error(
      msg + exOpt.fold("")(ex => (if (msg.isEmpty) "" else "\n") + Interp.showException(
        ex, colors().error(), fansi.Attr.Reset, colors().literal()
      ))
    )

  def interpret(
    line: String,
    output: Option[(String => Unit, String => Unit)],
    storeHistory: Boolean,
    current: Option[ParsedMessage[_]]
  ): jupyter.kernel.interpreter.Interpreter.Result = {

    // addHistory(code)

    currentPublish = for {
      f <- publishFunc
      m <- current
    } yield f(m)

    try {

      var interruptedStackTraceOpt = Option.empty[Array[StackTraceElement]]

      val res =
        for {
          (code, stmts) <- Parsers.Splitter.parse(line) match {
            case Parsed.Success(value, idx) =>
              Res.Success((line, value))
            case Parsed.Failure(_, index, extra) => Res.Failure(
              None,
              fastparse.core.ParseError.msg(extra.input, extra.traced.expected, index)
            )
          }
          currentThread = Thread.currentThread()
          _ <- Signaller("INT") {
            interruptedStackTraceOpt = Some(currentThread.getStackTrace)
            currentThread.stop()
          }
          ev <- capturingOutput {
            val r = interp.processLine(code, stmts, s"cmd${interp.eval.getCurrentLine}.sc")
            interp.handleOutput(r)
            r
          }
        } yield ev

      res match {
        case Res.Success(ev) =>
          val output = ev.value.asInstanceOf[Vector[String]].mkString("")

          if (output.isEmpty)
            jupyter.kernel.interpreter.Interpreter.NoValue
          else
            jupyter.kernel.interpreter.Interpreter.Value(Seq(
              jupyter.kernel.interpreter.DisplayData.text(
                output
              )
            ))
        case Res.Failure(exOpt, msg) =>
          for (ex <- exOpt)
            logger.warn(s"failed to run user code (${ex.getMessage})", ex)

          interruptedStackTraceOpt match {
            case None => error(exOpt, msg)
            case Some(st) =>

              val cutoff = Set("$main", "evaluatorRunPrinter")

              jupyter.kernel.interpreter.Interpreter.Error(
                (
                  "Interrupted!" +: st
                    .takeWhile(x => !cutoff(x.getMethodName))
                    .map(Interp.highlightFrame(_, fansi.Attr.Reset, colors().literal()))
                ).mkString(ammonite.util.Util.newLine)
              )
          }

        case Res.Exception(ex, msg) =>
          logger.warn(s"exception in user code (${ex.getMessage})", ex)
          error(Some(ex), msg)

        case Res.Skip =>
          jupyter.kernel.interpreter.Interpreter.NoValue
        case Res.Exit(_) =>
          ???
      }
    } finally {
      currentPublish = None
    }
  }

  override def publish(publish: ParsedMessage[_] => Publish): Unit = {
    publishFunc = Some(publish)
  }

  override def isComplete(code: String): Some[IsComplete] = {

    val res = Parsers.Splitter.parse(code) match {
      case Parsed.Success(value, idx) =>
        IsComplete.Complete
      case Parsed.Failure(_, index, extra) if code.drop(index).trim() == "" =>
        val indent = code.split('\n').last.takeWhile(_.isSpaceChar)
        IsComplete.Incomplete(indent)
      case Parsed.Failure(_, _, _) =>
        IsComplete.Invalid
    }

    Some(res)
  }

  override def complete(code: String, pos: Int): (Int, Int, Seq[String]) = {

    val (newPos, completions0, details) = interp.pressy.complete(
      pos,
      Preprocessor.importBlock(interp.eval.frames.head.imports),
      code,
      replaceMode = true
    )

    if (sys.env.contains("DEBUG"))
      // output typically not captured during completion requests
      sys.props("jupyter-scala.completion.result") =
        s"""newPos = $newPos
           |completions =
           |${completions0.map("  '" + _ + "'").mkString("\n")}
           |details =
           |${details.map("  '" + _ + "'").mkString("\n")}
         """.stripMargin

    val completions = completions0.filter(!_.contains("$"))

    (if (completions.isEmpty) pos else newPos, pos, completions.map(_.trim).distinct)
  }

  def executionCount: Int =
    Try(interp.eval.getCurrentLine.replace('_', '-').toInt)
      .toOption
      .getOrElse(0)

  val scalaVersion = scala.util.Properties.versionNumberString
  val scalaBinaryVersion = scalaVersion.split('.').take(2).mkString(".")

  def languageInfo = LanguageInfo(
    name = s"scala${scalaBinaryVersion.filterNot(_ == '.')}",
    version = scalaVersion,
    mimetype = "text/x-scala",
    file_extension = ".scala",
    nbconvert_exporter = "scala", // ???
    pygments_lexer = Some("scala"),
    codemirror_mode = Some("text/x-scala")
  )

  override def implementation =
    "jupyter-scala" -> BuildInfo.version

  override def banner =
    s"""jupyter-scala ${BuildInfo.version}
       |ammonium ${BuildInfo.ammoniumVersion}
       |Scala ${_root_.scala.util.Properties.versionNumberString}
       |Java ${sys.props.getOrElse("java.version", "[unknown]")}""".stripMargin

  override def helpLinks = Seq(

  )
}

object Interp {

  // these come from Ammonite
  // exception display was tweaked a bit (too much red for notebooks else)

  def highlightFrame(f: StackTraceElement,
                     highlightError: fansi.Attrs,
                     source: fansi.Attrs) = {
    val src =
      if (f.isNativeMethod) source("Native Method")
      else if (f.getFileName == null) source("Unknown Source")
      else source(f.getFileName) ++ ":" ++ source(f.getLineNumber.toString)

    val prefix :+ clsName = f.getClassName.split('.').toSeq
    val prefixString = prefix.map(_+'.').mkString("")
    val clsNameString = clsName //.replace("$", error("$"))
    val method =
    fansi.Str(prefixString) ++ highlightError(clsNameString) ++ "." ++
      highlightError(f.getMethodName)

    fansi.Str(s"  ") ++ method ++ "(" ++ src ++ ")"
  }
  def showException(ex: Throwable,
                    error: fansi.Attrs,
                    highlightError: fansi.Attrs,
                    source: fansi.Attrs) = {
    import ammonite.util.Util.newLine

    val cutoff = Set("$main", "evaluatorRunPrinter")
    val traces = Ex.unapplySeq(ex).get.map(exception =>
      error(exception.toString) + newLine +
        exception
          .getStackTrace
          .takeWhile(x => !cutoff(x.getMethodName))
          .map(highlightFrame(_, highlightError, source))
          .mkString(newLine)
    )
    traces.mkString(newLine)
  }

  val pprintPredef =
    "import _root_.jupyter.api.JupyterAPIHolder.value.{pprintConfig, derefPPrint}"

  val ignoreUselessImports = """
                               |notify => _,
                               |  wait => _,
                               |  equals => _,
                               |  asInstanceOf => _,
                               |  synchronized => _,
                               |  notifyAll => _,
                               |  isInstanceOf => _,
                               |  == => _,
                               |  != => _,
                               |  getClass => _,
                               |  ne => _,
                               |  eq => _,
                               |  ## => _,
                               |  hashCode => _,
                               |  _
                               |"""

  val predefString = s"""
                        |import ammonite.ops.Extensions.{
                        |  $ignoreUselessImports
                        |}
                        |import ammonite.runtime.tools._
                        |// import ammonite.repl.tools._ // desugar
                        |import ammonite.runtime.tools.DependencyConstructor.{ArtifactIdExt, GroupIdExt}
                        |import jupyter.api.JupyterAPIHolder.value.{exit, codeColors, tprintColors, show, typeOf}
                        |// import ammonite.main.Router.{doc, main}
                        |// import ammonite.main.Scripts.pathScoptRead
                        |import kernel.publish
                        |""".stripMargin

}
