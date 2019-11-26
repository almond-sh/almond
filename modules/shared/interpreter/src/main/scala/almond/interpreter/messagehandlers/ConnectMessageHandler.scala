package almond.interpreter.messagehandlers

import almond.channels.Channel
import almond.protocol.Connect
import almond.protocol.Codecs.{connectRequestCodec, connectReplyCodec}

object ConnectMessageHandler {

  def apply(reply: Connect.Reply): MessageHandler =
    MessageHandler(Channel.Requests, Connect.requestType) { message =>
      message
        .reply(Connect.replyType, reply)
        .streamOn(Channel.Requests)
    }

}
