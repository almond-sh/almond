package almond.kernel

import almond.kernel.install.{Install, JupyterDirectories}
import almond.kernel.util.OS
import utest._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions

import scala.jdk.CollectionConverters._

object JupyterDirectoriesTests extends TestSuite {

  private def withTmpDir[T](f: Path => T): T = {
    val dir = Files.createTempDirectory("almond-jupyter-dirs-tests")
    try f(dir)
    finally
      Files.walk(dir)
        .iterator()
        .asScala
        .toVector
        .reverse
        .foreach(Files.deleteIfExists)
  }

  private def write(path: Path, content: String): Path = {
    Files.createDirectories(path.getParent)
    Files.write(path, content.getBytes(StandardCharsets.UTF_8))
  }

  private def fakeJupyter(dir: Path, script: String): Path = {
    val path = write(dir.resolve("bin/jupyter"), "#!/usr/bin/env sh" + "\n" + script)
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
    path
  }

  val tests = Tests {

    test("get") {
      test("from jupyter") {
        if (OS.current != OS.Windows)
          withTmpDir { tmpDir =>
            val jupyter = fakeJupyter(
              tmpDir,
              """case "$1" in
                |  --data-dir) echo "/home/user/.local/share/jupyter" ;;
                |  --paths) echo '{"runtime": ["/run"], "config": [], "data": ["/env/share/jupyter", "/home/user/.local/share/jupyter", "/usr/local/share/jupyter"]}' ;;
                |  *) exit 1 ;;
                |esac
                |""".stripMargin
            )
            val res = JupyterDirectories.get(jupyter.toString)
            val expected = Right(
              JupyterDirectories(
                jupyter,
                java.nio.file.Paths.get("/home/user/.local/share/jupyter"),
                Seq(
                  java.nio.file.Paths.get("/env/share/jupyter"),
                  java.nio.file.Paths.get("/home/user/.local/share/jupyter"),
                  java.nio.file.Paths.get("/usr/local/share/jupyter")
                )
              )
            )
            assert(res == expected)
          }
      }

      test("jupyter failing") {
        if (OS.current != OS.Windows)
          withTmpDir { tmpDir =>
            val jupyter = fakeJupyter(tmpDir, "echo 'Something went wrong' 1>&2; exit 2\n")
            val res     = JupyterDirectories.get(jupyter.toString)
            assert(res.isLeft)
            val err = res.left.toOption.getOrElse("")
            assert(err.contains("exited with code 2"))
            assert(err.contains("Something went wrong"))
          }
      }

      test("malformed output") {
        if (OS.current != OS.Windows)
          withTmpDir { tmpDir =>
            val jupyter = fakeJupyter(tmpDir, "echo 'not JSON'\n")
            val res     = JupyterDirectories.get(jupyter.toString)
            assert(res.isLeft)
            assert(res.left.toOption.exists(_.contains("Malformed output")))
          }
      }

      test("jupyter not found") {
        withTmpDir { tmpDir =>
          val res = JupyterDirectories.get(tmpDir.resolve("nope/jupyter").toString)
          assert(res.isLeft)
          assert(res.left.toOption.exists(_.contains("not found")))
        }
      }
    }

    test("checkVisibility") {
      def dirs(base: Path) = JupyterDirectories(
        base.resolve("bin/jupyter"),
        base.resolve("user"),
        Seq(base.resolve("env"), base.resolve("user"), base.resolve("system"))
      )

      test("visible") {
        withTmpDir { tmpDir =>
          val res = Install.checkVisibility(dirs(tmpDir), tmpDir.resolve("user/kernels/scala"))
          assert(res.isEmpty)
        }
      }

      test("not visible") {
        withTmpDir { tmpDir =>
          val res = Install.checkVisibility(dirs(tmpDir), tmpDir.resolve("other/kernels/scala"))
          assert(res.exists(_.contains("doesn't look for kernels in")))
          assert(res.exists(_.contains(tmpDir.resolve("system/kernels").toString)))
        }
      }

      test("missing kernels in path") {
        withTmpDir { tmpDir =>
          val res = Install.checkVisibility(dirs(tmpDir), tmpDir.resolve("system/scala"))
          assert(
            res.exists(_.contains(
              s"Did you mean to pass --jupyter-path ${tmpDir.resolve("system/kernels")}?"
            ))
          )
        }
      }

      test("shadowed") {
        withTmpDir { tmpDir =>
          write(tmpDir.resolve("env/kernels/scala/kernel.json"), "{}")
          val res = Install.checkVisibility(dirs(tmpDir), tmpDir.resolve("user/kernels/scala"))
          assert(res.exists(_.contains("another kernel with id scala")))
          assert(res.exists(_.contains(tmpDir.resolve("env/kernels/scala").toString)))
        }
      }

      test("not shadowed by lower priority kernels") {
        withTmpDir { tmpDir =>
          write(tmpDir.resolve("system/kernels/scala/kernel.json"), "{}")
          val res = Install.checkVisibility(dirs(tmpDir), tmpDir.resolve("user/kernels/scala"))
          assert(res.isEmpty)
        }
      }
    }

  }

}
