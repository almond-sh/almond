package almond.api

import scala.reflect.ClassTag

trait FullJupyterApi extends JupyterApi { self =>

  protected def printOnChange[T](value: => T,
                                      ident: String,
                                      custom: Option[String],
                                      onChange: Option[(T => Unit) => Unit])
                                     (implicit tprint: pprint.TPrint[T],
                                      tcolors: pprint.TPrintColors,
                                      classTagT: ClassTag[T] = null): Iterator[String]

  protected def ansiTextToHtml(text: String): String

  object Internal {
    def printOnChange[T](value: => T,
                                        ident: String,
                                        custom: Option[String],
                                        onChange: Option[(T => Unit) => Unit])
                                       (implicit tprint: pprint.TPrint[T],
                                        tcolors: pprint.TPrintColors,
                                        classTagT: ClassTag[T] = null): Iterator[String] =
      self.printOnChange(value, ident, custom, onChange)(tprint, tcolors, classTagT)
    def ansiTextToHtml(text: String): String =
      self.ansiTextToHtml(text)
  }
}
