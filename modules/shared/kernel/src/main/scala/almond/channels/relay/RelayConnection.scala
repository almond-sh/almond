package almond.channels.relay

import almond.channels.{Channel, Connection, Message}
import cats.effect.IO

import java.io.{BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream}
import java.net.{InetAddress, Socket}
import java.util.concurrent.atomic.AtomicBoolean
import java.{util => ju}

import scala.annotation.tailrec
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.control.NonFatal

/** Kernel side of the connection between the launcher and the kernel it starts
  *
  * See [[RelayServer]].
  */
final class RelayConnection private (socket: Socket) extends Connection {

  private val in  = new DataInputStream(new BufferedInputStream(socket.getInputStream))
  private val out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream))

  // guards pending and inputClosed
  private val lock        = new Object
  private val pending     = Channel.channels.map(_ -> new ju.ArrayDeque[Message]).toMap
  private var inputClosed = false

  private val closed = new AtomicBoolean(false)

  private val readerThread: Thread =
    new Thread("almond-relay-reader") {
      setDaemon(true)
      override def run(): Unit =
        try {
          @tailrec
          def loop(): Unit =
            RelayCodec.read(in) match {
              case Some((channel, message)) =>
                lock.synchronized {
                  pending(channel).add(message)
                  lock.notifyAll()
                }
                loop()
              case None =>
            }
          loop()
        }
        catch {
          case NonFatal(_) =>
          // socket closed, or launcher gone, reported via tryRead
        }
        finally
          lock.synchronized {
            inputClosed = true
            lock.notifyAll()
          }
    }

  def open: IO[Map[Option[Channel], Int]] =
    IO {
      synchronized {
        if (readerThread.getState == Thread.State.NEW)
          readerThread.start()
      }
      Map.empty
    }

  def send(channel: Channel, message: Message): IO[Unit] =
    IO.blocking {
      out.synchronized {
        RelayCodec.write(out, channel, message)
        out.flush()
      }
    }

  def tryRead(
    channels: Seq[Channel],
    pollingDelay: Duration
  ): IO[Option[Either[Unit, (Channel, Message)]]] =
    IO.blocking {
      val deadlineOpt = pollingDelay match {
        case d: FiniteDuration => Some(d.fromNow)
        case _                 => None
      }
      lock.synchronized {
        @tailrec
        def loop(): Option[Either[Unit, (Channel, Message)]] =
          channels.find(!pending(_).isEmpty) match {
            case Some(channel) =>
              Some(Right((channel, pending(channel).poll())))
            case None if inputClosed =>
              Some(Left(()))
            case None =>
              deadlineOpt match {
                case Some(deadline) =>
                  val timeLeft = deadline.timeLeft
                  if (timeLeft <= Duration.Zero) None
                  else {
                    lock.wait(math.max(1L, timeLeft.toMillis))
                    loop()
                  }
                case None =>
                  lock.wait()
                  loop()
              }
          }
        loop()
      }
    }

  def close(partial: Boolean, lingerDuration: Duration): IO[Unit] =
    IO.blocking {
      lock.synchronized {
        inputClosed = true
        lock.notifyAll()
      }
      if (!partial && closed.compareAndSet(false, true))
        try {
          out.synchronized(out.flush())
          // Letting the launcher close the connection first, so that what we sent isn't discarded
          // if the connection is reset because of messages we didn't read
          socket.shutdownOutput()
          val lingerMillis = lingerDuration match {
            case d: FiniteDuration => math.max(1L, d.toMillis)
            case _                 => 0L // no timeout
          }
          readerThread.join(lingerMillis)
        }
        finally
          socket.close()
    }
}

object RelayConnection {

  def connect(relayFile: RelayFile): IO[RelayConnection] =
    IO.blocking {
      val socket = new Socket(InetAddress.getLoopbackAddress, relayFile.port)
      try {
        socket.setTcpNoDelay(true)
        val out = socket.getOutputStream
        out.write(relayFile.keyBytes)
        out.flush()
        new RelayConnection(socket)
      }
      catch {
        case NonFatal(e) =>
          socket.close()
          throw e
      }
    }
}
