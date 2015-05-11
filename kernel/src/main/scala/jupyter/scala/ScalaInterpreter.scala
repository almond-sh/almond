package jupyter.scala

import java.io.File

import ammonite.interpreter.Preprocessor.PreprocessorParser
import ammonite.interpreter._
import ammonite.pprint
import ammonite.shell.util.ColorSet
import ammonite.shell._
import com.github.alexarchambault.ivylight.ResolverHelpers
import jupyter.kernel.interpreter
import jupyter.kernel.interpreter.DisplayData
import jupyter.kernel.interpreter.Interpreter.Result
import jupyter.kernel.interpreter.helpers.Capture
import org.apache.ivy.plugins.resolver.DependencyResolver

import scala.tools.nsc.Global

object ScalaInterpreter {

  def bridgeConfig(
    startJars: Seq[File] = Nil,
    startIvys: Seq[(String, String, String)] = Nil,
    startResolvers: Seq[DependencyResolver] = Seq(ResolverHelpers.localRepo, ResolverHelpers.defaultMaven),
    pprintConfig: pprint.Config = pprint.Config.Colors.PPrintConfig
  ): BridgeConfig[Preprocessor.Output, Iterator[Iterator[String]]] =
    BridgeConfig(
    "object ReplBridge extends ammonite.shell.ReplAPIHolder{}",
    "ReplBridge",
    {
      _ =>
        val _pprintConfig = pprintConfig
        var replApi: ReplAPI with FullShellReplAPI = null

        (intp, cls, stdout) =>
          if (replApi == null)
            replApi = new ReplAPIImpl[Iterator[Iterator[String]]](intp, s => stdout(s + "\n"), startJars, startIvys, startResolvers) with ShellReplAPIImpl {
              def shellPrompt0 = throw new IllegalArgumentException("No shell prompt from Jupyter")
              def pprintConfig = _pprintConfig
              def colors = ColorSet.Default
            }

          ReplAPIHolder.initReplBridge(
            cls.asInstanceOf[Class[ReplAPIHolder]],
            replApi
          )

          BridgeHandle {
            replApi.power.stop()
          }
    },
    Evaluator.namesFor[ReplAPI with ShellReplAPI].map(n => n -> ImportData(n, n, "", "ReplBridge.shell")).toSeq ++
      Evaluator.namesFor[IvyConstructor].map(n => n -> ImportData(n, n, "", "ammonite.shell.IvyConstructor")).toSeq
    )

  val preprocessor: (Unit => (String => Either[String, scala.Seq[Global#Tree]])) => (String, String) => Res[Preprocessor.Output] =
    f => new PreprocessorParser(f(()), new WebDisplay {}) .apply

  def mergePrinters(printers: Seq[String]) = s"Iterator[Iterator[String]](${printers mkString ", "})"

  val wrap: (Preprocessor.Output, String, String) => String =
    (p, previousImportBlock, wrapperName) =>
      Wrap.obj(p.code, mergePrinters(p.printer), previousImportBlock, wrapperName)

  def classWrap(instanceSymbol: String): (Preprocessor.Output, String, String) => String =
    (p, previousImportBlock, wrapperName) =>
      Wrap.cls(p.code, mergePrinters(p.printer), previousImportBlock, wrapperName, instanceSymbol)

  val classWrapperInstanceSymbol = "INSTANCE"

  def apply(
    startJars: Seq[File],
    startDirs: Seq[File],
    startIvys: Seq[(String, String, String)],
    startResolvers: Seq[DependencyResolver],
    startClassLoader: ClassLoader
  ) = new interpreter.Interpreter {
    val underlying = new Interpreter[Preprocessor.Output, Iterator[Iterator[String]]](
      bridgeConfig(startJars = startJars, startIvys = startIvys, startResolvers = startResolvers),
      preprocessor,
      classWrap(classWrapperInstanceSymbol),
      handleResult = {
        val transform = Wrap.classWrapImportsTransform(classWrapperInstanceSymbol) _
        (buf, r) => transform(r)
      },
      printer = _.foreach(print),
      stdout = s => Console.out.println(s),
      initialHistory = Nil,
      predef = "",
      classes = new DefaultClassesImpl(startClassLoader, startJars, startDirs),
      useClassWrapper = true,
      classWrapperInstance = Some(classWrapperInstanceSymbol)
    )

    def interpret(line: String, output: Option[((String) => Unit, (String) => Unit)], storeHistory: Boolean): Result = {
      def capture[T](t: => T): T =
        output match {
          case Some((out, err)) =>
            Capture(out, err)(t)
          case None =>
            t
        }

      capture {
        underlying.processLine(line, _(_), it => new DisplayData.RawData(it.map(_.mkString).mkString("\n"))) match {
          case Res.Buffer(s) =>
            interpreter.Interpreter.Incomplete
          case Res.Exit =>
            interpreter.Interpreter.Error("Close this notebook to exit")
          case Res.Failure(reason) =>
            interpreter.Interpreter.Error(reason)
          case Res.Skip =>
            interpreter.Interpreter.NoValue
          case r @ Res.Success(ev) =>
            underlying.handleOutput(r)
            interpreter.Interpreter.Value(ev.value)
        }
      }
    }

    def complete(code: String, pos: Int): (Int, Seq[String]) = {
      val (pos0, completions, _) = underlying.pressy.complete(pos, underlying.eval.previousImportBlock, code)
      (pos0, completions)
    }

    def executionCount = underlying.history.length
  }

}
