package almond

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.ConcurrentHashMap

import almond.channels.Channel
import almond.interpreter.messagehandlers.MessageHandler
import almond.logger.LoggerContext
import almond.protocol.custom.Format
import cats.effect.IO
import cats.implicits._
import com.typesafe.config.ConfigFactory
import org.scalafmt.interfaces.{Scalafmt => ScalafmtInterface}

import scala.concurrent.ExecutionContext

final class Scalafmt(
  fmtPool: ExecutionContext,
  queueEc: ExecutionContext,
  logCtx: LoggerContext,
  defaultDialect: String,
  defaultVersion: String = almond.api.Properties.defaultScalafmtVersionOpt.getOrElse("2.7.5")
) {

  private val log = logCtx(getClass)

  private lazy val interface =
    ScalafmtInterface.create(Thread.currentThread().getContextClassLoader)

  private val confFilesMap = new ConcurrentHashMap[String, Path]
  private def confFile(conf: String): Path = {
    val confFile = Files.createTempFile("test-scalafmt", ".conf")
    confFile.toFile.deleteOnExit()
    Files.write(confFile, conf.getBytes(StandardCharsets.UTF_8))
    val previousOrNull = confFilesMap.putIfAbsent(conf, confFile)
    if (previousOrNull == null) confFile
    else {
      Files.delete(confFile)
      previousOrNull
    }
  }

  private val defaultDummyPath = Paths.get("/foo.sc")

  private def defaultConfFile =
    Seq(
      s"version=$defaultVersion",
      s"runner.dialect=$defaultDialect"
    ).map(_ + System.lineSeparator).mkString

  private def format(code: String): String =
    // TODO Get version via build.sbt
    interface.format(confFile(defaultConfFile), defaultDummyPath, code)
      .stripSuffix("\n") // System.lineSeparator() instead?

  def messageHandler: MessageHandler =
    MessageHandler.blocking(Channel.Requests, Format.requestType, queueEc, logCtx) {
      (msg, queue) =>
        log.info(s"format message: $msg")
        val sendResponses = msg.content.cells.toVector.traverse {
          case (key, code) =>
            for {
              formatted <- IO(format(code)).evalOn(fmtPool)
              _ <- msg
                .publish(
                  Format.responseType,
                  Format.Response(key = key, initial_code = code, code = Some(formatted)),
                  ident = Some("scalafmt")
                )
                .enqueueOn(Channel.Publish, queue)
            } yield ()
        }
        val sendReply = {
          val reply = Format.Reply()
          msg
            .reply(Format.replyType, reply)
            .enqueueOn(Channel.Requests, queue)
        }
        for {
          _ <- sendResponses
          _ <- sendReply
        } yield ()
    }
}

object Scalafmt {
  def defaultDialectFor(scalaVersion: String): String =
    if (scalaVersion.startsWith("2.11.")) "scala211"
    else if (scalaVersion.startsWith("2.12.")) "scala212"
    else if (scalaVersion.startsWith("2.13.")) "scala213"
    else "scala3"
}
