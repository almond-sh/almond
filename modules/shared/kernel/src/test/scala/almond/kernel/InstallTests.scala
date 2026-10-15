package almond.kernel

import almond.kernel.install.{Install, InstallException}
import almond.kernel.util.OS
import almond.protocol.{ActualMap, KernelSpec}
import utest._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}

import scala.jdk.CollectionConverters._

object InstallTests extends TestSuite {

  private def withTmpDir[T](f: Path => T): T = {
    val dir = Files.createTempDirectory("almond-install-tests")
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

  private val spec = KernelSpec(
    argv = List("java", "-jar", "launcher.jar"),
    display_name = "Test",
    language = "test",
    env = ActualMap(Map.empty)
  )

  private def install(kernelsDir: Path, id: String): Path =
    Install.installIn(id, spec, Left(kernelsDir), force = true)

  val tests = Tests {

    test("reject invalid ids") {
      withTmpDir { tmpDir =>
        val kernelsDir = tmpDir.resolve("kernels")
        val other      = write(kernelsDir.resolve("other/kernel.json"), "{}")
        val outside    = write(tmpDir.resolve("outside/file"), "a")
        val invalidIds = Seq(
          "",
          ".",
          "..",
          "../outside",
          "a/b",
          "a\\b",
          outside.getParent.toString,
          "with space"
        )
        for (id <- invalidIds) {
          val res = scala.util.Try(install(kernelsDir, id))
          assert(res.failed.toOption.exists(_.isInstanceOf[InstallException.InvalidKernelId]))
        }
        assert(Files.exists(other))
        assert(Files.exists(outside))
      }
    }

    test("accept valid ids") {
      withTmpDir { tmpDir =>
        val kernelsDir = tmpDir.resolve("kernels")
        for (id <- Seq("scala", "scala213", "custom-id", "Scala_2.13", ".hidden")) {
          val dir = install(kernelsDir, id)
          assert(dir == kernelsDir.resolve(id))
          assert(Files.isRegularFile(dir.resolve("kernel.json")))
        }
      }
    }

    test("force doesn't follow links") {
      if (OS.current != OS.Windows)
        withTmpDir { tmpDir =>
          val kernelsDir = tmpDir.resolve("kernels")
          val target     = tmpDir.resolve("target")
          val targetFile = write(target.resolve("file"), "a")

          // link in an existing kernel directory
          val kernelDir = kernelsDir.resolve("test")
          Files.createDirectories(kernelDir)
          Files.createSymbolicLink(kernelDir.resolve("link"), target)
          install(kernelsDir, "test")
          assert(!Files.exists(kernelDir.resolve("link"), LinkOption.NOFOLLOW_LINKS))
          assert(Files.exists(targetFile))

          // kernel directory itself being a link
          val linkedKernelDir = kernelsDir.resolve("linked")
          Files.createSymbolicLink(linkedKernelDir, target)
          install(kernelsDir, "linked")
          assert(!Files.isSymbolicLink(linkedKernelDir))
          assert(Files.isRegularFile(linkedKernelDir.resolve("kernel.json")))
          assert(Files.exists(targetFile))
          assert(!Files.exists(target.resolve("kernel.json")))
        }
    }

  }

}
