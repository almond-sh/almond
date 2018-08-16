package almond.kernel.install

final case class Options(
  force: Boolean = false,
  id: Option[String] = None,
  displayName: Option[String] = None,
  global: Boolean = false,
  jupyterPath: Option[String] = None,
  logo: Option[String] = None,
  arg: List[String] = Nil,
  command: Option[String] = None,
  interruptViaMessage: Boolean = false
)
