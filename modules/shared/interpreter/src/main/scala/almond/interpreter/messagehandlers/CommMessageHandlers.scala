package almond.interpreter.messagehandlers

import almond.channels.Channel
import almond.interpreter.comm.CommTargetManager
import almond.logger.LoggerContext
import almond.protocol.{Comm, CommInfo}
import cats.effect.IO

import scala.concurrent.ExecutionContext
import almond.protocol.RawJson

final case class CommMessageHandlers(
  commManager: CommTargetManager,
  queueEc: ExecutionContext,
  logCtx: LoggerContext
) {

  def commOpenHandler: MessageHandler =
    MessageHandler.blocking(Channel.Requests, Comm.openType, queueEc, logCtx) { (message, queue) =>
      commManager.target(message.content.target_name) match {
        case None =>
          message
            .reply(Comm.closeType, Comm.Close(message.content.comm_id, RawJson.emptyObj))
            .enqueueOn(Channel.Requests, queue)

        case Some(target) =>
          commManager.addId(target, message.content.comm_id)
          target.open(message.content.comm_id, message.content.data.value)
      }
    }

  def commMessageHandler: MessageHandler =
    MessageHandler.blocking(Channel.Requests, Comm.messageType, queueEc, logCtx) { (message, _) =>
      commManager.fromId(message.content.comm_id) match {
        case None => // FIXME Log error
          IO.unit
        case Some(target) =>
          target.message(message.content.comm_id, message.content.data.value)
      }
    }

  def commCloseHandler: MessageHandler =
    MessageHandler.blocking(Channel.Requests, Comm.closeType, queueEc, logCtx) { (message, _) =>
      commManager.removeId(message.content.comm_id) match {
        case None => // FIXME Log error
          IO.unit
        case Some(target) =>
          target.close(message.content.comm_id, message.content.data.value)
      }
    }

  def commInfoHandler: MessageHandler =
    MessageHandler.blocking(Channel.Requests, CommInfo.requestType, queueEc, logCtx) {
      (message, queue) =>

        val commsIO =
          message.content.target_name match {
            case None =>
              commManager.allInfos
            case Some(name) =>
              val info = CommInfo.Info(name)
              commManager.allIds(name).map(_.map(_ -> info).toMap)
          }

        for {
          comms <- commsIO
          _ <- queue.offer(
            message
              .reply(CommInfo.replyType, CommInfo.Reply(comms))
              .on(Channel.Requests)
          )
        } yield ()
    }

  def messageHandler: MessageHandler =
    commOpenHandler.orElse(
      commMessageHandler,
      commCloseHandler,
      commInfoHandler
    )

}
