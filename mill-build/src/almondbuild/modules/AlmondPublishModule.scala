package almondbuild.modules

import mill.*
import mill.api.*
import mill.javalib.publish.*
import mill.scalalib.*

import java.io.ByteArrayOutputStream
import java.util.zip.ZipOutputStream

trait AlmondPublishModule extends PublishModule with ScalaModule with AlmondJvmTarget {
  import mill.scalalib.publish._
  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "sh.almond",
    url = "https://github.com/almond-sh/almond",
    licenses = Seq(License.`BSD-3-Clause`),
    versionControl = VersionControl.github("almond-sh", "almond"),
    developers = Seq(
      Developer("alexarchambault", "Alex Archambault", "https://github.com/alexarchambault")
    )
  )
  def publishVersion = Task(AlmondPublishModule.buildVersion())

  // We don't publish any documentation, publish empty doc JARs
  def docJar: T[PathRef] = Task {
    AlmondPublishModule.emptyZip()
  }
}

object AlmondPublishModule extends ExternalModule {

  def latestTaggedVersion(): String =
    os.proc("git", "describe", "--abbrev=0", "--tags", "--match", "v*")
      .call().out
      .trim()

  def computeBuildVersion(): String = {
    val gitHead       = os.proc("git", "rev-parse", "HEAD").call().out.trim()
    val maybeExactTag = {
      val res = os.proc("git", "describe", "--exact-match", "--tags", "--always", gitHead)
        .call(stderr = os.Pipe, check = false)
      if (res.exitCode == 0)
        Some(res.out.trim().stripPrefix("v"))
      else
        None
    }
    maybeExactTag.getOrElse {
      val latestTaggedVersion0      = latestTaggedVersion()
      val commitsSinceTaggedVersion =
        os.proc("git", "rev-list", gitHead, "--not", latestTaggedVersion0, "--count")
          .call().out.trim()
          .toInt
      val gitHash = os.proc("git", "rev-parse", "--short", "HEAD").call().out.trim()
      s"${latestTaggedVersion0.stripPrefix("v")}-$commitsSinceTaggedVersion-$gitHash-SNAPSHOT"
    }
  }
  def buildVersion: T[String] = Task.Input {
    computeBuildVersion()
  }

  /** An empty ZIP file, used as doc JAR by all published modules */
  def emptyZip: T[PathRef] = Task {
    val baos = new ByteArrayOutputStream
    val zos  = new ZipOutputStream(baos)
    zos.finish()
    zos.close()
    val dest = Task.dest / "empty.zip"
    os.write(dest, baos.toByteArray)
    PathRef(dest)
  }

  lazy val millDiscover: Discover = Discover[this.type]
}
