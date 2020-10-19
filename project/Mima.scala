
import com.typesafe.tools.mima.core._
import sys.process._

object Mima {

  private def stable(ver: String): Boolean =
    ver.exists(c => c != '0' && c != '.') &&
    ver
      .replace("-RC", "-")
      .forall(c => c == '.' || c == '-' || c.isDigit)

  def binaryCompatibilityVersions(): Set[String] =
    Seq("git", "tag", "--merged", "HEAD^", "--contains", "v0.8.3")
      .!!
      .linesIterator
      .map(_.trim)
      .filter(_.startsWith("v"))
      .map(_.stripPrefix("v"))
      .filter(_ != "0.8.3") // Preserving compatibility right after it
      .filter(stable)
      .toSet

  val scalaKernelApiRules = Seq(
    // almond.api.FullJupyterApi assumed to be an internal API
    ProblemFilters.exclude[ReversedMissingMethodProblem]("almond.api.FullJupyterApi.VariableInspector"),
    ProblemFilters.exclude[ReversedMissingMethodProblem]("almond.api.FullJupyterApi.declareVariable"),
    ProblemFilters.exclude[ReversedMissingMethodProblem]("almond.api.FullJupyterApi.variableInspectorEnabled"),
    ProblemFilters.exclude[ReversedMissingMethodProblem]("almond.api.FullJupyterApi.variableInspectorInit"),
    ProblemFilters.exclude[ReversedMissingMethodProblem]("almond.api.FullJupyterApi.variableInspectorDictList")
  )

}
