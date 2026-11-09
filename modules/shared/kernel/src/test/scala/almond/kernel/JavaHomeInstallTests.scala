package almond.kernel

import almond.kernel.install.{Install, Options}
import almond.protocol.KernelSpec
import com.github.plokhotnyuk.jsoniter_scala.core._
import utest._

import java.io.FileOutputStream
import java.nio.file.{Files, Path, Paths}
import java.util.jar.{Attributes, JarOutputStream, Manifest}

import scala.jdk.CollectionConverters._

object JavaHomeInstallTests extends TestSuite {

  private def withTmpDir[T](f: Path => T): T = {
    val dir = Files.createTempDirectory("almond-java-home-install-tests")
    try f(dir)
    finally
      Files.walk(dir)
        .iterator()
        .asScala
        .toVector
        .reverse
        .foreach(Files.deleteIfExists)
  }

  // Makes the current app look like it was started by a coursier launcher
  private def withCoursierLauncher[T](tmpDir: Path)(f: => T): T = {
    val launcher = tmpDir.resolve("launcher.jar")
    val manifest = new Manifest
    manifest.getMainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
    manifest.getMainAttributes.put(Attributes.Name.MAIN_CLASS, "foo.Main")
    new JarOutputStream(new FileOutputStream(launcher.toFile), manifest).close()

    val props = Seq(
      "coursier.mainJar"    -> launcher.toString,
      "coursier.main.arg-0" -> "--install",
      "coursier.main.arg-1" -> "--log",
      "coursier.main.arg-2" -> "info"
    )
    for ((k, v) <- props) sys.props(k) = v
    try f
    finally
      for ((k, _) <- props) sys.props.remove(k)
  }

  // passing --env values like the almond launcher does
  private def install(tmpDir: Path, options: Options): KernelSpec = {
    val res = Install.installOrError(
      defaultId = "test",
      defaultDisplayName = "Test",
      language = "test",
      options = options.copy(
        jupyterPath = Some(tmpDir.resolve("kernels").toString),
        jupyterCommand = Some("")
      ),
      defaultLogoOpt = None,
      connectionFileArgs = Install.defaultConnectionFileArgs,
      interruptMode = None,
      env = options.envMap(),
      extraStartupClassPath = Nil
    )
    val dir = res.fold(e => throw e, identity)
    readFromArray(Files.readAllBytes(dir.resolve("kernel.json")))(KernelSpec.codec)
  }

  val tests = Tests {

    test("current JVM in spec") {
      withTmpDir { tmpDir =>
        val spec = withCoursierLauncher(tmpDir)(install(tmpDir, Options()))
        val java = spec.argv.head
        assert(Paths.get(java).isAbsolute)
        assert(Files.isSameFile(Paths.get(java), Paths.get(Install.currentJavaCommand())))
        val javaHome = spec.env.map.get("JAVA_HOME")
        assert(javaHome == Some(Install.currentJavaHome().toString))
        assert(Paths.get(java).startsWith(Paths.get(javaHome.get)))
        assert(spec.argv.contains("foo.Main"))
        assert(spec.argv.contains("--log"))
        assert(!spec.argv.contains("--install"))
      }
    }

    test("JAVA_HOME from --env wins") {
      withTmpDir { tmpDir =>
        val spec = withCoursierLauncher(tmpDir) {
          install(tmpDir, Options(env = List("JAVA_HOME=/other/jvm")))
        }
        assert(spec.env.map.get("JAVA_HOME") == Some("/other/jvm"))
      }
    }

    test("no JAVA_HOME for custom commands") {
      withTmpDir { tmpDir =>
        val spec = install(tmpDir, Options(arg = List("java", "-jar", "kernel.jar")))
        assert(spec.argv.head == "java")
        assert(!spec.env.map.contains("JAVA_HOME"))
      }
    }

  }

}
