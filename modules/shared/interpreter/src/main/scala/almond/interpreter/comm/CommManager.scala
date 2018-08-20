package almond.interpreter.comm

import java.util.concurrent.ConcurrentHashMap

import argonaut.JsonObject
import almond.channels.Channel
import almond.interpreter.messagehandlers.MessageHandler
import almond.protocol.{Comm, CommInfo}
import cats.effect.IO

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

/**
  * Manages targets for comm messages from frontends.
  *
  * See https://jupyter-client.readthedocs.io/en/5.2.3/messaging.html#custom-messages.
  *
  * Adding a target with name `"target_name"` allows to receive messages from frontends.
  * From the Jupyter classic UI, one can send messages to this target via code like
  * {{{
  *   var comm = Jupyter.notebook.kernel.comm_manager.new_comm("target_name", '{"a": 2, "b": false}');
  *   comm.open();
  *   comm.send('{"c": 2, "d": {"foo": [1, 2]}}');
  * }}}
  */
final class CommManager {

  private val targets = new ConcurrentHashMap[String, TaskCommTarget]
  private val commIdTargets = new ConcurrentHashMap[String, TaskCommTarget]

  def addTarget(name: String, target: TaskCommTarget): Unit = {

    val previous = targets.putIfAbsent(name, target)

    if (previous != null)
      throw new Exception(s"Target $name already registered")
  }

  def removeTarget(name: String): Unit = {
    val target = targets.remove(name)
    if (target != null)
      for ((id, t) <- commIdTargets.asScala.iterator if t == target)
        targets.remove(id)
  }


  def commOpenHandler(queueEc: ExecutionContext): MessageHandler =
    MessageHandler.blocking(Channel.Requests, Comm.openType, queueEc) { (message, queue) =>
      Option(targets.get(message.content.target_name)) match {
        case None =>
          message
            .reply(Comm.closeType, Comm.Close(message.content.comm_id, JsonObject.empty))
            .enqueueOn(Channel.Requests, queue)

        case Some(target) =>

          val previous = commIdTargets.put(message.content.comm_id, target)
          if (previous != null) {
            // TODO Log error
          }

          target.open(message.content.comm_id, message.content.data)
      }
    }

  def commMessageHandler(queueEc: ExecutionContext): MessageHandler =
    MessageHandler.blocking(Channel.Requests, Comm.messageType, queueEc) { (message, _) =>
      Option(commIdTargets.get(message.content.comm_id)) match {
        case None => // FIXME Log error
          IO.unit
        case Some(target) =>
          target.message(message.content.comm_id, message.content.data)
      }
    }

  def commCloseHandler(queueEc: ExecutionContext): MessageHandler =
    MessageHandler.blocking(Channel.Requests, Comm.closeType, queueEc) { (message, _) =>
      Option(commIdTargets.remove(message.content.comm_id)) match {
        case None => // FIXME Log error
          IO.unit
        case Some(target) =>
          target.close(message.content.comm_id, message.content.data)
      }
    }


  // small and unlikely chance of discrepancies between targets and commIdTargets, as we query them at different times…
  private val allCommInfos =
    IO {
      val map = targets.asScala.iterator.map { case (k, v) => v -> k }.toMap
      commIdTargets
        .asScala
        .iterator
        .map {
          case (id, target) =>
            id -> map.get(target)
        }
        .collect {
          case (id, Some(name)) =>
            id -> CommInfo.Info(name)
        }
        .toMap
    }

  // same as above
  private def commIds(name: String): IO[Seq[String]] =
    IO {
      Option(targets.get(name)) match {
        case Some(target) =>
          commIdTargets
            .asScala
            .iterator
            .collect {
              case (id, `target`) =>
                id
            }
            .toSeq
        case None =>
          Nil
      }
    }

  def commInfoHandler(queueEc: ExecutionContext): MessageHandler =
    MessageHandler.blocking(Channel.Requests, CommInfo.requestType, queueEc) { (message, queue) =>

      val commsIO =
        message.content.target_name match {
          case None =>
            allCommInfos
          case Some(name) =>
            val info = CommInfo.Info(name)
            commIds(name).map(_.map(_ -> info).toMap)
        }

      for {
        comms <- commsIO
        _ <- queue.enqueue1(
          message
            .reply(CommInfo.replyType, CommInfo.Reply(comms))
            .on(Channel.Requests)
        )
      } yield ()
    }

  def messageHandler(queueEc: ExecutionContext): MessageHandler =
    commOpenHandler(queueEc).orElse(
      commMessageHandler(queueEc),
      commCloseHandler(queueEc),
      commInfoHandler(queueEc)
    )

}
