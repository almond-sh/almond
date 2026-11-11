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

  protected def printValueOnly[T](value: => T)(implicit classTagT: ClassTag[T]): Iterator[String]

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
    def printValueOnly[T](value: => T)(implicit classTagT: ClassTag[T] = null): Iterator[String] =
      self.printValueOnly(value)(classTagT)
    def ansiTextToHtml(text: String): String =
      self.ansiTextToHtml(text)
    def declareVariable[T](name: String, value: => T, strValueOrNull: String = null)(implicit
      tprint: pprint.TPrint[T],
      tcolors: pprint.TPrintColors,
      classTagT: ClassTag[T] = null
    ): Unit =
      self.declareVariable(name, value, Option(strValueOrNull))

    /** Like `declareVariable`, with the type of `value` passed explicitly
      *
      * Used on Scala 3, where the type comes from the compiler (see
      * `ammonite.repl.FullReplAPI.Internal.renderedType`), as pprint's `TPrint` renders some Scala
      * 3 types badly, like named tuples.
      */
    def declareVariableWithType[T](
      name: String,
      value: => T,
      tprint: pprint.TPrint[T],
      strValueOrNull: String = null
    )(implicit
      tcolors: pprint.TPrintColors,
      classTagT: ClassTag[T] = null
    ): Unit =
      self.declareVariable(name, value, Option(strValueOrNull))(tprint, tcolors, classTagT)
  }

  def kernelClassLoader: ClassLoader
}
