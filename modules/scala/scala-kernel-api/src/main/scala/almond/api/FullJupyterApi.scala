package almond.api

import scala.reflect.ClassTag

// TODO Move to almond.api.internal
trait FullJupyterApi extends JupyterApi { self =>

  protected def printOnChange[T](
    value: => T,
    ident: String,
    custom: Option[String],
    onChange: Option[(T => Unit) => Unit],
    onChangeOrError: Option[(Either[Throwable, T] => Unit) => Unit]
  )(implicit
    tprint: pprint.TPrint[T],
    tcolors: pprint.TPrintColors,
    classTagT: ClassTag[T] = null
  ): Iterator[String]

  protected def ansiTextToHtml(text: String): String

  protected def declareVariable[T](name: String, value: => T, strValueOpt: Option[String])(implicit
    tprint: pprint.TPrint[T],
    tcolors: pprint.TPrintColors,
    classTagT: ClassTag[T]
  ): Unit

  protected def variableInspectorEnabled(): Boolean
  protected def variableInspectorInit(): Unit
  protected def variableInspectorDictList(): Unit

  object VariableInspector {
    def enabled(): Boolean =
      variableInspectorEnabled()
    def init(): Unit =
      variableInspectorInit()
    def dictList(): Unit =
      variableInspectorDictList()
  }

  object Internal {
    def printOnChange[T](
      value: => T,
      ident: String,
      custom: Option[String],
      onChange: Option[(T => Unit) => Unit],
      onChangeOrError: Option[(Either[Throwable, T] => Unit) => Unit]
    )(implicit
      tprint: pprint.TPrint[T],
      tcolors: pprint.TPrintColors,
      classTagT: ClassTag[T] = null
    ): Iterator[String] =
      self.printOnChange(value, ident, custom, onChange, onChangeOrError)(
        tprint,
        tcolors,
        classTagT
      )
    def ansiTextToHtml(text: String): String =
      self.ansiTextToHtml(text)
    def declareVariable[T](name: String, value: => T, strValueOrNull: String = null)(implicit
      tprint: pprint.TPrint[T],
      tcolors: pprint.TPrintColors,
      classTagT: ClassTag[T] = null
    ): Unit =
      self.declareVariable(name, value, Option(strValueOrNull))
  }

  def kernelClassLoader: ClassLoader
}
