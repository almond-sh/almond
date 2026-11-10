// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/DirectiveSpecialSyntax.scala

package almond.directivehandler

import scala.util.matching.Regex

object DirectiveSpecialSyntax {

  /** Replaces the `${.}` pattern in the directive value with the parent directory of the file
    * containing the directive. Skips replacement if the pattern is preceded by two dollar signs
    * ($$). https://github.com/VirtusLab/scala-cli/issues/1098
    *
    * @param directiveValue
    *   the value of the directive, e.g., "-coverage-out:${.}" for example for the directive "//>
    *   using options "-coverage-out:${.}""
    * @param path
    *   the file path from which the directive is read; replacement occurs only if the directive is
    *   from a local file
    * @return
    *   the directive value with the `${.}` pattern replaced by the parent directory, if applicable
    */
  def handlingSpecialPathSyntax(directiveValue: String, path: Either[String, os.Path]): String = {
    val pattern = """(((?:\$)+)(\{\.\}))""".r
    path match {
      case Right(p) =>
        pattern.replaceAllIn(
          directiveValue,
          (m: Regex.Match) => {
            val dollarSigns = m.group(2)
            val dollars     = "\\$" * (dollarSigns.length / 2)
            if (dollarSigns.length % 2 == 0)
              s"$dollars${m.group(3)}"
            else
              s"$dollars${p / os.up}"
          }
        )
      case _ => directiveValue
    }
  }
}
