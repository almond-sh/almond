package almond.display

import java.util.Locale

private[almond] object HtmlUtils {

  /** Escapes a string, so that it can be put in HTML text or in a quoted HTML attribute value */
  def escape(s: String): String = {
    val b = new StringBuilder(s.length + 16)
    s.foreach {
      case '&'  => b.append("&amp;")
      case '<'  => b.append("&lt;")
      case '>'  => b.append("&gt;")
      case '"'  => b.append("&quot;")
      case '\'' => b.append("&#39;")
      case c    => b.append(c)
    }
    b.result()
  }

  private val schemeRegex    = "^([a-zA-Z][a-zA-Z0-9+.-]*):".r
  private val allowedSchemes = Set("http", "https")

  /** Whether a URL is relative, or uses a scheme allowed in links or iframes (http or https) */
  def isAllowedUrl(url: String): Boolean = {
    // Like browsers do before parsing URLs, remove tabs and new lines, and strip leading spaces and
    // control characters, so that things like " java\tscript:…" are detected
    val url0 = url.filter(c => c != '\t' && c != '\n' && c != '\r').dropWhile(_ <= ' ')
    schemeRegex.findPrefixMatchOf(url0) match {
      case Some(m) => allowedSchemes.contains(m.group(1).toLowerCase(Locale.ROOT))
      case None    => true
    }
  }

  def checkUrl(url: String): Unit =
    if (!isAllowedUrl(url))
      throw new IllegalArgumentException(
        s"Unsupported URL '$url' (only http, https, and relative URLs are accepted)"
      )
}
