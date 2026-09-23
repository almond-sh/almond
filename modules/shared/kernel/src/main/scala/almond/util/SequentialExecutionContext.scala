package almond.util

import scala.concurrent.ExecutionContext

// vaguely adapted from TrampolineEC stuff in cats-effects (without trampolining here…)
class SequentialExecutionContext extends ExecutionContext {
  def execute(r: Runnable): Unit = r.run()
  def reportFailure(e: Throwable): Unit =
    Thread.getDefaultUncaughtExceptionHandler match {
      case null => e.printStackTrace()
      case h    => h.uncaughtException(Thread.currentThread(), e)
    }
}
