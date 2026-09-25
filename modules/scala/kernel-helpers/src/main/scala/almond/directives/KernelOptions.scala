package almond.directives

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import dependency.parser.DependencyParser

import scala.cli.directivehandler._
import scala.cli.directivehandler.EitherSequence._

// other directives that could be imported from Scala CLI:
// - exclude
// - objectWrapper
// - plugin
// - python
// - resources
// - toolkit
final case class KernelOptions(
  dependencies: Seq[dependency.AnyDependency] = Nil,
  scalacOptions: ShadowingSeq[Positioned[ScalacOpt]] = ShadowingSeq.empty,
  extraRepositories: Seq[String] = Nil,
  ignoredDirectives: Seq[IgnoredDirective] = Nil,
  scripts: Seq[Positioned[String]] = Nil
) {
  def isEmpty: Boolean =
    this == KernelOptions()
  def +(other: KernelOptions): KernelOptions =
    copy(
      dependencies = dependencies ++ other.dependencies,
      scalacOptions = scalacOptions ++ other.scalacOptions.toSeq,
      extraRepositories = extraRepositories ++ other.extraRepositories,
      ignoredDirectives = ignoredDirectives ++ other.ignoredDirectives,
      scripts = scripts ++ other.scripts
    )
}

object KernelOptions {

  final case class AsJson(
    dependencies: Seq[String] = Nil,
    scalacOptions: Seq[String] = Nil,
    extraRepositories: Seq[String] = Nil,
    scripts: Seq[String] = Nil
  ) {
    def +(other: AsJson): AsJson =
      AsJson(
        dependencies = dependencies ++ other.dependencies,
        scalacOptions = scalacOptions ++ other.scalacOptions,
        extraRepositories = extraRepositories ++ other.extraRepositories,
        scripts = scripts ++ other.scripts
      )
    def toKernelOptions: Either[::[String], KernelOptions] = {
      val maybeDependencies = dependencies
        .map { dep =>
          DependencyParser.parse(dep)
        }
        .sequence
        .left.map(errors => ::(errors.head, errors.tail.toList))
      maybeDependencies match {
        case Right(dependencies0) =>
          Right(
            KernelOptions(
              dependencies = dependencies0,
              scalacOptions =
                ShadowingSeq.from(scalacOptions.map(opt => Positioned.none(ScalacOpt(opt)))),
              extraRepositories = extraRepositories,
              scripts = scripts.map(Positioned.none(_))
            )
          )
        case Left(depErrors) =>
          Left(depErrors)
      }
    }
  }

  object AsJson {
    def empty: AsJson                          = AsJson(Nil, Nil, Nil, Nil)
    implicit val codec: JsonValueCodec[AsJson] = JsonCodecMaker.makeWithRequiredCollectionFields

    def apply(options: KernelOptions): AsJson =
      AsJson(
        dependencies = options.dependencies.map(_.render),
        scalacOptions = options.scalacOptions.toSeq.map(_.value.value),
        extraRepositories = options.extraRepositories,
        scripts = options.scripts.map(_.value)
      )
  }

}
