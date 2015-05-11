package jupyter.scala

import java.io.File

import ammonite.pprint
import ammonite.interpreter._
import ammonite.api.{IvyConstructor, ImportData, BridgeConfig}

import jupyter.api.Publish
import jupyter.kernel.interpreter
import jupyter.kernel.interpreter.DisplayData
import jupyter.kernel.protocol.Output.LanguageInfo
import jupyter.kernel.protocol.ParsedMessage

import com.github.alexarchambault.ivylight.Resolver
import org.apache.ivy.plugins.resolver.DependencyResolver

object ScalaInterpreter {

  def bridgeConfig(publish: => Option[Publish[Evidence]],
                   currentMessage: => Option[ParsedMessage[_]],
                   startJars: Seq[File] = Nil,
                   startIvys: Seq[(String, String, String)] = Nil,
                   jarMap: File => File = identity,
                   startResolvers: Seq[DependencyResolver] = Seq(Resolver.localRepo, Resolver.defaultMaven),
                   pprintConfig: pprint.Config = pprint.Config.Colors.PPrintConfig,
                   colors: ColorSet = ColorSet.Default): BridgeConfig =
    BridgeConfig(
      "object ReplBridge extends jupyter.scala.APIHolder",
      "ReplBridge",
      NamesFor[API].map{case (n, isImpl) => ImportData(n, n, "", "ReplBridge.shell", isImpl)}.toSeq ++
        NamesFor[IvyConstructor.type].map{case (n, isImpl) => ImportData(n, n, "", "ammonite.api.IvyConstructor", isImpl)}.toSeq,
      _.asInstanceOf[Iterator[String]].foreach(print)
    ) {
        var api: FullAPI = null

        (intp, cls) =>
          if (api == null)
            api = new APIImpl(intp, publish, currentMessage, startJars, startIvys, jarMap, startResolvers, colors, pprintConfig)

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
            colors: ColorSet = ColorSet.Default): interpreter.Interpreter =
    new interpreter.Interpreter {
      var currentPublish = Option.empty[Publish[Evidence]]
      var currentMessage = Option.empty[ParsedMessage[_]]

      val underlying = new Interpreter(
        bridgeConfig = bridgeConfig(
          currentPublish,
          currentMessage,
          startJars = startJars,
          startIvys = startIvys,
          jarMap = jarMap,
          startResolvers = startResolvers,
          pprintConfig = pprintConfig,
          colors = colors
        ),
        wrapper = wrap,
        imports = new ammonite.interpreter.Imports(useClassWrapper = true),
        classes = new Classes(startClassLoader, (startJars, startDirs))
      )

      def interpret(line: String, output: Option[(String => Unit, String => Unit)], storeHistory: Boolean, current: Option[ParsedMessage[_]]) = {
        currentMessage = current

        try {
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
        }
        finally
          currentMessage = None
      }

      override def publish(publish: Publish[ParsedMessage[_]]) = {
        currentPublish = Some(publish.contramap[Evidence](e => e.underlying.asInstanceOf[ParsedMessage[_]]))
      }

      def complete(code: String, pos: Int) = {
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
