package almond.channels.relay

import almond.channels.Connection
import almond.logger.LoggerContext
import cats.effect.IO
import fs2.Stream

import java.io.{BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream}
import java.net.{InetAddress, ServerSocket, Socket, SocketException, SocketTimeoutException}
import java.security.{MessageDigest, SecureRandom}
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.control.NonFatal

/** Launcher side of the connection between the launcher and the kernel it starts
  *
  * The launcher keeps the ZeroMQ sockets the frontend connects to bound while the kernel runs, and
  * forwards messages between these and the kernel. That way, these ports are never released, and no
  * other process can bind them while the frontend still uses them.
  *
  * The kernel connects to a port bound on the loopback interface by the launcher before the kernel
  * is started, and authenticates itself by sending a random key, that the launcher passes it via a
  * file only readable by the current user.
  */
final class RelayServer private (serverSocket: ServerSocket, key: Array[Byte])
    extends AutoCloseable {

  def relayFile: RelayFile =
    RelayFile(serverSocket.getLocalPort, key)

  /** Waits for the kernel to connect
    *
    * Peers that don't send the right key within `handshakeTimeout` are disconnected. Handshakes run
    * concurrently, so that peers that don't send anything don't delay the kernel.
    *
    * @param keepWaiting
    *   called regularly, accepting stops when it returns false (when the kernel exited, typically)
    * @return
    *   the socket connected to the kernel, if it connected
    */
  def accept(
    keepWaiting: () => Boolean,
    handshakeTimeout: FiniteDuration = 30.seconds
  ): Option[Socket] = {

    val accepted = new AtomicReference[Socket]

    def handshake(socket: Socket): Unit = {
      val t = new Thread("almond-relay-handshake") {
        setDaemon(true)
        override def run(): Unit =
          try {
            socket.setSoTimeout(handshakeTimeout.toMillis.toInt)
            val received = new Array[Byte](key.length)
            // not buffering, so that we don't read anything past the key
            new DataInputStream(socket.getInputStream).readFully(received)
            if (MessageDigest.isEqual(received, key) && accepted.compareAndSet(null, socket)) {
              socket.setSoTimeout(0)
              // wakes up the accept loop
              serverSocket.close()
            }
            else
              socket.close()
          }
          catch {
            case NonFatal(_) =>
              socket.close()
          }
      }
      t.start()
    }

    def acceptOnce(): Unit =
      try handshake(serverSocket.accept())
      catch {
        case _: SocketTimeoutException                   =>
        case _: SocketException if serverSocket.isClosed =>
      }

    serverSocket.setSoTimeout(1000)
    try
      while (accepted.get() == null && !serverSocket.isClosed && keepWaiting())
        acceptOnce()
    finally
      close()

    Option(accepted.get())
  }

  def close(): Unit =
    serverSocket.close()
}

object RelayServer {

  def create(): RelayServer = {
    val serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    val key          = new Array[Byte](32)
    new SecureRandom().nextBytes(key)
    new RelayServer(serverSocket, key)
  }

  /** Forwards messages between `connection` and the kernel connected to `socket`, until the kernel
    * closes its side of the connection
    */
  def forward(connection: Connection, socket: Socket, logCtx: LoggerContext): IO[Unit] = {

    val log = logCtx(getClass)

    val prepare = IO.blocking {
      socket.setTcpNoDelay(true)
      val in  = new DataInputStream(new BufferedInputStream(socket.getInputStream))
      val out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream))
      (in, out)
    }

    def toKernel(out: DataOutputStream): IO[Unit] =
      connection.stream()
        .evalMap {
          case (channel, message) =>
            IO.blocking {
              RelayCodec.write(out, channel, message)
              out.flush()
            }
        }
        .compile
        .drain
        .handleErrorWith(e => IO(log.debug("Error while sending messages to kernel", e)))

    def fromKernel(in: DataInputStream): IO[Unit] =
      Stream.repeatEval(IO.blocking(RelayCodec.read(in)))
        .unNoneTerminate
        .evalMap {
          case (channel, message) =>
            connection.send(channel, message)
        }
        .compile
        .drain
        .handleErrorWith(e => IO(log.debug("Error while reading messages from kernel", e)))

    prepare
      .flatMap {
        case (in, out) =>
          toKernel(out).background.surround(fromKernel(in))
      }
      .guarantee(IO.blocking(socket.close()))
  }
}
