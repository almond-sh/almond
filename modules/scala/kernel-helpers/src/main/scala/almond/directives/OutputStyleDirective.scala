package almond.directives

import almond.OutputStyle

import scala.cli.directivehandler._

@DirectiveGroupName("Output style")
@DirectiveExamples("//> using outputStyle python")
@DirectiveExamples("//> using outputStyle last")
@DirectiveExamples("//> using outputStyle default")
@DirectiveUsage(
  "//> using outputStyle default|last|python",
  "`//> using outputStyle` _default|last|python_"
)
@DirectiveDescription(OutputStyleDirective.usageMsg)
final case class OutputStyleDirective(
  outputStyle: Option[Positioned[String]] = None
) extends HasKernelOptions {
  def kernelOptions =
    outputStyle match {
      case None => Right(KernelOptions())
      case Some(input) =>
        OutputStyle.parse(input.value) match {
          case Left(err)    => Left(new MalformedDirectiveError(err, input.positions))
          case Right(style) => Right(KernelOptions(outputStyle = Some(style)))
        }
    }
}

object OutputStyleDirective {
  val handler: DirectiveHandler[OutputStyleDirective] =
    DirectiveHandler.deriver[OutputStyleDirective].derive

  val usageMsg =
    """Change how the results of the cells that follow are displayed.
      |
      |`default` displays each value defined or computed in a cell, along with its name and type. `last` only displays the last of them. `python` displays results like the Python kernel does: only the value of the last expression of a cell, without its name and type, and nothing if the cell ends with a semicolon.""".stripMargin
}
