package jupyter.scala
package config

import com.typesafe.scalalogging.slf4j.LazyLogging
import jupyter.kernel.interpreter._

import scalaz.\/

object ScalaKernel extends InterpreterKernel with LazyLogging {
  def apply() = \/.fromTryCatchNonFatal {
    ScalaInterpreter()
  }
}
