// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/CompositeDirectiveException.scala

package almond.directivehandler

final class CompositeDirectiveException private (
  val mainException: DirectiveException,
  val others: Seq[DirectiveException]
) extends DirectiveException(
      s"${others.length + 1} exceptions, first one: ${mainException.getMessage}",
      Nil,
      mainException
    ) {

  def exceptions: Seq[DirectiveException] =
    mainException +: others

}

object CompositeDirectiveException {
  private def flatten(list: ::[DirectiveException]): ::[DirectiveException] = {
    val list0 = list.flatMap {
      case c: CompositeDirectiveException => c.mainException :: c.others.toList
      case e                              => e :: Nil
    }
    list0 match {
      case Nil    => sys.error("Can't happen")
      case h :: t => ::(h, t)
    }
  }
  def apply(exceptions: ::[DirectiveException]): DirectiveException =
    flatten(exceptions) match {
      case h :: Nil => h
      case h :: t   => new CompositeDirectiveException(h, t)
    }

  def apply(exceptions: Seq[DirectiveException]): DirectiveException =
    exceptions.distinct match {
      case Seq(head)    => head
      case head +: tail => new CompositeDirectiveException(head, tail)
    }
}
