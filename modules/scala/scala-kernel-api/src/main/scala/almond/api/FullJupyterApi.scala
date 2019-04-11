package almond.api

trait FullJupyterApi extends JupyterApi { self =>

  protected def ansiTextToHtml(text: String): String

  object Internal {
    def ansiTextToHtml(text: String): String =
      self.ansiTextToHtml(text)
  }
}
