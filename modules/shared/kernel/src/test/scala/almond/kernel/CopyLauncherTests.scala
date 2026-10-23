package almond.kernel

import almond.kernel.install.Install
import almond.protocol.KernelSpec
import utest._

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

import scala.jdk.CollectionConverters._

object CopyLauncherTests extends TestSuite {

  val tests = Tests {

    test("java -jar") {
      val cmd         = List("java", "-jar", "foo", "arg1", "arg2")
      val res         = Install.launcherPos(cmd)
      val expectedRes = Some(("foo", 2))
      assert(res == expectedRes)
    }

    test("java -jar with options") {
      val cmd         = List("java", "-Xmx15T", "-Dmode=lightweight", "-jar", "foo", "arg1", "arg2")
      val res         = Install.launcherPos(cmd)
      val expectedRes = Some(("foo", 4))
      assert(res == expectedRes)
    }

    test("java -cp") {
      val sep         = File.pathSeparator
      val cmd         = List("java", "-cp", Seq("foo", "other").mkString(sep), "arg1", "arg2")
      val res         = Install.launcherPos(cmd)
      val expectedRes = Some(("other", 2))
      assert(res == expectedRes)
    }

    test("no java: nope") {
      val cmd         = List("zava", "-cp", "foo:other", "arg1", "arg2")
      val res         = Install.launcherPos(cmd)
      val expectedRes = None
      assert(res == expectedRes)
    }

    test("main JAR path with special characters") {
      if (!scala.util.Properties.isWin)
        for (path <- Seq("/foo/dir with space/a.jar", "/foo/a%20b#c?d/a.jar")) {
          val res = Install.mainJarPath(path)
          assert(res == path)
        }
    }

    test("copied launcher doesn't keep source attributes") {
      if (!scala.util.Properties.isWin) {
        val tmpDir = Files.createTempDirectory("almond-copy-launcher-tests")
        try {
          val source = tmpDir.resolve("source/launcher")
          Files.createDirectories(source.getParent)
          Files.write(source, Array[Byte](1, 2, 3))
          Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rw-------"))

          val spec = KernelSpec(
            argv = List("java", "-jar", source.toString, "--connection-file", "{connection_file}"),
            display_name = "Test",
            language = "scala"
          )
          val dir = Install.installIn(
            "test",
            spec,
            Left(tmpDir.resolve("kernels")),
            copyLauncher = true
          )

          val dest  = dir.resolve("launcher.jar")
          val perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(dest))
          assert(perms == "rwxr-xr-x")
          assert(Files.readAllBytes(dest).toSeq == Seq[Byte](1, 2, 3))
        }
        finally
          Files.walk(tmpDir)
            .iterator()
            .asScala
            .toVector
            .reverse
            .foreach(Files.delete)
      }
    }

  }

}
