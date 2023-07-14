package almond.launcher.directives

import almond.directives.KernelOptions
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

import scala.cli.directivehandler.{DirectiveHandler, DirectiveHandlers}

final case class LauncherParameters(
  jvm: Option[String] = None,
  javaOptions: Seq[String] = Nil,
  scala: Option[String] = None,
  javaCmd: Option[Seq[String]] = None,
  kernelOptions: Seq[String] = Nil,
  customDirectives: Seq[(CustomGroup, String, Seq[String])] = Nil
) {
  import LauncherParameters._

  def +(other: LauncherParameters): LauncherParameters =
    LauncherParameters(
      jvm.orElse(other.jvm),
      javaOptions ++ other.javaOptions,
      scala.orElse(other.scala),
      javaCmd.orElse(other.javaCmd),
      kernelOptions ++ other.kernelOptions,
      customDirectives = customDirectives ++ other.customDirectives
    )

  def processCustomDirectives(options: KernelOptions): (LauncherParameters, KernelOptions) = {

    var tmpFile0: os.Path = null
    lazy val tmpFile = {
      tmpFile0 = os.temp(prefix = "almond-launcher-params-", suffix = ".json")
      tmpFile0
    }

    val updates =
      try
        customDirectives
          .map {
            case (group, key, values) =>
              val entries = List(Entry(key, values.toList))
              val input = CustomGroupInput(
                entries,
                LauncherParameters.AsJson.from(this),
                KernelOptions.AsJson(options)
              )
              os.write.over(tmpFile, writeToArray(input)(CustomGroupInput.codec))
              val res = os.proc(group.command, tmpFile)
                .call(stdin = os.Inherit, check = false)
              if (res.exitCode != 0)
                sys.error(
                  s"Command ${group.command} for custom directives ${group.prefix} exited with code ${res.exitCode}"
                )
              readFromArray(res.out.bytes)(CustomGroupOutput.codec)
          }
          .foldLeft(CustomGroupOutput.empty)(_ + _)
      finally
        if (tmpFile0 != null)
          os.remove(tmpFile0)

    (
      this + updates.launcherParameters.params,
      options + updates.kernelParameters.toKernelOptions.fold(_ => ???, identity)
    )
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

  private case class CustomGroupInput(
    entries: List[Entry] = Nil,
    currentLauncherParameters: AsJson,
    currentKernelParameters: KernelOptions.AsJson
  )

  private object CustomGroupInput {
    val codec: JsonValueCodec[CustomGroupInput] =
      JsonCodecMaker.makeWithRequiredCollectionFields
  }

  private case class CustomGroupOutput(
    launcherParameters: AsJson = AsJson(),
    kernelParameters: KernelOptions.AsJson = KernelOptions.AsJson()
  ) {
    def +(other: CustomGroupOutput): CustomGroupOutput =
      CustomGroupOutput(
        launcherParameters = launcherParameters + other.launcherParameters,
        kernelParameters = kernelParameters + other.kernelParameters
      )
  }

  private object CustomGroupOutput {
    def empty: CustomGroupOutput = CustomGroupOutput(AsJson.empty, KernelOptions.AsJson.empty)
    val codec: JsonValueCodec[CustomGroupOutput] =
      JsonCodecMaker.makeWithRequiredCollectionFields
  }

  private final case class AsJson(
    jvm: Option[String] = None,
    javaOptions: Seq[String] = Nil,
    scala: Option[String] = None,
    javaCmd: Option[Seq[String]] = None,
    kernelOptions: Seq[String] = Nil
  ) {
    def +(other: AsJson): AsJson =
      AsJson(
        jvm = jvm.orElse(other.jvm),
        javaOptions = javaOptions ++ other.javaOptions,
        scala = scala.orElse(other.scala),
        javaCmd = javaCmd.orElse(other.javaCmd),
        kernelOptions = kernelOptions ++ other.kernelOptions
      )
    def params: LauncherParameters =
      LauncherParameters(
        jvm = jvm,
        javaOptions = javaOptions,
        scala = scala,
        javaCmd = javaCmd,
        kernelOptions = kernelOptions
      )
  }

  private object AsJson {
    def empty: AsJson                          = AsJson(None, Nil, None, None)
    implicit val codec: JsonValueCodec[AsJson] = JsonCodecMaker.makeWithRequiredCollectionFields
    def from(params: LauncherParameters): AsJson =
      AsJson(
        jvm = params.jvm,
        javaOptions = params.javaOptions,
        scala = params.scala,
        javaCmd = params.javaCmd
      )
  }

}
