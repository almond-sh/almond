package almond.interpreter.util

import almond.interpreter.{Completion, Inspection, Interpreter, IsCompleteResult}
import almond.logger.LoggerContext

trait AsyncInterpreterOps extends Interpreter {

  def logCtx: LoggerContext

  // As most "cancelled" calculations (completions, inspections, …) are run in other threads by the presentation
  // compiler, they aren't actually cancelled, they'll keep running in the background. This just interrupts
  // the thread that waits for the background calculation.
  // Having a thread that blocks for results, in turn, is almost required by scala.tools.nsc.interactive.Response…
  private val cancellableFuturePool = new CancellableFuturePool(logCtx)

  override def asyncIsComplete(code: String): Some[CancellableFuture[Option[IsCompleteResult]]] =
    Some(cancellableFuturePool.cancellableFuture(isComplete(code)))
  override def asyncComplete(code: String, pos: Int): Some[CancellableFuture[Completion]] =
    Some(cancellableFuturePool.cancellableFuture(complete(code, pos)))
  override def asyncInspect(
    code: String,
    pos: Int,
    detailLevel: Int
  ): Some[CancellableFuture[Option[Inspection]]] =
    Some(cancellableFuturePool.cancellableFuture(inspect(code, pos)))

}
