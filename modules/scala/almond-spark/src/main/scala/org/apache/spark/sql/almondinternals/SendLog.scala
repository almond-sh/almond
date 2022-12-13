package org.apache.spark.sql.almondinternals

import java.io.{BufferedReader, File, FileReader}
import java.nio.file.{Files, StandardOpenOption}
import java.util.UUID

import almond.interpreter.api.{CommHandler, CommTarget, OutputHandler}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.util.control.NonFatal
import java.nio.charset.StandardCharsets

final class SendLog(
  f: File,
  commHandler: CommHandler,
  threadName: String = "send-log",
  prefix: Option[String] = None,
  fileName: String = null,
  lineBufferSize: Int = 10,
  delay: FiniteDuration = 2.seconds,
  initialBackOffDelay: FiniteDuration = 5.seconds,
  maxBackOffDelay: FiniteDuration = 1.minute,
  backOffFactor: Double = 2.0,
  replaceHome: Boolean = true
) {

  assert(backOffFactor >= 1.0)

  private val commTarget = UUID.randomUUID().toString
  private val commId = UUID.randomUUID().toString

  @volatile private var gotAck = false
  @volatile private var keepReading = true

  commHandler.registerCommTarget(
    commTarget + "-ack",
    CommTarget { (_, _) =>
      gotAck = true
    }
  )

  private def withExponentialBackOff[T](f: => T): T = {

    def helper(delay: Duration): T = {
      val res =
        try Some(f)
        catch {
          case NonFatal(_) =>
            // FIXME Log the exception that somewhere?
            None
        }

      res match {
        case Some(t) => t
        case None =>
          assert(delay.isFinite)
          Thread.sleep(delay.toMillis)
          helper((backOffFactor * delay).min(maxBackOffDelay))
      }
    }

    helper(initialBackOffDelay)
  }

  val thread: Thread =
    new Thread(threadName) {
      override def run(): Unit = {

        import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray

        val fileName0 = Option(fileName).getOrElse {
          val p = f.getAbsolutePath
          val home = sys.props("user.home")
          if (replaceHome && p.startsWith(home))
            "~" + p.stripPrefix(home)
          else
            p
        }

        var r: FileReader = null

        val msg = writeToArray(SendLog.Open(fileName0, prefix))

        try {
          withExponentialBackOff {
            commHandler.commOpen(
              targetName = commTarget,
              id = commId,
              data = msg,
              metadata = SendLog.emptyObj
            )
          }

          // https://stackoverflow.com/questions/557844/java-io-implementation-of-unix-linux-tail-f/558262#558262

          while (!gotAck)
            Thread.sleep(delay.toMillis)

          r = new FileReader(f)
          val br = new BufferedReader(r)

          var lines = new ListBuffer[String]
          while (keepReading) {
            val line = br.readLine()
            if (line == null)
              // wait until there is more in the file
              Thread.sleep(delay.toMillis)
            else {
              lines += line

              while (keepReading && lines.length <= lineBufferSize && br.ready())
                lines += br.readLine()

              val l = lines.result()
              val l0 =
                if (replaceHome) {
                  val home = sys.props("user.home")
                  l.map(_.replace(home, "~"))
                } else
                  l
              // Beware: nospaces has a bad complexity (super slow when generating a large output)
              val res = writeToArray(SendLog.Data(l0))
              lines.clear()

              withExponentialBackOff {
                commHandler.commMessage(commId, res, SendLog.emptyObj)
              }
            }
          }
        } catch {
          case _: InterruptedException =>
            // normal exit
        } finally {
          if (r != null)
            r.close()
          // no re-attempt here…
          commHandler.commClose(commId, SendLog.emptyObj, SendLog.emptyObj)
        }
      }
    }

  def init()(implicit outputHandler: OutputHandler): Unit =
    outputHandler.js(SendLog.jsInit(commTarget))

  def start(): Unit = {
    assert(keepReading, "Already stopped")
    if (!thread.isAlive)
      synchronized {
        if (!thread.isAlive)
          thread.start()
      }
  }

  def stop(): Unit = {
    keepReading = false
    thread.interrupt()
  }

}

object SendLog {

  private final case class Open(file_name: String, prefix: Option[String])

  private object Open {
    import com.github.plokhotnyuk.jsoniter_scala.core._
    import com.github.plokhotnyuk.jsoniter_scala.macros._
    implicit val codec: JsonValueCodec[Open] =
      JsonCodecMaker.make
  }

  private final case class Data(data: Seq[String])

  private object Data {
    import com.github.plokhotnyuk.jsoniter_scala.core._
    import com.github.plokhotnyuk.jsoniter_scala.macros._
    implicit val codec: JsonValueCodec[Data] =
      JsonCodecMaker.make
  }

  private val emptyObj = "{}".getBytes(StandardCharsets.UTF_8)

  def start(
    f: File,
    prefix: String = null
  )(implicit
    commHandler: CommHandler,
    outputHandler: OutputHandler
  ): SendLog = {

    // It seems the file must exist for the reader above to get content appended to it.
    if (!f.exists())
      Files.write(f.toPath, Array.emptyByteArray, StandardOpenOption.CREATE)

    val sendLog = new SendLog(f, commHandler, prefix = Option(prefix))
    sendLog.init()
    sendLog.start()
    sendLog
  }

  private def jsInit(target: String): String =
    s"""
      Jupyter.notebook.kernel.comm_manager.register_target(
        "$target",
        function (comm, data) {
          console.log("$$ tail -F " + data.content.data.file_name);
          var prefix = data.content.data.prefix || "";

          var ackComm = Jupyter.notebook.kernel.comm_manager.new_comm("$target-ack", "{}");
          ackComm.open();
          ackComm.send({});

          comm.on_msg(
            function (msg) {
              var a = msg.content.data.data;
              var len = a.length;
              for (var i = 0; i < len; i++) {
                console.log(prefix + a[i]);
              }
            }
          );
        }
      );
    """


}
