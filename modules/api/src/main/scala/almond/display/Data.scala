package almond.display

final class Data private (
  data0: Map[String, String],
  metadata0: Map[String, String],
  val displayId: String
) extends UpdatableDisplay {

  private def copy(
    data: Map[String, String] = data0,
    metadata: Map[String, String] = metadata0
  ): Data =
    new Data(data, metadata, displayId)

  def withData(data: Map[String, String]): Data =
    copy(data = data)
  def withMetadata(metadata: Map[String, String]): Data =
    copy(metadata = metadata)

  override def metadata(): Map[String, String] =
    metadata0
  def data(): Map[String, String] =
    data0
}

object Data {
  def apply(data: Map[String, String]): Data =
    new Data(data, Map(), UpdatableDisplay.generateId())
  def apply(data: (String, String)*): Data =
    new Data(data.toMap, Map(), UpdatableDisplay.generateId())
}
