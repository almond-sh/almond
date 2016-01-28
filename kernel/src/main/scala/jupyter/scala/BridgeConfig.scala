package jupyter.scala

import ammonite.api.{ ModuleConstructor, Import }
import ammonite.interpreter.{ Colors, NamesFor }
import ammonite.Interpreter

import jupyter.api._
import jupyter.kernel.protocol.ParsedMessage

class BridgeConfig(
  publish: => Option[Publish[Evidence]],
  currentMessage: => Option[ParsedMessage[_]],
  pprintConfig: pprint.Config = pprint.Config.Colors.PPrintConfig,
  colors: Colors = Colors.Default
) extends ammonite.BridgeConfig {

  def init = "object BridgeHolder extends jupyter.api.APIHolder"
  def name = "BridgeHolder"
  def imports =
    NamesFor[API].map{case (n, isImpl) => Import(n, n, "", "BridgeHolder.shell", isImpl)}.toSeq ++
      NamesFor[ModuleConstructor.type].map{case (n, isImpl) => Import(n, n, "", "ammonite.api.ModuleConstructor", isImpl)}.toSeq
  def print(v: AnyRef) =
    println(v.asInstanceOf[Iterator[String]].mkString)

  var api: API = null

  def initClass(intp: Interpreter, cls: Class[_]): Unit = {
    if (api == null)
      api = new APIImpl(
        intp, 
        publish, 
        currentMessage,
        colors, 
        pprintConfig
      )

    APIHolder.initReplBridge(cls.asInstanceOf[Class[APIHolder]], api)
  }
}
