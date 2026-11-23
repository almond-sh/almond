package almond.directives

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

/** Values of kernel directives (like `//> using dep …`), that the launcher passes to the kernel, as
  * JSON
  *
  * Values are passed as is: the kernel validates them when it loads them, with the same directive
  * hooks as those handling the directives of cells (see `almond.Execute.loadOptions`).
  *
  * Its JSON format is also the one of the kernel parameters that the commands of custom directive
  * groups get and return.
  */
// other directives that could be imported from Scala CLI:
// - exclude
// - objectWrapper
// - plugin
// - python
// - resources
// - toolkit
final case class KernelOptions(
  dependencies: Seq[String] = Nil,
  scalacOptions: Seq[String] = Nil,
  extraRepositories: Seq[String] = Nil,
  scripts: Seq[String] = Nil,
  outputStyle: Option[String] = None
) {
  def isEmpty: Boolean =
    this == KernelOptions()
  def +(other: KernelOptions): KernelOptions =
    KernelOptions(
      dependencies = dependencies ++ other.dependencies,
      scalacOptions = scalacOptions ++ other.scalacOptions,
      extraRepositories = extraRepositories ++ other.extraRepositories,
      scripts = scripts ++ other.scripts,
      outputStyle = other.outputStyle.orElse(outputStyle)
    )

  /** These options, as directives (key and values), in the order they should be loaded in */
  def directives: Seq[(String, Seq[String])] =
    Seq(
      // repositories first, for dependencies to be resolved from them
      DirectiveKeys.repository   -> extraRepositories,
      DirectiveKeys.dependency   -> dependencies,
      DirectiveKeys.scalacOption -> scalacOptions,
      DirectiveKeys.outputStyle  -> outputStyle.toSeq,
      DirectiveKeys.script       -> scripts
    ).collect {
      case (keys, values) if values.nonEmpty =>
        (keys.head, values)
    }
}

object KernelOptions {

  implicit val codec: JsonValueCodec[KernelOptions] =
    JsonCodecMaker.makeWithRequiredCollectionFields

  /** Adds the values of a kernel directive (one whose key is in [[DirectiveKeys.kernel]]) */
  def fromDirective(key: String, values: Seq[String]): KernelOptions =
    if (DirectiveKeys.dependency.contains(key)) KernelOptions(dependencies = values)
    else if (DirectiveKeys.repository.contains(key)) KernelOptions(extraRepositories = values)
    else if (DirectiveKeys.scalacOption.contains(key)) KernelOptions(scalacOptions = values)
    else if (DirectiveKeys.script.contains(key)) KernelOptions(scripts = values)
    else if (DirectiveKeys.outputStyle.contains(key)) KernelOptions(outputStyle = values.lastOption)
    else KernelOptions()
}
