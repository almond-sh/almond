// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/ScopedDirective.scala

package almond.directivehandler

case class ScopedDirective(
  directive: StrictDirective,
  maybePath: Either[String, os.Path],
  cwd: ScopePath
) {
  def unusedDirectiveError: UnusedDirectiveError = {
    val values =
      DirectiveUtil.concatAllValues(this)
    new UnusedDirectiveError(
      directive.key,
      values.map(_.value),
      values.flatMap(_.positions)
    )
  }
}
