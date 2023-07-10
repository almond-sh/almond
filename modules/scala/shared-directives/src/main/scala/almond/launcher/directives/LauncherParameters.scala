package almond.launcher.directives

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

import scala.cli.directivehandler.{DirectiveHandler, DirectiveHandlers}

final case class LauncherParameters(
  jvm: Option[String] = None,
  javaOptions: Seq[String] = Nil,
  scala: Option[String] = None,
  javaCmd: Option[Seq[String]] = None,
  customDirectives: Seq[(CustomGroup, String, Seq[String])] = Nil
) {
  def +(other: LauncherParameters): LauncherParameters =
    LauncherParameters(
      jvm.orElse(other.jvm),
      javaOptions ++ other.javaOptions,
      scala.orElse(other.scala),
      javaCmd.orElse(other.javaCmd),
      customDirectives = customDirectives ++ other.customDirectives
    )

  def processCustomDirectives(): LauncherParameters = {

    import LauncherParameters.{AsJson, Entry, entriesCodec}

    var tmpFile0: os.Path = null
    lazy val tmpFile = {
      tmpFile0 = os.temp(prefix = "almond-launcher-params-", suffix = ".json")
      tmpFile0
    }

    try
      customDirectives
        .map {
          case (group, key, values) =>
            val entries = List(Entry(key, values.toList))
            os.write.over(tmpFile, writeToArray(entries)(entriesCodec))
            val res = os.proc(group.command, tmpFile)
              .call(stdin = os.Inherit, check = false)
            if (res.exitCode != 0)
              sys.error(
                s"Command ${group.command} for custom directives ${group.prefix} exited with code ${res.exitCode}"
              )
            readFromArray(res.out.bytes)(AsJson.codec).params
        }
        .foldLeft(this)(_ + _)
    finally
      if (tmpFile0 != null)
        os.remove(tmpFile0)
  }
}

object LauncherParameters {

  val handlers = DirectiveHandlers(
    Seq[DirectiveHandler[HasLauncherParameters]](
      JavaOptions.handler,
      Jvm.handler,
      ScalaVersion.handler,
      JavaCommand.handler
    )
  )

  private case class Entry(key: String, values: List[String])
  private val entriesCodec: JsonValueCodec[List[Entry]] =
    JsonCodecMaker.makeWithRequiredCollectionFields

  private final case class AsJson(
    jvm: Option[String] = None,
    javaOptions: Seq[String] = Nil,
    scala: Option[String] = None,
    javaCmd: Option[Seq[String]] = None
  ) {
    def params: LauncherParameters =
      LauncherParameters(
        jvm = jvm,
        javaOptions = javaOptions,
        scala = scala,
        javaCmd = javaCmd
      )
  }

  private object AsJson {
    val codec: JsonValueCodec[AsJson] = JsonCodecMaker.make
    def from(params: LauncherParameters): AsJson =
      AsJson(
        jvm = params.jvm,
        javaOptions = params.javaOptions,
        scala = params.scala,
        javaCmd = params.javaCmd
      )
  }

}
