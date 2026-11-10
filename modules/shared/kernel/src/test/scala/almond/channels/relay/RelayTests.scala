package almond.channels.relay

import java.io.{BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream}
import java.net.{InetAddress, Socket}
import java.nio.charset.StandardCharsets

import almond.channels.{Channel, Message}
import cats.effect.unsafe.IORuntime
import utest._

import scala.concurrent.duration.DurationInt

object RelayTests extends TestSuite {

  private implicit val ioRuntime: IORuntime = IORuntime.global

  private def message(content: String, idents: Seq[String] = Nil) =
    Message(
      idents.map(_.getBytes(StandardCharsets.UTF_8).toSeq),
      "header".getBytes(StandardCharsets.UTF_8),
      "parent_header".getBytes(StandardCharsets.UTF_8),
      "metadata".getBytes(StandardCharsets.UTF_8),
      content.getBytes(StandardCharsets.UTF_8),
      Seq("buffer".getBytes(StandardCharsets.UTF_8))
    )

  val tests = Tests {

    test("relay") {
      val server    = RelayServer.create()
      val relayFile = server.relayFile

      // peers that don't know the key are disconnected, and don't prevent the kernel
      // from connecting
      val silentPeer = new Socket(InetAddress.getLoopbackAddress, relayFile.port)
      val wrongPeer  = new Socket(InetAddress.getLoopbackAddress, relayFile.port)
      wrongPeer.getOutputStream.write(new Array[Byte](relayFile.keyBytes.length))
      wrongPeer.getOutputStream.flush()

      val kernelConn = RelayConnection.connect(relayFile).unsafeRunSync()
      kernelConn.open.unsafeRunSync()

      val socket = server.accept(() => true).getOrElse(sys.error("No kernel connected"))

      assert(wrongPeer.getInputStream.read() == -1)
      silentPeer.close()
      wrongPeer.close()

      val in  = new DataInputStream(new BufferedInputStream(socket.getInputStream))
      val out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream))

      // launcher to kernel
      val request = message("request", Seq("client-id"))
      RelayCodec.write(out, Channel.Requests, request)
      RelayCodec.write(out, Channel.Control, message("control"))
      out.flush()
      // only reading from the channels asked
      val control = kernelConn.tryRead(Seq(Channel.Control), 5.seconds).unsafeRunSync()
      assert(control == Some(Right((Channel.Control, message("control")))))
      val received = kernelConn.tryRead(Channel.channels, 5.seconds).unsafeRunSync()
      assert(received == Some(Right((Channel.Requests, request))))
      assert(kernelConn.tryRead(Channel.channels, 10.millis).unsafeRunSync().isEmpty)

      // kernel to launcher
      val reply = message("reply", Seq("client-id"))
      kernelConn.send(Channel.Requests, reply).unsafeRunSync()
      kernelConn.send(Channel.Publish, message("output")).unsafeRunSync()
      assert(RelayCodec.read(in) == Some((Channel.Requests, reply)))
      assert(RelayCodec.read(in) == Some((Channel.Publish, message("output"))))

      // the kernel closing its side, what it sent before is still received
      val closing = kernelConn.close(partial = false, lingerDuration = 5.seconds).unsafeToFuture()
      assert(RelayCodec.read(in).isEmpty)
      socket.close()
      scala.concurrent.Await.result(closing, 10.seconds)
      assert(kernelConn.tryRead(Channel.channels, 1.second).unsafeRunSync() == Some(Left(())))
    }

    test("stop waiting") {
      val server = RelayServer.create()
      val res    = server.accept(() => false)
      assert(res.isEmpty)
    }
  }
}
