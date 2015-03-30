package jupyter
package scala

import ammonite.repl.interp.Classpath
import bridge.Bridge
import kernel.interpreter._
import scalaz.\/

object EmbeddedScalaKernel extends InterpreterKernel {
  def interpreter(classLoader: Option[ClassLoader]) = \/.fromTryCatchNonFatal {
    ScalaInterpreter(
      Classpath.jarDeps,
      Classpath.dirDeps,
      classLoader getOrElse getClass.getClassLoader,
      ScalaKernel.resolvers
    )
  }
}
