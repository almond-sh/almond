package almond.directives

import scala.cli.directivehandler._
import scala.cli.directivehandler.EitherSequence._

@DirectiveGroupName("Repository")
@DirectiveExamples("//> using repository jitpack")
@DirectiveExamples("//> using repository sonatype:snapshots")
@DirectiveExamples("//> using repository m2Local")
@DirectiveExamples(
  "//> using repository https://maven-central.storage-download.googleapis.com/maven2"
)
@DirectiveUsage(
  "//> using repository _repository_",
  "`//> using repository `_repository_"
)
@DirectiveDescription(Repository.usageMsg)
// format: off
final case class Repository(
  @DirectiveName("repository")
    repositories: List[String] = Nil
) extends HasKernelOptions {
  // format: on
  def kernelOptions =
    Right(
      KernelOptions(
        extraRepositories = repositories
      )
    )
}

object Repository {
  val handler: DirectiveHandler[Repository] = DirectiveHandler.deriver[Repository].derive

  val usageMsg =
    """Add repositories for dependency resolution.
      |
      |Accepts predefined repositories supported by Coursier (like `sonatype:snapshots` or `m2Local`) or a URL of the root of Maven repository""".stripMargin
}
