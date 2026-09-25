package almond.directives

import scala.cli.directivehandler._

@DirectiveGroupName("Scripts")
@DirectiveExamples("//> using script path/to/script.sc")
@DirectiveExamples("//> using scripts foo.sc, ../bar.sc")
@DirectiveUsage(
  "//> using script _path_ | using scripts _path1_ _path2_ …",
  """`//> using script `_path_
    |
    |`//> using scripts `_path1_, _path2_ …""".stripMargin
)
@DirectiveDescription(Script.usageMsg)
final case class Script(
  @DirectiveName("script")
  scripts: List[Positioned[String]] = Nil
) extends HasKernelOptions {
  def kernelOptions =
    Right(
      KernelOptions(
        scripts = scripts
      )
    )
}

object Script {
  val handler: DirectiveHandler[Script] = DirectiveHandler.deriver[Script].derive

  val usageMsg =
    """Load Ammonite scripts, like `import $file.path.to.script` does.
      |
      |Paths are relative to the working directory of the kernel (usually the directory of the notebook). Each script is compiled and run once, and its wrapper object is brought in scope, under the name of the script file (`script` for `script.sc`). Scripts whose content changed since they were loaded are loaded again.""".stripMargin
}
