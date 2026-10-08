package almond

/** How the results of cells are displayed */
sealed abstract class OutputStyle(val name: String) extends Product with Serializable

object OutputStyle {

  /** Each value defined or computed in a cell is displayed, along with its name and type */
  case object Default extends OutputStyle("default")

  /** Only the last value defined or computed in a cell is displayed, along with its name and type
    */
  case object Last extends OutputStyle("last")

  /** Like the Python kernel, only the value of the last expression of a cell is displayed, without
    * its name and type, and nothing is displayed if the cell ends with a semicolon
    */
  case object Python extends OutputStyle("python")

  val all: Seq[OutputStyle] = Seq(Default, Last, Python)

  def parse(input: String): Either[String, OutputStyle] =
    all.find(_.name == input.trim).toRight {
      s"Invalid output style '$input', expected one of ${all.map(_.name).mkString(", ")}"
    }
}
