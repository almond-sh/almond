package almond

import almond.api.JupyterApi
import almond.interpreter.api.DisplayData
import ammonite.ops.read
import ammonite.repl.{FrontEnd, FullReplAPI, ReplLoad, SessionApiImpl}
import ammonite.runtime.{History, Storage}
import ammonite.util.{Bind, Colors, CompilationError, Ref, Res}
import ammonite.util.Util.normalizeNewlines
import jupyter.{Displayer, Displayers}
import pprint.{TPrint, TPrintColors}

import scala.collection.mutable
import scala.reflect.ClassTag

/** Actual [[ammonite.repl.ReplAPI]] instance */
final class ReplApiImpl(
  execute0: Execute,
  storage: Storage,
  history0: History,
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
    pprinter: Ref[pprint.PPrinter],
    updatableResultsOpt: Option[JupyterApi.UpdatableResults]
  )(implicit tprint: TPrint[T], tcolors: TPrintColors, classTagT: ClassTag[T]): Option[Iterator[String]] =
    execute0.currentPublishOpt match {
      case None =>
        None
      case Some(p) =>

        val isUpdatableDisplay =
          classTagT != null &&
            classOf[almond.display.Display]
              .isAssignableFrom(classTagT.runtimeClass)

        val jvmReprDisplayer: Displayer[_] =
          Displayers.registration().find(classTagT.runtimeClass)
        val useJvmReprDisplay =
          jvmReprDisplayer ne defaultDisplayer

        if (isUpdatableDisplay) {
          val d = value.asInstanceOf[almond.display.Display]
          d.display()(p)
          Some(Iterator())
        } else if (useJvmReprDisplay) {
          import scala.collection.JavaConverters._
          val m = jvmReprDisplayer
            .asInstanceOf[Displayer[T]]
            .display(value)
            .asScala
            .toMap
          p.display(DisplayData(m))
          Some(Iterator())
        } else
          for (updatableResults <- updatableResultsOpt; onChange0 <- onChange) yield {

            // Pre-compute how many lines and how many columns the prefix of the
            // printed output takes, so we can feed that information into the
            // pretty-printing of the main body
            val prefix = new pprint.Truncated(
              Iterator(
                colors0().ident()(ident).render, ": ",
                implicitly[pprint.TPrint[T]].render(tcolors), " = "
              ),
              pprinter().defaultWidth,
              pprinter().defaultHeight
            )
            val output = mutable.Buffer.empty[fansi.Str]

            prefix.foreach(output.+=)

            val rhs = custom match {
              case None =>

                var currentValue = value

                val id = updatableResults.updatable {
                  pprinter().tokenize(
                    currentValue,
                    height = pprinter().defaultHeight - prefix.completedLineCount,
                    initialOffset = prefix.lastLineLength
                  ).map(_.render).mkString
                }

                onChange0 { value0 =>
                  if (value0 != currentValue) {
                    val s = pprinter().tokenize(
                      value0,
                      height = pprinter().defaultHeight - prefix.completedLineCount,
                      initialOffset = prefix.lastLineLength
                    )
                    updatableResults.update(id, s.map(_.render).mkString, last = false)
                    currentValue = value0
                  }
                }

                id

              case Some(s) =>
                pprinter().colorLiteral(s).render
            }

            output.iterator.map(_.render) ++ Iterator(rhs)
          }
      }



  def replArgs0 = Vector.empty[Bind[_]]
  def printer = execute0.printer

  def sess = sess0
  val prompt = Ref("nope")
  val frontEnd = Ref[FrontEnd](null)
  def lastException: Throwable = null
  def fullHistory = storage.fullHistory()
  def history = history0
  val colors = colors0
  def newCompiler() = ammInterp.compilerManager.init(force = true)
  def compiler = ammInterp.compilerManager.compiler.compiler
  def interactiveCompiler = ammInterp.compilerManager.pressy.compiler
  def fullImports = ammInterp.predefImports ++ imports
  def imports = ammInterp.frameImports
  def usedEarlierDefinitions = ammInterp.frameUsedEarlierDefinitions
  def width = 80
  def height = 80

  val load: ReplLoad =
    new ReplLoad {
      def apply(line: String) =
        ammInterp.processExec(line, execute0.currentLine, () => execute0.incrementLineCount()) match {
          case Res.Failure(s) => throw new CompilationError(s)
          case Res.Exception(t, _) => throw t
          case _ =>
        }

      def exec(file: ammonite.ops.Path): Unit = {
        ammInterp.watch(file)
        apply(normalizeNewlines(read(file)))
      }
    }

  override protected[this] def internal0: FullReplAPI.Internal =
    new FullReplAPI.Internal {
      def pprinter = self.pprinter
      def colors = self.colors
      def replArgs: IndexedSeq[Bind[_]] = replArgs0

      val defaultDisplayer = Displayers.registration().find(classOf[ReplApiImpl.Foo])

      override def print[T](
                             value: => T,
                             ident: String,
                             custom: Option[String]
                           )(implicit tprint: TPrint[T], tcolors: TPrintColors, classTagT: ClassTag[T]): Iterator[String] =
        printSpecial(value, ident, custom, None, pprinter, None)(tprint, tcolors, classTagT).getOrElse {
          super.print(value, ident, custom)(tprint, tcolors, classTagT)
        }
    }
}

object ReplApiImpl {
  private class Foo
}

