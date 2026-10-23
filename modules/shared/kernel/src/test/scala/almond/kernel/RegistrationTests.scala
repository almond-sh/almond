package almond.kernel

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import almond.channels.{Channel, ConnectionParameters}
import almond.channels.zeromq.ZeromqThreads
import almond.interpreter.{Message, TestInterpreter}
import almond.logger.LoggerContext
import almond.protocol.{Header, KernelInfo, RawJson, Registration, Shutdown}
import almond.protocol.Codecs.unitCodec
import almond.util.Secret
import almond.util.ThreadUtil.{
  attemptShutdownExecutionContext,
  singleThreadedExecutionContextExecutorService
}
import cats.effect.IO
import com.github.plokhotnyuk.jsoniter_scala.core.{readFromArray, writeToArray}
import org.zeromq.{SocketType, ZMQ}
import utest._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt

object RegistrationTests extends TestSuite {

  val logCtx = LoggerContext.nop // debug: LoggerContext.stderr(almond.logger.Level.Debug)

  val interpreterEc  = singleThreadedExecutionContextExecutorService("test-interpreter")
  val cancellablesEc = singleThreadedExecutionContextExecutorService("test-cancellables")

  val threads = KernelThreads.create("test")

  override def utestAfterAll() = {
    threads.attemptShutdown()
    if (!attemptShutdownExecutionContext(interpreterEc))
      println(s"Don't know how to shutdown $interpreterEc")
  }

