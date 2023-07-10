package almond

import almond.api.JupyterApi
import almond.interpreter.ExecuteResult
import almond.interpreter.api.DisplayData
import ammonite.repl.api.{FrontEnd, ReplLoad}
import ammonite.repl.{FullReplAPI, SessionApiImpl}
import ammonite.runtime.Storage
import ammonite.util.{Bind, Colors, CompilationError, Ref, Res}
import ammonite.util.Util.normalizeNewlines
import fansi.Attr
import jupyter.{Displayer, Displayers}
import pprint.{TPrint, TPrintColors}

import scala.collection.mutable
import scala.reflect.ClassTag

/** Actual [[ammonite.repl.api.ReplAPI]] instance */
final class ReplApiImpl(
  execute0: Execute,
  storage: Storage,
  colors0: Ref[Colors],
  ammInterp: => ammonite.interp.Interpreter,
  sess0: SessionApiImpl
) extends ammonite.repl.ReplApiImpl { self =>

  private val defaultDisplayer = Displayers.registration().find(classOf[almond.ReplApiImpl.Foo])

  def printSpecial[T](
    value: => T,
    ident: String,
    custom: Option[String],
    onChange: Option[(T => Unit) => Unit],
    onChangeOrError: Option[(Either[Throwable, T] => Unit) => Unit],
    pprinter: Ref[pprint.PPrinter],
    updatableResultsOpt: Option[JupyterApi.UpdatableResults]
  )(implicit
    tprint: TPrint[T],
    tcolors: TPrintColors,
    classTagT: ClassTag[T]
  ): Option[Iterator[String]] =
    execute0.currentPublishOpt match {
      case None =>
        None
      case Some(p) =>
        val isUpdatableDisplay =
          classTagT != null &&
          classOf[almond.display.Display]
            .isAssignableFrom(classTagT.runtimeClass)

        val jvmReprDisplayer: Displayer[_] =
          if (classTagT == null) defaultDisplayer
          else Displayers.registration().find(classTagT.runtimeClass)

        val useJvmReprDisplay =
          jvmReprDisplayer ne defaultDisplayer

        if (isUpdatableDisplay) {
          val d = value.asInstanceOf[almond.display.Display]
          d.display()(p)
          Some(Iterator())
        }
        else if (useJvmReprDisplay) {
          import scala.jdk.CollectionConverters._
          val m = jvmReprDisplayer
            .asInstanceOf[Displayer[T]]
            .display(value)
            .asScala
          if (m == null) None
          else {
            p.display(DisplayData(m.toMap))
            Some(Iterator())
          }
        }
        else
          for (
            updatableResults <- updatableResultsOpt
            if (onChange.nonEmpty && custom.isEmpty) || (onChangeOrError.nonEmpty && custom.nonEmpty)
          ) yield {

            // Pre-compute how many lines and how many columns the prefix of the
            // printed output takes, so we can feed that information into the
            // pretty-printing of the main body
            val prefix = new pprint.Truncated(
              Iterator(
                colors0().ident()(ident).render,
                ": ",
                implicitly[pprint.TPrint[T]].render(tcolors),
                " = "
              ),
              pprinter().defaultWidth,
              pprinter().defaultHeight
            )
            val output = mutable.Buffer.empty[fansi.Str]

            prefix.foreach(output.+=)

            val rhs = custom match {
              case None =>
                var currentValue = value // FIXME May be better to only hold weak references here
                //       (in case we allow to wipe previous values at some point)

                val id = updatableResults.updatable {
                  pprinter().tokenize(
                    currentValue,
                    height = pprinter().defaultHeight - prefix.completedLineCount,
                    initialOffset = prefix.lastLineLength
                  ).map(_.render).mkString
                }

                onChange.foreach(_ { value0 =>
                  if (value0 != currentValue) {
                    val s = pprinter().tokenize(
                      value0,
                      height = pprinter().defaultHeight - prefix.completedLineCount,
                      initialOffset = prefix.lastLineLength
                    )
                    updatableResults.update(id, s.map(_.render).mkString, last = false)
                    currentValue = value0
                  }
                })

                id

              case Some(s) =>
                val messageColor = Some(pprinter().colorLiteral)
                  .filter(_ == fansi.Attrs.Empty)
                  .getOrElse(fansi.Color.LightGray)

                val id = updatableResults.updatable {
                  messageColor(s).render
                }

                onChangeOrError.foreach(_ {
                  case Left(ex) =>
                    val messageColor = Some(pprinter().colorLiteral)
                      .filter(_ == fansi.Attrs.Empty)
                      .getOrElse(fansi.Color.LightGray)

                    val err =
                      ExecuteResult.Error.showException(
                        ex,
                        colors0().error(),
                        Attr.Reset,
                        colors0().literal()
                      )
                    val s = messageColor("[last attempt failed]").render + "\n" + err
                    updatableResults.update(id, s, last = false)
                  case Right(value0) =>
                    val s = pprinter().tokenize(
                      value0,
                      height = pprinter().defaultHeight - prefix.completedLineCount,
                      initialOffset = prefix.lastLineLength
                    )
                    updatableResults.update(id, s.map(_.render).mkString, last = true)
                })

                id
            }

            output.iterator.map(_.render) ++ Iterator(rhs)
          }
    }

  def replArgs0 = Vector.empty[Bind[_]]
  def printer   = execute0.printer

  def sess                     = sess0
  val prompt                   = Ref("nope")
  val frontEnd                 = Ref[FrontEnd](null)
  def lastException: Throwable = execute0.lastExceptionOpt.orNull
  def fullHistory              = storage.fullHistory()
  def history                  = execute0.history
  val colors                   = colors0
  def newCompiler()            = ammInterp.compilerManager.init(force = true)
  def _compilerManager         = ammInterp.compilerManager
  def fullImports              = ammInterp.predefImports ++ imports
  def imports                  = ammInterp.frameImports
  def usedEarlierDefinitions   = ammInterp.frameUsedEarlierDefinitions
  def width                    = 80
  def height                   = 80

  val load: ReplLoad =
    new ReplLoad {
      def apply(line: String) =
        ammInterp.processExec(
          line,
          execute0.currentLine + 1,
          () => execute0.incrementLineCount()
        ) match {
          case Res.Failure(s)      => throw new CompilationError(s)
          case Res.Exception(t, _) => throw t
          case _                   =>
        }

      def exec(file: os.Path): Unit = {
        ammInterp.watch(file)
        apply(normalizeNewlines(os.read(file)))
      }
    }

  override protected[this] def internal0: FullReplAPI.Internal =
    new FullReplAPI.Internal {
      def pprinter                      = self.pprinter
      def colors                        = self.colors
      def replArgs: IndexedSeq[Bind[_]] = replArgs0

      val defaultDisplayer = Displayers.registration().find(classOf[ReplApiImpl.Foo])

      override def print[T](
        value: => T,
        ident: String,
        custom: Option[String]
      )(implicit
        tprint: TPrint[T],
        tcolors: TPrintColors,
        classTagT: ClassTag[T]
      ): Iterator[String] =
        printSpecial(value, ident, custom, None, None, pprinter, None)(
          tprint,
          tcolors,
          classTagT
        ).getOrElse {
          super.print(value, ident, custom)(tprint, tcolors, classTagT)
        }
    }
}

object ReplApiImpl {
  private class Foo
}
