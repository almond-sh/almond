package almond.display

final class ProgressBar private (
  val label: Option[String],
  val total: Int,
  val progress: Option[Int],
  val htmlWidth: String,
  val textWidth: Int,
  val displayId: String
) extends UpdatableDisplay {

  private def copy(
    label: Option[String] = label,
    total: Int = total,
    progress: Option[Int] = progress,
    htmlWidth: String = htmlWidth,
    textWidth: Int = textWidth
  ): ProgressBar =
    new ProgressBar(label, total, progress, htmlWidth, textWidth, displayId)

  def withLabel(label: String): ProgressBar =
    copy(label = Some(label))
  def withLabel(labelOpt: Option[String]): ProgressBar =
    copy(label = labelOpt)
  def withTotal(total: Int): ProgressBar =
    copy(total = total)
  def withProgress(progress: Int): ProgressBar =
    copy(progress = Some(progress))
  def withProgress(progressOpt: Option[Int]): ProgressBar =
    copy(progress = progressOpt)
  def withHtmlWidth(width: String): ProgressBar =
    copy(htmlWidth = width)
  def withTextWidth(textWidth: Int): ProgressBar =
    copy(textWidth = textWidth)

  private def finalLabelOpt: Option[String] =
    label
      .filter(lab => !lab.contains("{progress}") || progress.nonEmpty)
      .map { lab =>
        lab
          .replace("{progress}", progress.fold("")(_.toString))
          .replace("{total}", total.toString)
      }

  private def html: String = {
    val widthPart    = s"style='width:$htmlWidth'"
    val progressPart = progress.fold("")(v => s"value='$v'")
    val idPart       = finalLabelOpt.fold("")(_ => s"id='progress-$displayId'")

    // FIXME Escape label?
    val labelPart = finalLabelOpt.fold("")(l => s"<label for='progress-$displayId'>$l</label><br>")
    val bar       = s"""<progress $idPart $widthPart max='$total' $progressPart></progress>"""

    labelPart + bar
  }

  private def text: String = {

    val bar = progress match {
      case None =>
        val ellipsis       = (total - 2).min(3).max(0)
        val remaining      = (total - 2 - ellipsis).max(0)
        val remainingLeft  = remaining / 2
        val remainingRight = remaining - remainingLeft
        "[" + (" " * remainingLeft) + ("." * ellipsis) + (" " * remainingRight) + "]"
      case Some(p) =>
        val remaining = (total - 2).max(0)
        val filled    = ((p * remaining) / total).max(0)
        val empty     = (remaining - filled).max(0)
        "[" + ("#" * filled) + (" " * empty) + "]"
    }

    finalLabelOpt.fold("")(_ + System.lineSeparator()) +
      bar
  }

  def data(): Map[String, String] =
    Map(
      Text.mimeType -> text,
      Html.mimeType -> html
    )

}

object ProgressBar {
  def apply(total: Int): ProgressBar =
    new ProgressBar(None, total, None, "60ex", 60, UpdatableDisplay.generateId())
  def apply(progress: Int, total: Int): ProgressBar =
    new ProgressBar(None, total, Some(progress), "60ex", 60, UpdatableDisplay.generateId())
}
