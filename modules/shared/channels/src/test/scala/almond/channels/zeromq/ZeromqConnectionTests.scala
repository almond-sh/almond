package almond.channels.zeromq

import java.nio.charset.StandardCharsets

import almond.channels.{Channel, ConnectionParameters, Message}
import almond.logger.LoggerContext
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import utest._

import scala.concurrent.duration.DurationInt

object ZeromqConnectionTests extends TestSuite {

  val tests = Tests {

    test("simple") {

      val logCtx        = LoggerContext.nop
      val params        = ConnectionParameters.randomLocal()
      val kernelThreads = ZeromqThreads.create("test-kernel")
      val serverThreads = ZeromqThreads.create("test-server")

      val msg0 = Message(
        Nil,
        "header".getBytes(StandardCharsets.UTF_8),
        "parent_header".getBytes(StandardCharsets.UTF_8),
        "metadata".getBytes(StandardCharsets.UTF_8),
        "content".getBytes(StandardCharsets.UTF_8)
      )

      val t =
        for {
          kernel <- params.channels(bind = true, kernelThreads, None, logCtx)
          server <- params.channels(bind = false, serverThreads, None, logCtx)
          _      <- kernel.open
          _      <- server.open
          _      <- server.send(Channel.Requests, msg0)
          resp <- kernel.tryRead(Seq(Channel.Requests), 1.second).flatMap {
            case Some(r) => IO.pure(r)
            case None    => IO.raiseError(new Exception("no message"))
          }
          _ = assert(resp._1 == Channel.Requests)
          _ = assert(resp._2.copy(idents = Nil) == msg0)
          // TODO Enforce this is run via bracketing
          _ <- kernel.close(partial = false, lingerDuration = 2.seconds)
          _ <- server.close(partial = false, lingerDuration = 2.seconds)
        } yield ()

      t.unsafeRunSync()(IORuntime.global)
    }

  }

}
