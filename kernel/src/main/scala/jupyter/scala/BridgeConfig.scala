package jupyter.scala

import java.io.File

import ammonite.api.{ClassLoaderType, ModuleConstructor, Import}
import ammonite.interpreter.{Interpreter, NamesFor}
import ammonite.pprint
import coursier.core.Repository
import jupyter.api._
import jupyter.kernel.protocol.ParsedMessage

class BridgeConfig(
  publish: => Option[Publish[Evidence]],
  currentMessage: => Option[ParsedMessage[_]],
  paths0: Map[ClassLoaderType, Seq[File]],
  modules0: Map[ClassLoaderType, Seq[(String, String, String)]],
  pathMap: File => File = identity,
  repositories0: Seq[Repository] = Seq(Repository.ivy2Local, Repository.mavenCentral),
  pprintConfig: pprint.Config = pprint.Config.Colors.PPrintConfig,
  colors: ColorSet = ColorSet.Default
) extends ammonite.interpreter.BridgeConfig {

  def init = "object ReplBridge extends jupyter.api.APIHolder"
  def name = "ReplBridge"
  def imports =
    NamesFor[API].map{case (n, isImpl) => Import(n, n, "", "ReplBridge.shell", isImpl)}.toSeq ++
      NamesFor[ModuleConstructor.type].map{case (n, isImpl) => Import(n, n, "", "ammonite.api.IvyConstructor", isImpl)}.toSeq
  def print(v: AnyRef) =
    println(v.asInstanceOf[Iterator[String]].mkString)

  var api: API = null

  def initClass(intp: Interpreter, cls: Class[_]): Unit = {
    if (api == null)
      api = new APIImpl(
        intp, 
        publish, 
        currentMessage, 
        startJars, 
        startIvys, 
        jarMap, 
        repositories0,
        colors, 
        pprintConfig
      )

    APIHolder.initReplBridge(cls.asInstanceOf[Class[APIHolder]], api)
  }
}
