package almond.interpreter.lsp

import almond.interpreter.api.{CommHandler, CommTarget}
import almond.logger.LoggerContext
import almond.protocol.RawJson
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

import scala.util.control.NonFatal

/** Comm target carrying LSP messages between frontends and a [[LanguageServer]]
  *
  * Each comm opened by a frontend on [[LanguageServer.commTarget]] starts a new LSP session.
  */
final class LspCommTarget(
  server: LanguageServer,
  commHandler: CommHandler,
  logCtx: LoggerContext
) extends CommTarget {

  import LspCommTarget._

  private val log      = logCtx(getClass)
  private val sessions = new ConcurrentHashMap[String, LanguageServer.Session]

  def open(id: String, data: Array[Byte]): Unit = {
    val session  = server.session(client(id))
    val previous = sessions.put(id, session)
    if (previous != null) {
      log.warn(s"LSP comm $id opened twice, closing its former session")
      previous.close()
    }
  }

  def message(id: String, data: Array[Byte]): Unit =
    Option(sessions.get(id)) match {
      case None =>
        log.warn(s"Ignoring message for unknown LSP comm $id")
      case Some(session) =>
        val messageOpt =
          try Some(readFromArray[Data](data).message)
          catch {
            case NonFatal(e) =>
              log.warn(s"Ignoring malformed message on LSP comm $id", e)
              None
          }
        for (message <- messageOpt)
          session.message(message)
    }

  def close(id: String, data: Array[Byte]): Unit =
    for (session <- Option(sessions.remove(id)))
      session.close()

  private def client(id: String): LanguageServer.Client =
    new LanguageServer.Client {
      def send(message: RawJson): Unit =
        commHandler.commMessage(id, writeToArray(Data(message)), emptyObj)
      def close(): Unit =
        if (sessions.remove(id) != null) {
          commHandler.unregisterCommId(id)
          commHandler.commClose(id, emptyObj, emptyObj)
        }
    }
}

object LspCommTarget {

  private final case class Data(message: RawJson)

  private implicit val dataCodec: JsonValueCodec[Data] =
    JsonCodecMaker.make

  private def emptyObj = "{}".getBytes(StandardCharsets.UTF_8)

  /** Registers a comm target for `server`, so that frontends can open LSP sessions with it */
  def register(
    server: LanguageServer,
    commHandler: CommHandler,
    logCtx: LoggerContext
  ): Unit =
    commHandler.registerCommTarget(
      LanguageServer.commTarget,
      new LspCommTarget(server, commHandler, logCtx)
    )
}
