package jupyter
package scala

import java.io.File
import ammonite.repl._
import bridge.{BridgeHolder, Load, Bridge, DisplayData, IvyConstructor}
import com.typesafe.scalalogging.slf4j.LazyLogging
import jupyter.kernel.interpreter.helpers.Capture
import org.apache.ivy.plugins.resolver.DependencyResolver
import jupyter.kernel.interpreter.Interpreter

import _root_.scala.reflect.runtime.universe._

class ScalaBridge(
  underlying: ammonite.repl.interp.Interpreter[_, _]
) extends Bridge {
  import _root_.scala.reflect.runtime.universe.{ Try => _, _ }

  def clear(): Unit = ()
  def history: Seq[String] = underlying.history

  def typeOf[T: WeakTypeTag]: Type = weakTypeOf[T]
  def typeOf[T: WeakTypeTag](t: => T): Type = weakTypeOf[T]

  // FIXME Move in Load
  private var _resolvers = Seq.empty[DependencyResolver]
  private[scala] def setResolvers(resolvers: Seq[DependencyResolver]) = {
    _resolvers = resolvers
  }

  def load: Load = new Load {
    def apply(line: String): Unit = ???

    def handleJar(jar: File): Unit = {
      underlying.extraJars = underlying.extraJars ++ Seq(jar)
      underlying.eval.addJar(jar.toURI.toURL)
    }
    def jar(jar: File): Unit = {
      underlying.eval.newClassloader()
      underlying.extraJars = underlying.extraJars :+ jar
      handleJar(jar)
      underlying.init()
    }
    def ivy(coordinates: (String, String, String)): Unit = {
      val (groupId, artifactId, version) = coordinates
      val jars = IvyHelper.resolveArtifact(groupId, artifactId, version, _resolvers)

      underlying.eval.newClassloader()
      underlying.extraJars ++= jars
      jars.foreach(handleJar)
      underlying.init()
    }
  }

  def newCompiler(): Unit = underlying.init()
}

object ScalaInterpreter {
  def apply(jarDeps: Seq[File], dirDeps: Seq[File], classLoader: ClassLoader, resolvers: Seq[DependencyResolver]): Interpreter  =
    underlying[ScalaBridge](jarDeps, dirDeps, classLoader, resolvers, "_root_.jupyter.bridge.BridgeHolder", (cls, bridge) => Bridge.init(cls.asInstanceOf[Class[BridgeHolder]], bridge), new ScalaBridge(_))

  // FIXME Should be just <: Bridge
  def underlying[T <: ScalaBridge : TypeTag](
    jarDeps: Seq[File],
    dirDeps: Seq[File],
    classLoader: ClassLoader,
    resolvers: Seq[DependencyResolver],
    bridgeHolderTypePath: String,
    bridgeSet: (Class[_], T) => Unit,
    bridgeCreate: ammonite.repl.interp.Interpreter[CustomPreprocessor.Output, Seq[DisplayData]] => T,
    useClassWrapper: Boolean = false
  ): Interpreter =
    new Interpreter with LazyLogging { intp =>

      logger debug s"Jar deps: ${jarDeps.map(_.toString).sorted mkString "\n"}"
      logger debug s"Dir deps: ${dirDeps.map(_.toString).sorted mkString "\n"}"

      val bridgeHolderHandle = "$BridgeHolder"

      val NS = "_root_.jupyter.bridge"

      val bootstrapSymbol = "bootstrap"

      def wrap(codeDefined: CustomPreprocessor.Output, previousImportBlock: String, wrapperName: String) = {
        val (code, defined) = (codeDefined.code, codeDefined.defined)

        if (useClassWrapper)
          // The class definition must be in last position here, for the AmmonitePlugin to find it
          s"""$previousImportBlock

              object $wrapperName {
                val $bootstrapSymbol = new $wrapperName
              }

              class $wrapperName extends Serializable {
                $code
                def $$main(): Seq[$NS.DisplayData] = {
                  Seq[Any](${defined mkString ", "}) .map {
                    case d: $NS.DisplayData => d
                    case v => $NS.DisplayData.RawData(v)
                  }
                }
              }
           """
        else
          s"""$previousImportBlock

              object $wrapperName {
                $code
                def $$main(): Seq[$NS.DisplayData] = {
                  Seq[Any](${defined mkString ", "}) .map {
                    case d: $NS.DisplayData => d
                    case v => $NS.DisplayData.RawData(v)
                  }
                }
              }
           """
      }

      def namesFor(t: Type): Set[String] = {
        // See https://issues.scala-lang.org/browse/SI-5736 and
        // http://stackoverflow.com/questions/17244180/how-to-recognize-scala-constructor-parameter-fields-with-no-underlying-java-fi/17248174#17248174
        val yours = t.members.collect{ case m if m.isPublic && !m.name.decoded.endsWith(nme.LOCAL_SUFFIX_STRING) => m.name.toString }.toSet
        val default = typeOf[Object].members.map(_.name.toString)
        yours -- default
      }

      val previousImports =
        namesFor(typeOf[T]).toList.map(n => n -> ImportData(n, n, "", s"$bridgeHolderHandle.bridge")) ++ // FIXME Should be typeOf[T]
          namesFor(typeOf[IvyConstructor]).toList.map(n => n -> ImportData(n, n, "", "_root_.jupyter.bridge.IvyConstructor"))

      private var bridge: T = _

      def initBridge(underlying: ammonite.repl.interp.Interpreter[CustomPreprocessor.Output, Seq[DisplayData]]) = {
        bridge = bridgeCreate(underlying)
        bridge.setResolvers(resolvers)
      }

      private val underlying = new ammonite.repl.interp.Interpreter[CustomPreprocessor.Output, Seq[DisplayData]](
        (_, _) => (),
        Console.out.println,
        Nil,
        previousImports,
        f => CustomPreprocessor(f()).apply,
        wrap,
        s"object $bridgeHolderHandle extends $bridgeHolderTypePath {}",
        bridgeHolderHandle,
        {
          (intp, cls) =>
            if (bridge == null) initBridge(intp)

            bridgeSet(cls, bridge)
        },
        jarDeps,
        dirDeps
      )

      def executionCount = underlying.history.length

      def interpret(line: String, output: Option[(String => Unit, String => Unit)], storeHistory: Boolean) =
        underlying.processLine(line, _(_), f => Capture(output.map(_._1) getOrElse Console.out.print, output.map(_._2) getOrElse Console.err.print)(f), useClassWrapper) match {
          case res @ Res.Success(ev0) =>
            val ev =
              if (useClassWrapper)
                ev0.copy(imports = ev0.imports.map(d => d.copy(prefix = d.prefix + "." + bootstrapSymbol)))
              else
                ev0

            underlying.handleOutput(Res.Success(ev))
            Interpreter.Value(ev.value.headOption getOrElse DisplayData.EmptyData)
          case Res.Failure(desc) =>
            Interpreter.Error(desc)
          case res @ Res.Buffer(_) =>
            underlying.handleOutput(res)
            Interpreter.Incomplete
          case Res.Skip =>
            Interpreter.Cancelled
          case Res.Exit =>
            // Should not happen to us
            ???
        }

      def complete(code: String, pos: Int) =
        underlying.pressy.complete(pos, underlying.eval.previousImportBlock, code)

      def reset() = {
        underlying.history.clear()
        underlying.eval.newClassloader()
        // FIXME We should do that
        // underlying.eval.previousImports.clear()
      }

      def stop() = {
        ???
      }
    }
}