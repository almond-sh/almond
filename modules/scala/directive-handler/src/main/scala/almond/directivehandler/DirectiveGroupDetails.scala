// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/DirectiveGroupDetails.scala

package almond.directivehandler

final case class DirectiveGroupDetails(
  name: String,
  description: String,
  usage: String,
  descriptionMdOpt: Option[String] = None,
  usageMdOpt: Option[String] = None,
  examples: Seq[String] = Nil
) {
  def descriptionMd: String =
    descriptionMdOpt.getOrElse(description)
  def usageMd: String =
    usageMdOpt.getOrElse(s"`$usage`")
}
