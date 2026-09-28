package almond.interpreter.util

import almond.interpreter.{Completion, Inspection, Interpreter, IsCompleteResult}
import almond.logger.LoggerContext

trait AsyncInterpreterOps extends Interpreter {

  def logCtx: LoggerContext

  // These run in threads of cancellableFuturePool, so that they can run while the interpreter
  // is busy (running a cell, typically). Implementations of isComplete / complete / inspect
  // must then be thread-safe with respect to execute.
  //
  // As most "cancelled" calculations (completions, inspections, …) can't really be stopped once
  // they started, cancelling one only has an effect if it didn't start yet. In that case, an empty
  // result is returned.
  private val cancellableFuturePool = new CancellableFuturePool(logCtx)

  override def asyncIsComplete(code: String): Some[CancellableFuture[Option[IsCompleteResult]]] =
    Some(cancellableFuturePool.lazyCancellableFuture(isComplete(code), None))
  override def asyncComplete(code: String, pos: Int): Some[CancellableFuture[Completion]] =
    Some(cancellableFuturePool.lazyCancellableFuture(complete(code, pos), Completion.empty(pos)))
  override def asyncInspect(
    code: String,
    pos: Int,
    detailLevel: Int
  ): Some[CancellableFuture[Option[Inspection]]] =
    Some(cancellableFuturePool.lazyCancellableFuture(inspect(code, pos, detailLevel), None))

}