  private def hmac(key: String, content: Array[Byte]): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key.getBytes(UTF_8), "HmacSHA256"))
    mac.doFinal(content).map(b => f"$b%02x").mkString
  }

  private val delimiter = "<IDS|MSG>".getBytes(UTF_8)

  val tests = Tests {

    test("parse registration files") {
      val specStyle =
        """{
          |  "kernel_id": "unique_kernel_id",
          |  "transport": "tcp",
          |  "registration_ip": "127.0.0.1",
          |  "registration_port": 51587,
          |  "signature_scheme": "hmac-sha256",
          |  "key": "a0436f6c-1916-498b-8eb9-e81ab9368e84"
          |}""".stripMargin.getBytes(UTF_8)
      // xeus writes the port as a string, and adds the IP to bind the kernel channels to
      val xeusStyle =
        """{
          |  "kernel_id": "unique_kernel_id",
          |  "transport": "tcp",
          |  "ip": "0.0.0.0",
          |  "registration_ip": "127.0.0.1",
          |  "registration_port": "51587",
          |  "signature_scheme": "hmac-sha256",
          |  "key": "a0436f6c-1916-498b-8eb9-e81ab9368e84"
          |}""".stripMargin.getBytes(UTF_8)
      val connectionFile =
        """{
          |  "control_port": 50160,
          |  "shell_port": 57503,
          |  "transport": "tcp",
          |  "signature_scheme": "hmac-sha256",
          |  "stdin_port": 52597,
          |  "hb_port": 42540,
          |  "ip": "127.0.0.1",
          |  "iopub_port": 40885,
          |  "key": "a0436f6c-1916-498b-8eb9-e81ab9368e84"
          |}""".stripMargin.getBytes(UTF_8)

      assert(Registration.isRegistration(specStyle))
      assert(Registration.isRegistration(xeusStyle))
      assert(!Registration.isRegistration(connectionFile))

      val spec = readFromArray(specStyle)(Registration.codec)
      assert(spec.registration_port == 51587)
      assert(spec.registrationUri == "tcp://127.0.0.1:51587")
      assert(spec.connectionParameters.ip == "127.0.0.1")
      assert(spec.key.value == "a0436f6c-1916-498b-8eb9-e81ab9368e84")

      val xeus = readFromArray(xeusStyle)(Registration.codec)
      assert(xeus.registration_port == 51587)
      assert(xeus.connectionParameters.ip == "0.0.0.0")
    }

    test("handshake") {

      val key      = Secret.randomUuid()
      val kernelId = "the-kernel-id"

      val context            = ZMQ.context(1)
      val registrationSocket = context.socket(SocketType.ROUTER)
      registrationSocket.setReceiveTimeOut(20000)
      val registrationPort = registrationSocket.bindToRandomPort("tcp://127.0.0.1")

      val registrationFile = Files.createTempFile("almond-test-registration", ".json")
      val registration = Registration(
        kernel_id = kernelId,
        transport = "tcp",
        registration_ip = "127.0.0.1",
        registration_port = registrationPort,
        key = key,
        signature_scheme = Some("hmac-sha256")
      )
      Files.write(registrationFile, writeToArray(registration)(Registration.codec))

      // Pseudo registration service, receiving the kernel connection info
      val registrationService = Future {
        val routingId = registrationSocket.recv()
        assert(routingId != null)
        val frames = {
          val b = Vector.newBuilder[Array[Byte]]
          while (registrationSocket.hasReceiveMore)
            b += registrationSocket.recv()
          b.result()
        }
        assert(frames.length == 3)
        val Seq(delim, signature, content) = frames
        assert(java.util.Arrays.equals(delim, delimiter))
        assert(new String(signature, UTF_8) == hmac(key.value, content))

        val ack = "ACK".getBytes(UTF_8)
        registrationSocket.sendMore(routingId)
        registrationSocket.sendMore(delimiter)
        registrationSocket.sendMore(hmac(key.value, ack).getBytes(UTF_8))
        registrationSocket.send(ack)

        readFromArray(content)(Registration.ConnectionInfo.codec)
      }(scala.concurrent.ExecutionContext.global)

      val zeromqThreads = ZeromqThreads.create("test-kernel")
      val clientThreads = ZeromqThreads.create("test-client")

      try {
        val (run, _) =
          Kernel.create(new TestInterpreter, interpreterEc, threads, cancellablesEc, logCtx)
            .flatMap(_.runOnConnectionFileAllowClose(
              registrationFile,
              "kernel",
              zeromqThreads,
              Nil,
              autoClose = true,
              lingerDuration = 2.seconds,
              bindToRandomPorts = None
            ))
            .unsafeRunSync()(threads.ioRuntime)
        val runFuture = run.unsafeToFuture()(threads.ioRuntime)

        val info = Await.result(registrationService, 30.seconds)
        assert(info.kernel_id == kernelId)
        val ports = Seq(
          info.shell_port,
          info.control_port,
          info.stdin_port,
          info.iopub_port,
          info.hb_port
        ).map(_.toInt)
        assert(ports.forall(_ > 0))
        assert(ports.distinct.length == ports.length)

        // registration file left untouched
        assert(Registration.isRegistration(Files.readAllBytes(registrationFile)))

        // Checking that the kernel is reachable at the ports it sent
        val clientParams = ConnectionParameters(
          ip = "127.0.0.1",
          transport = "tcp",
          stdin_port = info.stdin_port.toInt,
          control_port = info.control_port.toInt,
          hb_port = info.hb_port.toInt,
          shell_port = info.shell_port.toInt,
          iopub_port = info.iopub_port.toInt,
          key = key,
          signature_scheme = Some("hmac-sha256")
        )

        val kernelInfoRequest =
          Message(Header.random("test", KernelInfo.requestType), ())
        val shutdownRequest =
          Message(Header.random("test", Shutdown.requestType), Shutdown.Request(restart = false))

        def readReply(
          conn: almond.channels.Connection,
          channel: Channel,
          msgType: String
        ): IO[Message[RawJson]] =
          conn.tryRead(Seq(channel), 1.second).flatMap {
            case Some(Right((`channel`, raw))) =>
              IO.fromEither(Message.parse[RawJson](raw)).flatMap { m =>
                if (m.header.msg_type == msgType) IO.pure(m)
                else readReply(conn, channel, msgType)
              }
            case _ =>
              readReply(conn, channel, msgType)
          }

        val clientRun =
          for {
            client <- clientParams.channels(
              bind = false,
              clientThreads,
              None,
              logCtx,
              bindToRandomPorts = false,
              identityOpt = None
            )
            _ <- client.open
            _ <- client.send(Channel.Requests, kernelInfoRequest.asRawMessage)
            reply <- readReply(client, Channel.Requests, KernelInfo.replyType.messageType)
              .timeout(20.seconds)
            _ <- client.send(Channel.Control, shutdownRequest.asRawMessage)
            _ <- readReply(client, Channel.Control, Shutdown.replyType.messageType)
              .timeout(20.seconds)
            _ <- client.close(partial = false, lingerDuration = 2.seconds)
          } yield reply

        val reply = clientRun.unsafeRunSync()(threads.ioRuntime)
        assert(reply.parent_header.exists(_.msg_id == kernelInfoRequest.header.msg_id))

        Await.result(runFuture, 30.seconds)
      }
      finally {
        registrationSocket.close()
        context.close()
        Files.deleteIfExists(registrationFile)
        zeromqThreads.close()
        clientThreads.close()
      }
    }

  }

}
