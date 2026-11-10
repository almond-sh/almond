// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/Position.scala

package almond.directivehandler

sealed abstract class Position {
  def render(): String =
    render(os.pwd, java.io.File.separator)
  def render(cwd: os.Path): String =
    render(cwd, java.io.File.separator)
  def render(cwd: os.Path, sep: String): String
}

object Position {

  final case class File(
    path: Either[String, os.Path],
    startPos: (Int, Int),
    endPos: (Int, Int)
  ) extends Position {
    def render(cwd: os.Path, sep: String): String = {
      val p = path match {
        case Left(p0) => p0
        case Right(p0) =>
          if (p0.startsWith(cwd)) p0.relativeTo(cwd).segments.mkString(sep)
          else p0.toString
      }
      if (startPos == endPos)
        s"$p:${startPos._1 + 1}:${startPos._2 + 1}"
      else if (startPos._1 == endPos._1)
        s"$p:${startPos._1 + 1}:${startPos._2 + 1}-${endPos._2 + 1}"
      else
        s"$p:${startPos._1 + 1}:${startPos._2 + 1}-${endPos._1 + 1}:${endPos._2 + 1}"
    }
  }
}
