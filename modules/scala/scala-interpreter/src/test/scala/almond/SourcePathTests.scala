package almond

import almond.internals.SourcePath
import utest._

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{FileSystems, Files, Path}
import java.util.zip.{ZipEntry, ZipOutputStream}

import scala.jdk.CollectionConverters._

object SourcePathTests extends TestSuite {

  private def zip(entries: (String, Array[Byte])*): Array[Byte] = {
    val baos = new java.io.ByteArrayOutputStream
    val zos  = new ZipOutputStream(baos)
    for ((name, content) <- entries) {
      zos.putNextEntry(new ZipEntry(name))
      zos.write(content)
      zos.closeEntry()
    }
    zos.close()
    baos.toByteArray
  }

  val tests = Tests {

    // On Windows, open files can't be deleted, so the copies of the source JARs embedded in
    // standalone launchers have to be closed before they can be deleted
    test("copies of embedded JARs are closed and deleted") {
      val tmpDir = Files.createTempDirectory("almond-source-path-tests")
      try {
        val sourcesJar = zip(
          "foo/Foo.scala" -> "package foo\n\nclass Foo\n".getBytes(StandardCharsets.UTF_8)
        )
        val launcher = tmpDir.resolve("launcher")
        Files.write(
          launcher,
          zip("coursier/bootstrap/launcher/jars/foo-sources.jar" -> sourcesJar)
        )

        val launcherFs = FileSystems.newFileSystem(launcher, null: ClassLoader)
        try {
          val embedded = launcherFs.getPath("coursier/bootstrap/launcher/jars/foo-sources.jar")

          val copy = SourcePath.localFile(embedded)
          assert(copy.getFileSystem == FileSystems.getDefault)
          assert(copy.getFileName.toString == "foo-sources.jar")
          assert(Files.isRegularFile(copy))
          assert(SourcePath.localFile(embedded) == copy)

          // like Scala 3 inspections (SourceDocs)
          val zf = SourcePath.openZipFile(embedded)
          assert(zf.getEntry("foo/Foo.scala") != null)

          // like mtags (Scala 2 inspections), that never closes these
          val copyFs = FileSystems.newFileSystem(
            new URI("jar:" + copy.toUri.toString),
            Map.empty[String, String].asJava
          )
          assert(Files.isRegularFile(copyFs.getPath("foo/Foo.scala")))

          SourcePath.deleteLocalFiles()

          assert(!copyFs.isOpen)
          val zfClosed =
            try { zf.size(); false }
            catch { case _: IllegalStateException => true }
          assert(zfClosed)
          assert(!Files.exists(copy))
          assert(!Files.exists(copy.getParent))
          assert(!Files.exists(copy.getParent.getParent))

          // copies can still be made afterwards
          val newCopy = SourcePath.localFile(embedded)
          assert(Files.isRegularFile(newCopy))
          SourcePath.deleteLocalFiles()
          assert(!Files.exists(newCopy))
        }
        finally launcherFs.close()
      }
      finally deleteRecursively(tmpDir)
    }
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) {
      val stream = Files.list(path)
      try stream.iterator().asScala.toVector.foreach(deleteRecursively)
      finally stream.close()
    }
    Files.deleteIfExists(path)
  }
}
