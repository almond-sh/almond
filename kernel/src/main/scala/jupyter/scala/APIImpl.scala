package jupyter.scala

import ammonite.api.Eval
import ammonite.interpreter._
import ammonite.tprint.TPrint
import ammonite.Interpreter

import jupyter.api._
import jupyter.kernel.protocol.ParsedMessage

import pprint.{ PPrint, Config }

import scala.reflect.runtime.universe._


class APIImpl(
  intp: Interpreter,
  publish0: => Option[Publish[Evidence]],
  currentMessage: => Option[ParsedMessage[_]],
  colors: Colors,
  var pprintConfig: pprint.Config
) extends API {

  def classpath: ammonite.api.Classpath = intp.classpath
  def interpreter = intp

  val eval: Eval = new Eval {
    def apply(code: String) =
      Interpreter.run(code, (), None, None, _ => ())(intp)
  }

  val setup: ammonite.api.Setup =
    new ammonite.util.Setup(classpath, eval, Map(
      "ammonium.version" -> BuildInfo.ammoniumVersion,
      "jupyter-scala.version" -> BuildInfo.version
    ))

  def show[T](
    t: T,
    width: Integer = null,
    height: Integer = 0,
    indent: Integer = null,
    colors: _root_.pprint.Colors = null
  )(implicit
    cfg: Config = Config.Defaults.PPrintConfig,
    pprint0: PPrint[T]
  ): Unit = {
    pprint.tokenize(t, width, height, indent, colors)(implicitly[PPrint[T]], cfg).foreach(scala.Predef.print)
  }

  def evidence = new Evidence(
    currentMessage.getOrElse(throw new IllegalStateException("Not processing a Jupyter message")))

  def publish = publish0
    .getOrElse(throw new IllegalStateException("Interpreter is not connected to a front-end"))


  def printValue[T, U](
    value: => T,
    dummy: => U,
    ident: String,
    custom: Option[String]
  )(implicit
    cfg: Config,
    tprint: TPrint[U],
    pprint: PPrint[T],
    tpe: WeakTypeTag[T]
  ): Iterator[String] =
    if (weakTypeOf[T] =:= weakTypeOf[Unit])
      Iterator()
    else {
      val rhs = custom match {
        case None => implicitly[PPrint[T]].render(value, cfg)
        case Some(s) => Iterator(colors.literal() + s + colors.reset())
      }

      Iterator(
        colors.ident() + ident + colors.reset(), ": " +
          implicitly[TPrint[U]].render(cfg) + " = "
      ) ++ rhs
    }

}
