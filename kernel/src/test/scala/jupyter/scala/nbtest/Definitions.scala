package jupyter.scala.nbtest

sealed trait Cell {
  def cell_type: String
  def metadata: Map[String, argonaut.Json]
  def source: Seq[String]
}

case class MarkdownCell(metadata: Map[String, argonaut.Json],
                        source: Seq[String]) extends Cell {
  val cell_type = "markdown"
}

sealed trait Output {
  def output_type: String
}

case class StreamOutput(name: String,
                        text: Seq[String]) extends Output {
  def output_type = "stream"
}

case class DisplayDataOutput(metadata: Map[String, argonaut.Json],
                             data: Map[String, argonaut.Json]) extends Output {
  def output_type = "display_data"
}

case class ErrorOutput(ename: String,
                       evalue: String,
                       traceback: Seq[String]) extends Output {
  def output_type = "error"

  override def toString = s"ErrorOutput($ename, $evalue,\n${traceback mkString "\n"}\n)"
}

case class CodeCell(metadata: Map[String, argonaut.Json],
                    source: Seq[String],
                    execution_count: Option[Int],
                    outputs: Seq[Output]) extends Cell {
  val cell_type = "code"
}

case class NotebookData(cells: List[Cell],
                        metadata: Map[String, argonaut.Json],
                        nbformat: Int,
                        nbformat_minor: Int)
