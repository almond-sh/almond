package jupyter.scala.nbtest

import argonaut.Json
import jupyter.kernel.interpreter.Interpreter
import jupyter.scala.ScalaInterpreter

import scala.collection.mutable.ListBuffer
import scala.io.Source
import scalaz.{\/-, -\/}

object Notebook {

  def apply(path: String) = {
    fromPath(path) match {
      case Some(cells) =>
        interpret(cells)
      case None =>
        println(s"Ignoring notebook $path (scala version mismatch)")
    }
  }

  def fromPath(path: String, checkScalaVersion: Boolean = true) = {
    import argonaut._, Argonaut._
    import JsonCodecs.notebookDecodeJson

    Source.fromFile(path).mkString.decode[NotebookData] match {
      case -\/(err) =>
        throw new Exception(s"Error while reading notebook: $err")
      case \/-(data) =>
        val versionOkOpt =
          for {
            info <- data.metadata.get("language_info")
            version <- info.cursor.--\("version").flatMap(_.focus.as[String].toOption).map(_.split('.').take(2).mkString("."))
          } yield version == ScalaInterpreter.scalaBinaryVersion

        versionOkOpt match {
          case Some(false) =>
            None
          case _ =>
            Some(data.cells.collect{ case c: CodeCell => c })
        }
    }
  }

  def interpret(cells: Seq[CodeCell]) = {
    val intp = ScalaInterpreter()

    for ((cell, cellIdx) <- cells.zipWithIndex) {
      if (cell.execution_count.isEmpty) {
        println(s"Warning: ignoring non executed cell $cellIdx: $cell")
      } else {
        val outputs0 = new ListBuffer[Output]

        def stdout(s: String): Unit = {
          outputs0.lastOption match {
            case Some(StreamOutput("stdout", prev)) =>
              outputs0.update(outputs0.length - 1, StreamOutput("stdout", prev :+ s))
            case _ =>
              outputs0 += StreamOutput("stdout", Seq(s))
          }
        }
        def stderr(s: String): Unit = {
          outputs0.lastOption match {
            case Some(StreamOutput("stderr", prev)) =>
              outputs0.update(outputs0.length - 1, StreamOutput("stderr", prev :+ s))
            case _ =>
              outputs0 += StreamOutput("stderr", Seq(s))
          }
        }

        val res = intp.interpret(cell.source.mkString("\n"), Some((stdout _, stderr _)), true, None)

        def filterErr(s: String) = s
          .replaceAll("""(?m)\((.*\Q.\Ejava):[0-9]*\)$""", "($1:*)")
          .replaceAll("""(?m)\((.*\Q.\Escala):[0-9]*\)$""", "($1:*)")
          .replaceAll("\u001B\\[[;\\d]*m", "")
          .replaceAll("""(?m)^Main\Q.\Escala:[0-9]*:""", "Main.scala:*:")

        res match {
          case Interpreter.Value(data) =>
            outputs0 += DisplayDataOutput(Map.empty, data.data.map{case (k, v) =>
              k -> (if (v.isEmpty) Json.jEmptyArray else {
                val v0 = v.split('\n')
                Json.jArray((v0.init.map(_ + "\n") :+ v0.last).map(Json.jString(_)).toList)
              })
            }.toMap)
          case Interpreter.Error(error) =>
            outputs0 += ErrorOutput("", "", filterErr(error).split('\n'))
          case other =>
            println(s"Warning: ignoring result $other")
        }

        val ignore =
          cell.metadata.exists {
            case ("test_ignore", b) if b == Json.jBool(true) => true
            case _ => false
          }

        // Ignores stderr exact content, but still requires some stderr output to be on both sides
        // (even if they don't have the same content)
        val ignoreStdErr =
          cell.metadata.exists {
            case ("test_ignore_stderr", b) if b == Json.jBool(true) => true
            case _ => false
          }

        def isStdErr(output: Output) = output match {
          case StreamOutput("stderr", _) => true
          case _ => false
        }

        if (!ignore) {
          val outputs = outputs0.result()
          for (((got, exp), idx) <- outputs.zip(cell.outputs.map{ case ErrorOutput("", "", err) => ErrorOutput("", "", err.map(filterErr)); case c => c }).zipWithIndex) {
            if (exp != got) {
              if (ignoreStdErr && isStdErr(exp) && isStdErr(got))
                println(s"Ignoring different stderr output $idx at cell $cellIdx")
              else {
                val desc = s"Cell $cellIdx $cell: got different $idx output:\nexpected:\n$exp\ngot:\n$got"
                println(desc)
                throw new Exception(desc)
              }
            }
          }

          if (outputs.length != cell.outputs.length) {
            throw new Exception(s"Cell $cellIdx: got wrong output count: ${outputs.length} (expected ${cell.outputs.length})")
          }
        }
      }
    }

    intp.stop()
  }

}
