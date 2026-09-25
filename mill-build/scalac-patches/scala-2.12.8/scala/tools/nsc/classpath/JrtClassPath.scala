/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

// A copy of the JrtClassPath object and class of scalac 2.12.8, from
// https://github.com/scala/scala/blob/v2.12.8/src/compiler/scala/tools/nsc/classpath/DirectoryClassPath.scala
// with one fix (see the "Patched:" comment below). The almond build puts the classes compiled
// from this file ahead of scala-compiler on the class path of scalac 2.12.8, so that they shadow
// its own - see almondbuild.Scala212_8JrtPatch.

package scala.tools.nsc.classpath

import java.net.URL

import scala.reflect.io.{AbstractFile, PlainNioFile}
import scala.tools.nsc.util.ClassPath
import scala.collection.JavaConverters._
import scala.reflect.internal.JDK9Reflectors
import scala.tools.nsc.classpath.PackageNameUtils.{packageContains, separatePkgAndClassNames}

object JrtClassPath {
  import java.nio.file._, java.net.URI
  def apply(release: Option[String]): Option[ClassPath] = {
    import scala.util.Properties._
    if (!isJavaAtLeast("9")) None
    else {
      // TODO escalate errors once we're sure they are fatal
      // I'm hesitant to do this immediately, because -release will still work for multi-release JARs
      // even if we're running on a JRE or a non OpenJDK JDK where ct.sym is unavailable.
      //
      // Longer term we'd like an official API for this in the JDK
      // Discussion: http://mail.openjdk.java.net/pipermail/compiler-dev/2018-March/thread.html#11738

      val currentMajorVersion: Int = JDK9Reflectors.runtimeVersionMajor(JDK9Reflectors.runtimeVersion()).intValue()
      release match {
        case Some(v) if v.toInt < currentMajorVersion =>
          try {
            val ctSym = Paths.get(javaHome).resolve("lib").resolve("ct.sym")
            if (Files.notExists(ctSym)) None
            else Some(new CtSymClassPath(ctSym, v.toInt))
          } catch {
            case _: Throwable => None
          }
        case _ =>
          try {
            val fs = FileSystems.getFileSystem(URI.create("jrt:/"))
            Some(new JrtClassPath(fs))
          } catch {
            case _: ProviderNotFoundException | _: FileSystemNotFoundException => None
          }
      }
    }
  }
}

/**
  * Implementation `ClassPath` based on the JDK 9 encapsulated runtime modules (JEP-220)
  *
  * https://bugs.openjdk.java.net/browse/JDK-8066492 is the most up to date reference
  * for the structure of the jrt:// filesystem.
  *
  * The implementation assumes that no classes exist in the empty package.
  */
final class JrtClassPath(fs: java.nio.file.FileSystem) extends ClassPath with NoSourcePaths {
  import java.nio.file.Path, java.nio.file._
  type F = Path
  private val dir: Path = fs.getPath("/packages")

  // e.g. "java.lang" -> Seq("/modules/java.base")
  private val packageToModuleBases: Map[String, Seq[Path]] = {
    val ps = Files.newDirectoryStream(dir).iterator().asScala
    def lookup(pack: Path): Seq[Path] = {
      Files.list(pack).iterator().asScala.map(l => if (Files.isSymbolicLink(l)) Files.readSymbolicLink(l) else l).toList
    }
    ps.map(p => (p.toString.stripPrefix("/packages/"), lookup(p))).toMap
  }

  /** Empty string represents root package */
  override private[nsc] def hasPackage(pkg: String) = packageToModuleBases.contains(pkg)
  override private[nsc] def packages(inPackage: String): Seq[PackageEntry] = {
    packageToModuleBases.keysIterator.filter(pack => packageContains(inPackage, pack)).map(PackageEntryImpl(_)).toVector
  }
  private[nsc] def classes(inPackage: String): Seq[ClassFileEntry] = {
    if (inPackage == "") Nil
    else {
      packageToModuleBases.getOrElse(inPackage, Nil).flatMap(x =>
        Files.list(x.resolve(inPackage.replace('.', '/'))).iterator().asScala.filter(_.getFileName.toString.endsWith(".class"))).map(x =>
        ClassFileEntryImpl(new PlainNioFile(x))).toVector
    }
  }

  override private[nsc] def list(inPackage: String): ClassPathEntries =
    if (inPackage == "") ClassPathEntries(packages(inPackage), Nil)
    else ClassPathEntries(packages(inPackage), classes(inPackage))

  // Patched: scalac 2.12.8 has Seq(dir.toUri.toURL) here, and JDK 15+ reject toUri on
  // jrt:/packages ("cannot be represented as URI"), which breaks macro expansion, as the macro
  // class loader is built from these URLs. This is what 2.12.9 does (scala/scala#7412).
  def asURLs: Seq[URL] = Seq(new URL("jrt:/"))
  // We don't yet have a scheme to represent the JDK modules in our `-classpath`.
  // java models them as entries in the new "module path", we'll probably need to follow this.
  def asClassPathStrings: Seq[String] = Nil

  def findClassFile(className: String): Option[AbstractFile] = {
    if (!className.contains(".")) None
    else {
      val (inPackage, _) = separatePkgAndClassNames(className)
      packageToModuleBases.getOrElse(inPackage, Nil).iterator.flatMap { x =>
        val file = x.resolve(className.replace('.', '/') + ".class")
        if (Files.exists(file)) new scala.reflect.io.PlainNioFile(file) :: Nil else Nil
      }.take(1).toList.headOption
    }
  }
}
