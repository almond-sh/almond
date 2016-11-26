package jupyter.scala

import ammonite.repl.{RuntimeApiImpl, SessionApiImpl}
import ammonite.runtime._
import ammonite.util._
import com.typesafe.scalalogging.LazyLogging
import fastparse.core.Parsed
import jupyter.api.{Evidence, JupyterApi, Publish}
import jupyter.kernel.protocol.ShellReply.KernelInfo.LanguageInfo
import jupyter.kernel.protocol.ParsedMessage

import scala.util.Try

class Interp extends jupyter.kernel.interpreter.Interpreter with LazyLogging {


  def defaultPredef = true
  val augmentedPredef = if (defaultPredef) Interp.predefString else ""

  var history = new History(Vector())

  val colors = Ref[Colors](Colors.Default)

  var publishFunc = Option.empty[ParsedMessage[_] => Publish]
  var currentPublish = Option.empty[Publish]

  val interp: Interpreter = new Interpreter(
    Printer(
      s => currentPublish.foreach(_.stdout(s)),
      s => currentPublish.foreach(_.stderr(s)),
      s => currentPublish.foreach(_.stderr(s)),
      s => currentPublish.foreach(_.stdout(s))
    ),
    Storage.InMemory(),
    Seq(
      Name("HardcodedPredef") -> Interp.pprintPredef,
      // Name("ArgsPredef") -> argString,
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
        def publish = currentPublish.get
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
      val res = for {
        (code, stmts) <- Parsers.Splitter.parse(line) match {
          case Parsed.Success(value, idx) =>
            Res.Success((line, value))
          case Parsed.Failure(_, index, extra) => Res.Failure(
            None,
            fastparse.core.ParseError.msg(extra.input, extra.traced.expected, index)
          )
        }
        evRes = interp.processLine(code, stmts, s"cmd${interp.eval.getCurrentLine}.sc")
        _ = interp.handleOutput(evRes)
        ev <- evRes
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
          jupyter.kernel.interpreter.Interpreter.Error(
            s"${exOpt.mkString} $msg"
          )
        case Res.Exception(ex, msg) =>
          logger.warn(s"exception in user code (${ex.getMessage})", ex)
          jupyter.kernel.interpreter.Interpreter.Error(
            s"$ex $msg"
          )
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

  def complete(code: String, pos: Int): (Int, Seq[String]) = {
    ???
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
