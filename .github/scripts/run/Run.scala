//> using scala "2.13"
//> using dep "com.lihaoyi::os-lib:0.9.1"
//> using dep "com.lihaoyi::upickle:3.1.0"
//> using dep "io.get-coursier::coursier-jvm:2.1.4"
//> using dep "org.graalvm.nativeimage:svm:22.3.1"

import coursier.jvm.Execve
import scala.build.internal.Chdir
import upickle.default._

object Run {
  def main(args: Array[String]): Unit = {
    val path = args match {
      case Array(value) => os.Path(value, os.pwd)
      case _            => sys.error("Usage: run …path…")
    }
    val b   = os.read.bytes(path)
    val map = read[Map[String, Seq[String]]](b)

    val args0 = map("args")
    val cwd   = map("cwd").head
    val env   = sys.env.toVector.sorted.map { case (k, v) => s"$k=$v" } ++ map("env")

    val cwd0 = os.Path(cwd, os.pwd)

    if (Execve.available()) {
      if (cwd0 != os.pwd) {
        assert(Chdir.available(), "chdir system call not available")
        Chdir.chdir(cwd0.toString)
      }

      Execve.execve(
        args0.head,
        (os.Path(args0.head, os.pwd).last +: args0.tail).toArray,
        env.toArray
      )
    }
    else {
      System.err.println("exec system call not available")
      val res = os.proc(args0).call(
        cwd = cwd0,
        env = env
          .map(_.split("=", 2))
          .map {
            case Array(k, v) => k -> v
            case Array(k)    => k -> ""
          }
          .toMap,
        stdin = os.Inherit,
        stdout = os.Inherit,
        check = false
      )
      if (res.exitCode != 0)
        sys.exit(res.exitCode)
    }
  }
}
