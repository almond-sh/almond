package jupyter.scala

import java.io.File

import ammonite.pprint
import ammonite.interpreter._
import ammonite.api.{IvyConstructor, ImportData, BridgeConfig}

import jupyter.kernel.interpreter
import jupyter.kernel.interpreter.DisplayData
import jupyter.kernel.interpreter.Interpreter.Result
import jupyter.kernel.protocol.Output.LanguageInfo

import com.github.alexarchambault.ivylight.Resolver
import org.apache.ivy.plugins.resolver.DependencyResolver

object ScalaInterpreter {

  def bridgeConfig(startJars: Seq[File] = Nil,
                   startIvys: Seq[(String, String, String)] = Nil,
                   jarMap: File => File = identity,
                   startResolvers: Seq[DependencyResolver] = Seq(Resolver.localRepo, Resolver.defaultMaven),
                   pprintConfig: pprint.Config = pprint.Config.Colors.PPrintConfig,
                   colors: ColorSet = ColorSet.Default): BridgeConfig =
    BridgeConfig(
      "object ReplBridge extends ammonite.shell.ReplAPIHolder{}",
      "ReplBridge",
      NamesFor[API].map{case (n, isImpl) => ImportData(n, n, "", "ReplBridge.shell", isImpl)}.toSeq ++
        NamesFor[IvyConstructor.type].map{case (n, isImpl) => ImportData(n, n, "", "ammonite.api.IvyConstructor", isImpl)}.toSeq,
      _.asInstanceOf[Iterator[String]].foreach(print)
    ) {
        var api: FullAPI = null

        (intp, cls) =>
          if (api == null)
            api = new APIImpl(intp, startJars, startIvys, jarMap, startResolvers, colors, pprintConfig)

          APIHolder.initReplBridge(cls.asInstanceOf[Class[APIHolder]], api)
    }

  val wrap =
    Wrap(_.map(WebDisplay(_)).reduceOption(_ + "++ Iterator(\"\\n\") ++" + _).getOrElse("Iterator()"), classWrap = true)

  def apply(startJars: Seq[File],
            startDirs: Seq[File],
            startIvys: Seq[(String, String, String)],
            jarMap: File => File,
            startResolvers: Seq[DependencyResolver],
            startClassLoader: ClassLoader,
            pprintConfig: pprint.Config = pprint.Config.Colors.PPrintConfig,
            colors: ColorSet = ColorSet.Default) =
    new interpreter.Interpreter {
      val underlying = new Interpreter(
        bridgeConfig = bridgeConfig(startJars = startJars, startIvys = startIvys, jarMap = jarMap, startResolvers = startResolvers, pprintConfig = pprintConfig, colors = colors),
        wrapper = wrap,
        imports = new ammonite.interpreter.Imports(useClassWrapper = true),
        classes = new Classes(startClassLoader, (startJars, startDirs))
      )

      def interpret(line: String, output: Option[(String => Unit, String => Unit)], storeHistory: Boolean): Result =
        underlying(line, _(_), it => new DisplayData.RawData(it.asInstanceOf[Iterator[String]].mkString), stdout = output.map(_._1), stderr = output.map(_._2)) match {
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

      def complete(code: String, pos: Int): (Int, Seq[String]) = {
        val (pos0, completions, _) = underlying.complete(pos, code)
        (pos0, completions)
      }

      def executionCount = underlying.history.length

      val languageInfo = LanguageInfo(
        name="scala",
        codemirror_mode = "text/x-scala",
        file_extension = "scala",
        mimetype = "text/x-scala"
      )
    }

}
