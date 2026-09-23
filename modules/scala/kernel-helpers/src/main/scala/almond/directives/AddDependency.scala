package almond.directives

import scala.cli.directivehandler._
import scala.cli.directivehandler.EitherSequence._

@DirectiveGroupName("Dependency options")
@DirectiveExamples("//> using dep \"com.lihaoyi::os-lib:0.9.1\"")
@DirectiveUsage(
  "//> using dep _deps_",
  "`//> using dep `_deps_"
)
@DirectiveDescription("Add dependencies")
final case class AddDependency(
  @DirectiveName("lib")
  @DirectiveName("libs")
  @DirectiveName("dep")
  @DirectiveName("deps")
  @DirectiveName("dependencies")
  dependency: List[Positioned[String]] = Nil
) extends HasKernelOptions {
  def kernelOptions = {
    val maybeDeps = dependency
      .map { posInput =>
        _root_.dependency.parser.DependencyParser.parse(posInput.value)
          .left.map(err => new MalformedDependencyException(err, posInput.positions))
      }
      .sequence
      .left.map(CompositeDirectiveException(_))

    maybeDeps.map { deps =>
      KernelOptions(dependencies = deps)
    }
  }
}

object AddDependency {
  val handler: DirectiveHandler[AddDependency] = DirectiveHandler.deriver[AddDependency].derive
}
