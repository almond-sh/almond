package almond.interpreter.messagehandlers

import almond.channels.Channel
import almond.interpreter.KernelSession
import almond.protocol.Connect

object ConnectMessageHandler {

  def apply(reply: Connect.Reply, session: KernelSession): MessageHandler =
    MessageHandler(Channel.Requests, Connect.requestType) { message =>
      message
        .reply(session, Connect.replyType, reply)
        .streamOn(Channel.Requests)
    }

}
