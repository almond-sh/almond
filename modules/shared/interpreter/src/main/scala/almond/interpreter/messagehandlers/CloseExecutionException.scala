package almond.interpreter.messagehandlers

import almond.channels.{Channel, Message => RawMessage}

class CloseExecutionException(val messages: Seq[(Channel, RawMessage)]) extends Exception
