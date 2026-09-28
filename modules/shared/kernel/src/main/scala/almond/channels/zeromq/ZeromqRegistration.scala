package almond.channels.zeromq

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import almond.channels.ConnectionParameters
import almond.logger.LoggerContext
import almond.protocol.Registration
import cats.effect.IO
import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray
import org.zeromq.{SocketType, ZMQ}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Kernel side of the kernel startup handshake (protocol 5.6)
  *
  * Once the kernel channels are bound, sends their ports to the registration socket, and waits for
  * its acknowledgment.
  */
object ZeromqRegistration {

  private val delimiter = "<IDS|MSG>".getBytes(UTF_8)

  private def hmac(registration: Registration, content: Array[Byte]): String =
    if (registration.key.value.isEmpty) ""
    else {
      val algorithm = registration.signature_scheme.getOrElse("hmac-sha256").filter(_ != '-')
      val mac       = Mac.getInstance(algorithm)
      mac.init(new SecretKeySpec(registration.key.value.getBytes(UTF_8), algorithm))
      mac.doFinal(content).map(b => f"$b%02x").mkString
    }

  def sendConnectionInfo(
    context: ZMQ.Context,
    registration: Registration,
    params: ConnectionParameters,
    logCtx: LoggerContext,
    timeout: FiniteDuration = 30.seconds
  ): IO[Unit] = IO.blocking {

    val log = logCtx(getClass)

    val content = writeToArray(Registration.ConnectionInfo(registration.kernel_id, params))
    val socket  = context.socket(SocketType.DEALER)

    try {
      socket.setLinger(1000)
      socket.setReceiveTimeOut(timeout.toMillis.toInt)
      log.debug(s"Sending connection info to ${registration.registrationUri}")
      socket.connect(registration.registrationUri)

      socket.sendMore(delimiter)
      socket.sendMore(hmac(registration, content).getBytes(UTF_8))
      socket.send(content)

      // Reading all the frames of the acknowledgment
      val ack = {
        val first = socket.recv()
        if (first == null)
          throw new Exception(
            s"No acknowledgment received from ${registration.registrationUri} after $timeout"
          )
        val b = Vector.newBuilder[Array[Byte]]
        b += first
        while (socket.hasReceiveMore)
          b += socket.recv()
        b.result()
      }

      val afterDelimiter = ack.dropWhile(!java.util.Arrays.equals(_, delimiter)).drop(1)
      afterDelimiter match {
        case Seq(signature, ackContent, _*) =>
          val expected = hmac(registration, ackContent)
          if (!MessageDigest.isEqual(expected.getBytes(UTF_8), signature))
            throw new Exception(
              s"Invalid signature in acknowledgment from ${registration.registrationUri}"
            )
          log.debug(
            s"Got acknowledgment from ${registration.registrationUri}: " +
              new String(ackContent, UTF_8)
          )
        case _ =>
          throw new Exception(
            s"Malformed acknowledgment from ${registration.registrationUri} " +
              s"(${ack.length} frame(s))"
          )
      }
    }
    finally socket.close()
  }

}
