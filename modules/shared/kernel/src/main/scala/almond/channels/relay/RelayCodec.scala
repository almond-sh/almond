package almond.channels.relay

import almond.channels.{Channel, Message}

import java.io.{DataInputStream, DataOutputStream, IOException}

/** Encoding of the messages exchanged between the launcher and the kernel it starts
  *
  * Messages are sent as is, without signature: the launcher checks the signatures of the messages
  * it receives from the frontend, and signs the ones it sends it.
  */
object RelayCodec {

  private val channelIds = Map[Channel, Int](
    Channel.Requests -> 0,
    Channel.Control  -> 1,
    Channel.Publish  -> 2,
    Channel.Input    -> 3
  )
  private val idChannels = channelIds.map(_.swap)

  private def writeBytes(out: DataOutputStream, bytes: Array[Byte]): Unit = {
    out.writeInt(bytes.length)
    out.write(bytes)
  }

  private def readBytes(in: DataInputStream): Array[Byte] = {
    val len = in.readInt()
    if (len < 0)
      throw new RelayException(s"Invalid frame length $len")
    val bytes = new Array[Byte](len)
    in.readFully(bytes)
    bytes
  }

  /** Writes a message, without flushing `out` */
  def write(out: DataOutputStream, channel: Channel, message: Message): Unit = {
    out.writeByte(channelIds(channel))
    out.writeInt(message.idents.length)
    for (ident <- message.idents)
      writeBytes(out, ident.toArray)
    writeBytes(out, message.header)
    writeBytes(out, message.parentHeader)
    writeBytes(out, message.metadata)
    writeBytes(out, message.content)
    out.writeInt(message.buffers.length)
    for (buf <- message.buffers)
      writeBytes(out, buf)
  }

  /** Reads a message, or returns None if the stream ended right before it */
  def read(in: DataInputStream): Option[(Channel, Message)] =
    in.read() match {
      case -1 => None
      case id =>
        val channel = idChannels.getOrElse(
          id,
          throw new RelayException(s"Invalid channel id $id")
        )
        val idents       = Seq.fill(readCount(in))(readBytes(in).toSeq)
        val header       = readBytes(in)
        val parentHeader = readBytes(in)
        val metadata     = readBytes(in)
        val content      = readBytes(in)
        val buffers      = Seq.fill(readCount(in))(readBytes(in))
        Some((channel, Message(idents, header, parentHeader, metadata, content, buffers)))
    }

  private def readCount(in: DataInputStream): Int = {
    val count = in.readInt()
    if (count < 0)
      throw new RelayException(s"Invalid frame count $count")
    count
  }

  final class RelayException(message: String) extends IOException(message)
}
