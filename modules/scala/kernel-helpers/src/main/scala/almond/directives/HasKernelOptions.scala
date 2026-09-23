package almond.directives

import scala.cli.directivehandler.{
  DirectiveException,
  DirectiveHandler,
  DirectiveHandlers,
  IgnoredDirective
}

trait HasKernelOptions {
  def kernelOptions: Either[DirectiveException, KernelOptions]
}

object HasKernelOptions {

  case object Ignore extends HasKernelOptions {
    def kernelOptions: Either[DirectiveException, KernelOptions] =
      Right(KernelOptions())
  }

  final case class IgnoredDirectives(ignored: Seq[IgnoredDirective]) extends HasKernelOptions {
    def kernelOptions: Either[DirectiveException, KernelOptions] =
      Right(KernelOptions(ignoredDirectives = ignored))
  }

  object ops {
    implicit class HasKernelOptionsDirectiveHandlerOps[T](
      private val handler: DirectiveHandler[T]
    ) {
      def ignoredDirective: DirectiveHandler[HasKernelOptions] =
        handler.ignore.map(ignored => IgnoredDirectives(Seq(ignored)))
    }
  }

  val handlers = DirectiveHandlers(Seq[DirectiveHandler[HasKernelOptions]](
    AddDependency.handler,
    Repository.handler,
    ScalacOptions.handler
  ))

}
