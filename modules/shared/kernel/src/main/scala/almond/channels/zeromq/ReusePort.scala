package almond.channels.zeromq

import java.net.{
  Inet4Address,
  InetAddress,
  InetSocketAddress,
  ProtocolFamily,
  SocketOption,
  StandardSocketOptions
}
import java.nio.channels.{DatagramChannel, Pipe, ServerSocketChannel, SocketChannel}
import java.nio.channels.spi.{AbstractSelector, SelectorProvider}

import scala.util.Try

/** Helpers to bind sockets with SO_REUSEPORT
  *
  * The launcher binds the sockets the frontend connects to with SO_REUSEPORT, and also binds
  * "placeholder" sockets on the same ports, with SO_REUSEPORT too. Placeholders are bound, but
  * never listen nor connect, so that they don't accept connections. Placeholders survive the exec
  * of the actual kernel in the launcher process, and keep the ports reserved during that exec,
  * until the actual kernel binds the ports again, with SO_REUSEPORT too. The OS only lets processes
  * of the same user bind ports along SO_REUSEPORT sockets, so that other users can't take over the
  * ports in between. (macOS lets other users share wildcard addresses bound with SO_REUSEPORT
  * though, so the launcher only uses this on macOS for specific addresses, like 127.0.0.1.)
  */
object ReusePort {

  // SO_REUSEPORT was only added in Java 9, and we target Java 8
  private lazy val reusePortOption: Option[SocketOption[java.lang.Boolean]] =
    Try {
      classOf[StandardSocketOptions]
        .getField("SO_REUSEPORT")
        .get(null)
        .asInstanceOf[SocketOption[java.lang.Boolean]]
    }.toOption

  private def option: SocketOption[java.lang.Boolean] =
    reusePortOption.getOrElse {
      throw new UnsupportedOperationException("SO_REUSEPORT requires Java >= 9")
    }

  /** Whether SO_REUSEPORT can be set from the current JVM, and is supported by the current OS */
  lazy val supported: Boolean =
    reusePortOption.exists { opt =>
      Try {
        val channel = SocketChannel.open()
        try channel.supportedOptions().contains(opt)
        finally channel.close()
      }.getOrElse(false)
    }

  private final class Provider(underlying: SelectorProvider) extends SelectorProvider {
    def openDatagramChannel(): DatagramChannel =
      underlying.openDatagramChannel()
    def openDatagramChannel(family: ProtocolFamily): DatagramChannel =
      underlying.openDatagramChannel(family)
    def openPipe(): Pipe =
      underlying.openPipe()
    def openSelector(): AbstractSelector =
      underlying.openSelector()
    def openServerSocketChannel(): ServerSocketChannel = {
      val channel = underlying.openServerSocketChannel()
      try channel.setOption(option, java.lang.Boolean.TRUE)
      catch {
        case t: Throwable =>
          channel.close()
          throw t
      }
      channel
    }
    def openSocketChannel(): SocketChannel =
      underlying.openSocketChannel()
  }

  /** Makes JeroMQ sockets set SO_REUSEPORT on the channels they listen on
    *
    * Pass it to `ZMQ.Socket#setSelectorChooser` before binding the socket.
    */
  lazy val selectorChooser: zmq.io.net.SelectorProviderChooser = {
    val provider = new Provider(SelectorProvider.provider())
    new zmq.io.net.SelectorProviderChooser {
      def choose(addr: zmq.io.net.Address.IZAddress, options: zmq.Options): SelectorProvider =
        provider
    }
  }

  /** Resolves a host from a ZeroMQ TCP endpoint, like JeroMQ does when binding (with IPv6 disabled)
    */
  def resolve(host: String): InetAddress =
    if (host == "*") InetAddress.getByName("0.0.0.0")
    else {
      val addresses = InetAddress.getAllByName(host)
      addresses.collectFirst { case a: Inet4Address => a }.getOrElse(addresses.head)
    }

  /** Binds a placeholder socket on `address`
    *
    * The returned channel is bound, but never listens nor connects. It doesn't have SO_REUSEADDR,
    * so that it doesn't take connections over from listening sockets on macOS.
    */
  def placeholder(address: InetSocketAddress): SocketChannel = {
    val channel = SocketChannel.open()
    try {
      channel.setOption(option, java.lang.Boolean.TRUE)
      channel.bind(address)
    }
    catch {
      case t: Throwable =>
        channel.close()
        throw t
    }
    channel
  }
}
