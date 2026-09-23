package almond.directives

import scala.cli.directivehandler._

@DirectiveGroupName("Compiler options")
@DirectiveExamples("//> using option -Xasync")
@DirectiveExamples("//> using test.option -Xasync")
@DirectiveExamples("//> using options -Xasync, -Xfatal-warnings")
@DirectiveUsage(
  "using option _option_ | using options _option1_ _option2_ …",
  """`//> using option `_option_
    |
    |`//> using options `_option1_, _option2_ …""".stripMargin
)
@DirectiveDescription("Add Scala compiler options")
final case class ScalacOptions(
  @DirectiveName("option")
  options: List[Positioned[String]] = Nil
) extends HasKernelOptions {
  def kernelOptions =
    Right(
      KernelOptions(
        scalacOptions = ShadowingSeq.from(options.map(_.map(ScalacOpt(_))))
      )
    )
}

object ScalacOptions {
  val handler: DirectiveHandler[ScalacOptions] = DirectiveHandler.deriver[ScalacOptions].derive
}
