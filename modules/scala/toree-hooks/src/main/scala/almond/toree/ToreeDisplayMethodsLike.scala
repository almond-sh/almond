package almond.toree

trait ToreeDisplayMethodsLike {
  def content(mimeType: String, data: String): Unit
  def html(data: String): Unit       = content("text/html", data)
  def javascript(data: String): Unit = content("application/javascript", data)
  def clear(wait: Boolean = false): Unit
}
