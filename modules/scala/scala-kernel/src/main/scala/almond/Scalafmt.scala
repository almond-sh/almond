package almond

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.ConcurrentHashMap

import almond.channels.Channel
import almond.interpreter.KernelSession
import almond.interpreter.messagehandlers.MessageHandler
import almond.logger.LoggerContext
import almond.protocol.RawJson
import almond.protocol.custom.Format
import cats.effect.IO
import cats.implicits._
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}
import org.scalafmt.interfaces.{Scalafmt => ScalafmtInterface, ScalafmtSession}

import scala.concurrent.ExecutionContext

final class Scalafmt(
  fmtPool: ExecutionContext,
  queueEc: ExecutionContext,
  logCtx: LoggerContext,
  kernelSession: KernelSession,
  defaultDialect: String,
  defaultVersion: String = almond.api.Properties.defaultScalafmtVersion().getOrElse("3.7.15")
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

  private lazy val defaultConf = ConfigFactory.parseString(
    Seq(
      s"version=$defaultVersion",
      s"runner.dialect=$defaultDialect"
    ).mkString(System.lineSeparator)
  )

  /** Scalafmt configuration, from the (JSON) one sent by the front-end, and our defaults */
  private def conf(userConf: RawJson): String = {
    val json = userConf.toString.trim
    val userConfig =
      if (json.isEmpty || json == "null") ConfigFactory.empty()
      else {
        val root = ConfigFactory.parseString(json).root
        // Treat keys as paths, so that `{"runner.dialect": "scala213"}` works like in .scalafmt.conf
        root.keySet.toArray(Array.empty[String]).foldLeft(ConfigFactory.empty()) {
          (acc, key) =>
            ConfigFactory.empty().withValue(key, root.get(key)).withFallback(acc)
        }
      }
    userConfig
      .withFallback(defaultConf)
      .root
      .render(ConfigRenderOptions.concise())
  }

  private def session(conf: String): ScalafmtSession =
    interface.createSession(confFile(conf))

  private def errorMessage(e: Throwable): String =
    Option(e.getMessage).getOrElse(e.toString)

  private def usesCrlf(code: String): Boolean = {
    var hasLines = false
    val onlyCrlf = code
      .linesWithSeparators
      .forall { line =>
        hasLines = true
        line.endsWith("\r\n")
      }
    hasLines && onlyCrlf
  }

  private def format(session: ScalafmtSession, code: String): Either[String, String] = {
    val result = session.formatOrError(defaultDummyPath, code)
    if (result.exception == null) Right(fixLineEndings(code, result.value))
    else
      // result.exception is a generic "Format error" wrapping the actual (parsing, …) error
      Left(errorMessage(Option(result.exception.getCause).getOrElse(result.exception)))
  }

  private def fixLineEndings(code: String, rawResult: String): String =
    // Seems scalafmt discards crlf line endings
    if (usesCrlf(code))
      rawResult
        .linesWithSeparators
        .flatMap { line =>
          if (line.endsWith("\n") && !line.endsWith("\r\n"))
            Iterator(line.stripSuffix("\n"), "\r\n")
          else
            Iterator(line)
        }
        .mkString
        .stripSuffix("\r\n")
    else
      rawResult.stripSuffix("\n")

  def messageHandler: MessageHandler =
    MessageHandler.blocking(Channel.Requests, Format.requestType, kernelSession, queueEc, logCtx) {
      (msg, queue) =>
        log.info(s"format message: $msg")
        def response(key: String, code: String, formatted: Either[String, String]) =
          msg
            .publish(
              kernelSession,
              Format.responseType,
              Format.Response(
                key = key,
                initial_code = code,
                code = formatted.toOption,
                error = formatted.left.toOption
              ),
              ident = Some("scalafmt")
            )
            .enqueueOn(Channel.Publish, queue)
        val sendResponses =
          for {
            sessionOrError <- IO(session(conf(msg.content.conf))).attempt.evalOn(fmtPool)
            _ <- msg.content.cells.toVector.traverse {
              case (key, code) =>
                for {
                  formatted <- sessionOrError match {
                    case Left(e) => IO.pure(Left(errorMessage(e)))
                    case Right(session0) =>
                      IO(format(
                        session0,
                        code
                      )).attempt.map(_.left.map(errorMessage).flatMap(identity))
                        .evalOn(fmtPool)
                  }
                  _ <-
                    IO(formatted.left.foreach(err => log.info(s"Error formatting cell $key: $err")))
                  _ <- response(key, code, formatted)
                } yield ()
            }
          } yield ()
        val sendReply = {
          val reply = Format.Reply()
          msg
            .reply(kernelSession, Format.replyType, reply)
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
