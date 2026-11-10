// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/DirectiveDescription.scala

package almond.directivehandler

import scala.annotation.StaticAnnotation

final case class DirectiveDescription(description: String, descriptionMd: String = "")
    extends StaticAnnotation
