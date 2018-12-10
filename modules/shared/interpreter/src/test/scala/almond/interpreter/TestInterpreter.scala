package almond.interpreter

import almond.interpreter.api.{CommHandler, DisplayData, OutputHandler}
import almond.interpreter.input.InputManager
import almond.interpreter.util.CancellableFuture
import argonaut.Json

import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.Duration
import scala.util.Success

final class TestInterpreter extends Interpreter {
  def execute(
    code: String,
    storeHistory: Boolean,
    inputManager: Option[InputManager],
    outputHandler: Option[OutputHandler]
  ): ExecuteResult =
    if (code.startsWith("input:"))
      inputManager match {
        case None =>
          ExecuteResult.Error("input not available")
        case Some(m) =>
          val s = Await.result(
            m.readInput(code.stripPrefix("input:")),
            Duration.Inf
          )
          count += 1
          ExecuteResult.Success(DisplayData.text("> " + s))
      }
    else if (code.startsWith("comm-open:"))
      commHandlerOpt0 match {
        case None =>
          ExecuteResult.Error("comm not available")
        case Some(h) =>
          val target = code.stripPrefix("comm-open:")
          h.commOpen(target, target, "{}")
          count += 1
          ExecuteResult.Success()
      }
    else if (code.startsWith("comm-message:"))
      commHandlerOpt0 match {
        case None =>
          ExecuteResult.Error("comm not available")
        case Some(h) =>
          val target = code.stripPrefix("comm-message:")
          h.commMessage(target, """{"a": "b"}""")
          count += 1
          ExecuteResult.Success()
      }
    else if (code.startsWith("comm-close:"))
      commHandlerOpt0 match {
        case None =>
          ExecuteResult.Error("comm not available")
        case Some(h) =>
          val target = code.stripPrefix("comm-close:")
          h.commClose(target, "{}")
          count += 1
          ExecuteResult.Success()
      }
    else if (code.startsWith("echo:")) {
      count += 1
      ExecuteResult.Success(DisplayData.text(code.stripPrefix("echo:")))
    } else
      ExecuteResult.Error("not input")

  private var count = 0
  def currentLine() = count

  def kernelInfo() = ???

  override def asyncComplete(code: String, pos: Int) = {

    val res =
      if (code == "cancel") {
        val p = Promise[Completion]()
        CancellableFuture(p.future, () => p.complete(Success(Completion(0, code.length, Seq("cancelled")))))
      } else if (code.startsWith("meta:")) {
        import argonaut._
        import argonaut.Argonaut._
        import almond.protocol.internal.ExtraCodecs._
        val c = code.drop("meta:".length).decodeEither[JsonObject].right.toOption match {
          case None =>
            Completion.empty(pos)
          case Some(obj) =>
            Completion(pos, pos, Seq("sent"), obj)
        }
        CancellableFuture(Future.successful(c), () => sys.error("should not happen"))
      } else
        CancellableFuture(Future.successful(Completion(pos, pos, Seq("?"))), () => sys.error("should not happen"))

    Some(res)
  }

  override def complete(code: String, pos: Int) =
    sys.error("should not be called")

  override def asyncInspect(code: String, pos: Int, detailLevel: Int) = {

    val res =
      if (code == "cancel") {
        val p = Promise[Option[Inspection]]()
        CancellableFuture(
          p.future,
          () => p.complete(
            Success(
              Some(
                Inspection(Map("cancelled" -> Json.jBool(true)))
              )
            )
          )
        )
      } else
        CancellableFuture(
          Future.successful(
            Some(
              Inspection(Map("result" -> Json.jString(s"$code: code")))
            )
          ),
          () => sys.error("should not happen")
        )

    Some(res)
  }

  override def inspect(code: String, pos: Int, detailLevel: Int) =
    sys.error("should not be called")

  override def asyncIsComplete(code: String) = {

    val res =
      if (code == "cancel") {
        val p = Promise[Option[IsCompleteResult]]()
        CancellableFuture(
          p.future,
          () => p.complete(
            Success(
              Some(
                IsCompleteResult.Invalid
              )
            )
          )
        )
      } else
        CancellableFuture(
          Future.successful(
            Some(
              IsCompleteResult.Complete
            )
          ),
          () => sys.error("should not happen")
        )

    Some(res)
  }

  override def isComplete(code: String) =
    sys.error("should not be called")

  private var commHandlerOpt0 = Option.empty[CommHandler]
  override def supportComm = true
  override def setCommHandler(commHandler: CommHandler): Unit =
    commHandlerOpt0 = Some(commHandler)

  private var shutdownCalled0 = false

  def shutdownCalled(): Boolean =
    shutdownCalled0
  override def shutdown(): Unit = {
    shutdownCalled0 = true
  }
}
