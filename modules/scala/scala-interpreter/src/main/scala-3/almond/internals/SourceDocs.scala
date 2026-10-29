package almond.internals

import almond.logger.Logger
import ammonite.runtime.Frame
import ammonite.util.Util.newLine
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.{Context, ctx}
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.NameOps.stripModuleClassSuffix
import dotty.tools.dotc.core.Symbols.{defn, Symbol}
import dotty.tools.dotc.core.Types.{MethodType, PolyType, Type}

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.{LinkedHashMap => JLinkedHashMap, Map => JMap}
import java.util.zip.ZipFile

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** Doc comments read from source JARs, for the symbols whose docs can't be read from TASTy files:
  * those of Java libraries (and of the JDK, from its src.zip), and those of Scala 2 libraries (like
  * the standard library before Scala 3.8).
  *
  * The source JARs are those of the class path (see SourcePath): the kernel launcher puts those of
  * the kernel dependencies there, Ammonite adds those of the dependencies of `import $ivy`, and
  * users can add more via `--extra-class-path` or `interp.load.cp`. JARs whose source JAR isn't in
  * the class path have no docs here.
  *
  * Not thread-safe: inspections are computed while holding the lock of the compiler (see
  * ScalaInterpreter), which also guards the caches here.
  */
final class SourceDocs(frames: => List[Frame], log: Logger) {
  import SourceDocs._

  private def classLoader = frames.head.classloader

  // the source JARs of the class path, by the name of the JAR they have the sources of (JARs
  // that contain their own sources, like some launchers, are there under their own name)
  private var sourceJarsByName = Map.empty[String, Seq[Path]]
  // the session class path sourceJarsByName was computed for
  private var sessionClassPath    = Option.empty[Seq[String]]
  private lazy val baseSourceJars = SourcePath.base(frames, log)

  // small ones: paths
  private val sourceJars = mutable.HashMap.empty[Path, Option[Path]]
  private val locations  = mutable.HashMap.empty[String, Option[Location]]
  // the .scala files of the packages of source JARs, by the names of the classes they define
  private val packageIndices = mutable.HashMap.empty[(Path, String), Map[String, List[String]]]
  // bigger ones: open files, parsed sources
  private val zipFiles =
    new Lru[Path, Option[ZipFile]](
      32,
      _.foreach(zf =>
        try zf.close()
        catch { case NonFatal(_) => }
      )
    )
  private val javaFiles  = new Lru[Location, Seq[JavaMember]](64)
  private val scalaFiles = new Lru[Location, Option[untpd.Tree]](64)

  /** Closes the source JARs opened so far */
  def close(): Unit = {
    zipFiles.clear()
    javaFiles.clear()
    scalaFiles.clear()
  }

  /** The rendered doc of the first of `candidates` that has one
    *
    * @param candidates
    *   a symbol, followed by the symbols it overrides
    * @param site
    *   the class whose scaladoc variables (`$coll`, …) should be substituted
    */
  def docstring(candidates: Seq[Symbol], site: Symbol)(using Context): Option[String] = {
    refreshSourceJars()
    val raws = candidates.iterator.flatMap(sym => rawDoc(sym).map((sym, _)))
    raws.nextOption().map { (sym, raw) =>
      // for @inheritdoc and {@inheritDoc}: the doc of an overridden symbol
      lazy val parentMain = raws.nextOption().map((_, raw0) => parse(raw0, "").main)
      val parsed          = parse(raw, parentMain.getOrElse(""))
      val expanded =
        if (raw.java) parsed
        else parsed.expand(variables(sym, site))
      expanded.render
    }.filter(_.nonEmpty)
  }

  private def parse(raw: RawDoc, inherited: => String): DocText =
    if (raw.java) Javadoc.parse(raw.doc, inherited)
    else DocText.fromScaladoc(raw.doc, inherited)

  /** The names of the parameters of a Java method, which class files lack */
  def javaParamNames(sym: Symbol)(using Context): Option[List[String]] =
    if (!sym.exists || !sym.is(Flags.JavaDefined) || !sym.is(Flags.Method)) None
    else {
      refreshSourceJars()
      location(sym.owner)
        .filter(_.java)
        .flatMap(javaMember(_, sym))
        .map(_.paramNames)
        .filter(_.nonEmpty)
    }

  // Source JARs can be added to the session, along with dependencies in particular
  private def refreshSourceJars(): Unit = {
    val classPath = frames.flatMap(_.classpath).map(_.toString)
    if (!sessionClassPath.contains(classPath)) {
      sessionClassPath = Some(classPath)
      val sourcePath =
        try SourcePath.session(frames, log) ++ baseSourceJars
        catch {
          case NonFatal(e) =>
            // the docs of the JDK and those from TASTy files don't need these
            log.warn("Error computing the source JARs of the class path", e)
            Nil
        }
      sourceJarsByName = sourcePath.groupBy { p =>
        val name = p.getFileName.toString
        if (name.endsWith("-sources.jar")) name.stripSuffix("-sources.jar") + ".jar"
        else name
      }
      // the sources that couldn't be found so far may be there now
      sourceJars.clear()
      locations.clear()
    }
  }

  private def rawDoc(sym: Symbol)(using Context): Option[RawDoc] =
    if (!sym.exists || sym.is(Flags.Package)) None
    else {
      val sym0 = if (sym.isConstructor && !sym.is(Flags.JavaDefined)) sym.owner else sym
      val cls  = if (sym0.isClass) sym0 else sym0.owner
      if (!cls.isClass) None
      else
        location(cls).flatMap { loc =>
          if (loc.java) javaDoc(loc, sym0).map(RawDoc(_, java = true))
          else scalaDoc(loc, sym0).map(RawDoc(_, java = false))
        }
    }

  // Where the source of cls is
  private def location(cls: Symbol)(using Context): Option[Location] = {
    val top  = cls.topLevelClass
    val pkg  = top.owner.fullName.toString
    val name = top.name.stripModuleClassSuffix.toString
    val prefix =
      if (pkg.isEmpty || pkg == "<empty>" || pkg == "<root>") ""
      else pkg.replace('.', '/') + "/"
    locations.getOrElseUpdate(
      prefix + name, {
        val urlOpt = Option(classLoader.getResource(prefix + name + ".class"))
          .orElse(Option(classLoader.getResource(prefix + name + "$.class")))
        val isJava = top.is(Flags.JavaDefined)
        urlOpt.flatMap { url =>
          url.getProtocol match {
            case "jar" =>
              val str = url.toString.stripPrefix("jar:")
              val sep = str.indexOf("!/")
              if (sep < 0) None
              else
                sourcesJar(Paths.get(new URI(str.take(sep)))).flatMap { src =>
                  if (isJava) Some(Location(src, prefix + name + ".java", java = true))
                  else scalaEntry(src, prefix, name).map(Location(src, _, java = false))
                }
            case "jrt" =>
              // like jrt:/java.base/java/lang/String.class
              val module = url.getPath.stripPrefix("/").takeWhile(_ != '/')
              jdkSources.map(Location(_, s"$module/$prefix$name.java", java = true))
            case _ =>
              None
          }
        }
      }
    )
  }

  private lazy val jdkSources: Option[Path] =
    Option(System.getProperty("java.home"))
      .map(Paths.get(_, "lib", "src.zip"))
      .filter(Files.isRegularFile(_))

  private def sourcesJar(jar: Path): Option[Path] =
    sourceJars.getOrElseUpdate(
      jar, {
        val name       = jar.getFileName.toString
        val candidates = sourceJarsByName.getOrElse(name, Nil)
        // if several JARs have the same name, the source JAR next to jar
        candidates.find(_.getParent == jar.getParent).orElse(candidates.headOption)
      }
    )

  private def zipFile(path: Path): Option[ZipFile] =
    zipFiles.getOrElseUpdate(
      path,
      try Some(new ZipFile(path.toFile))
      catch {
        case NonFatal(e) =>
          log.debug(s"Could not open $path", e)
          None
      }
    )

  private def read(zip: Path, entry: String): Option[String] =
    for {
      zf <- zipFile(zip)
      e  <- Option(zf.getEntry(entry))
    } yield {
      val is = zf.getInputStream(e)
      try {
        val os  = new ByteArrayOutputStream
        val buf = new Array[Byte](16384)
        var n   = is.read(buf)
        while (n >= 0) {
          os.write(buf, 0, n)
          n = is.read(buf)
        }
        new String(os.toByteArray, StandardCharsets.UTF_8)
      }
      finally is.close()
    }

  // Scala files aren't necessarily named after the classes they define
  private def scalaEntry(zip: Path, prefix: String, name: String): Option[String] =
    zipFile(zip).flatMap { zf =>
      val direct = prefix + name + ".scala"
      if (zf.getEntry(direct) != null) Some(direct)
      else {
        // each package is scanned once, rather than once per class looked up in it
        val index =
          packageIndices.getOrElseUpdate((zip, prefix), packageIndex(zip, zf, prefix))
        val candidates = index.getOrElse(name, Nil)
        // prefer files named like a prefix of the class name, like Seq.scala for SeqOps
        candidates.sortBy { n =>
          val stem = n.drop(prefix.length).stripSuffix(".scala")
          if (name.startsWith(stem)) -stem.length else 0
        }.headOption
      }
    }

  private def packageIndex(
    zip: Path,
    zf: ZipFile,
    prefix: String
  ): Map[String, List[String]] = {
    val entries = zf.entries()
      .asScala
      .map(_.getName)
      .filter { n =>
        n.startsWith(prefix) && n.endsWith(".scala") && !n.drop(prefix.length).contains('/')
      }
      .toList
    val definitions = for {
      entry   <- entries
      content <- read(zip, entry).toList
      m       <- Definition.findAllMatchIn(content)
    } yield (m.group(1), entry)
    definitions.groupMap(_._1)(_._2).map((name, entries) => name -> entries.distinct)
  }

  // The names of the classes enclosing sym (sym included if it's a class), outermost first
  private def classNames(cls: Symbol)(using Context): List[String] =
    cls.ownersIterator
      .takeWhile(s => s.exists && !s.is(Flags.Package))
      .filter(_.isClass)
      .map(_.name.stripModuleClassSuffix.toString)
      .toList
      .reverse

  private def javaDoc(loc: Location, sym: Symbol)(using Context): Option[String] =
    javaMember(loc, sym).map(_.doc)

  private def javaMember(loc: Location, sym: Symbol)(using Context): Option[JavaMember] = {
    val members =
      javaFiles.getOrElseUpdate(loc, read(loc.zip, loc.entry).fold(Nil)(JavaScanner.members))
    val (classes, name) =
      if (sym.isClass) (classNames(sym.owner), sym.name.stripModuleClassSuffix.toString)
      else (classNames(sym.owner), sym.name.toString)
    val sameName = members.filter(m => m.classes == classes && m.name == name)
    val matching =
      if (sym.is(Flags.Method)) {
        val params = paramTypeNames(sym.info)
        sameName
          .filter(_.params.length == params.length)
          .sortBy(m => -m.params.zip(params).count((a, b) => a == b))
      }
      else sameName.filter(_.params.isEmpty)
    matching.headOption
  }

  /** The source code of the default arguments of a Scala method, by parameter name */
  def defaultArgs(sym: Symbol)(using Context): Map[String, String] =
    if (!sym.exists || !sym.is(Flags.Method) || sym.is(Flags.JavaDefined)) Map.empty
    else {
      refreshSourceJars()
      val res = for {
        loc     <- location(sym.owner).filter(!_.java)
        content <- read(loc.zip, loc.entry)
        found   <- scalaDefinition(loc, sym)
      } yield found match {
        case d: untpd.DefDef => ScalaSources.defaultArgs(d, content)
        case _               => Map.empty[String, String]
      }
      res.getOrElse(Map.empty)
    }

  private def scalaDefinition(loc: Location, sym: Symbol)(using Context): Option[untpd.Tree] = {
    val treeOpt =
      scalaFiles.getOrElseUpdate(loc, read(loc.zip, loc.entry).flatMap(parseScala(loc, _)))
    val ownerCls = if (sym.isClass) sym else sym.owner
    for {
      tree  <- treeOpt
      owner <- findClass(List(tree), classNames(ownerCls), ownerCls)
      found <-
        if (sym.isClass) Some(owner)
        else if (sym.isPrimaryConstructor)
          owner match {
            case untpd.TypeDef(_, tmpl: untpd.Template) => Some(tmpl.constr)
            case _                                      => None
          }
        else findMember(owner, sym)
    } yield found
  }

  private def scalaDoc(loc: Location, sym: Symbol)(using Context): Option[String] =
    for {
      found <- scalaDefinition(loc, sym)
      doc <- found match {
        case m: untpd.MemberDef => m.rawComment
        case _                  => None
      }
    } yield doc.raw

  private def parseScala(loc: Location, content: String)(using Context): Option[untpd.Tree] =
    try Some(ScalaSources.parse(loc.entry, content))
    catch {
      case NonFatal(e) =>
        log.debug(s"Could not parse ${loc.entry} from ${loc.zip}", e)
        None
    }

  private def templateBody(tree: untpd.Tree)(using Context): List[untpd.Tree] =
    tree match {
      case untpd.TypeDef(_, tmpl: untpd.Template) => tmpl.body
      case untpd.ModuleDef(_, tmpl)               => tmpl.body
      case untpd.PackageDef(_, stats)             => stats
      case _                                      => Nil
    }

  // Walks down packages and classes to the class named after the last of names, which is
  // an object if cls is a module class
  private def findClass(
    trees: List[untpd.Tree],
    names: List[String],
    cls: Symbol
  )(using Context): Option[untpd.Tree] =
    names match {
      case Nil => None
      case name :: rest =>
        val wantModule = rest.isEmpty && cls.isClass && cls.is(Flags.ModuleClass)
        val candidates = trees.flatMap {
          case p: untpd.PackageDef => templateBody(p)
          case other               => List(other)
        }
        val nested = trees.collect { case p: untpd.PackageDef => p }
        def matches(t: untpd.Tree) = t match {
          case t: untpd.ModuleDef => t.name.toString == name && (rest.nonEmpty || wantModule)
          case t: untpd.TypeDef   => t.name.toString == name && (rest.nonEmpty || !wantModule)
          case _                  => false
        }
        candidates.find(matches) match {
          case Some(found) if rest.isEmpty => Some(found)
          case Some(found)                 => findClass(templateBody(found), rest, cls)
          case None if nested.nonEmpty     => findClass(nested.flatMap(templateBody), names, cls)
          case None                        => None
        }
    }

  private def findMember(owner: untpd.Tree, sym: Symbol)(using Context): Option[untpd.Tree] = {
    val name = sym.name.stripModuleClassSuffix.toString
    val sameName = templateBody(owner).filter {
      case t: untpd.DefDef    => t.name.toString == name
      case t: untpd.ValDef    => t.name.toString == name
      case t: untpd.ModuleDef => t.name.toString == name
      case t: untpd.TypeDef   => t.name.toString == name
      case _                  => false
    }
    if (sym.is(Flags.Method) && sameName.lengthCompare(1) > 0) {
      val arities0 = arities(sym.info)
      sameName
        .find {
          case d: untpd.DefDef =>
            d.paramss.filter(c => c.isEmpty || c.head.isInstanceOf[untpd.ValDef]).map(_.length) ==
              arities0
          case _ => false
        }
        .orElse(sameName.headOption)
    }
    else sameName.headOption
  }

  // The scaladoc variables defined with @define in the docs of site and of its base classes,
  // most specific first
  private def variables(sym: Symbol, site: Symbol)(using Context): Map[String, String] = {
    val classes =
      (if (site.exists && site.isClass) site.info.baseClasses else Nil) ++
        sym.ownersIterator.filter(_.isClass).toList
    classes.reverse.foldLeft(Map.empty[String, String]) { (acc, cls) =>
      acc ++ rawDoc(cls).filter(!_.java).fold(Map.empty[String, String])(r =>
        DocText.defines(r.doc)
      )
    }
  }
}

object SourceDocs {

  private final case class Location(zip: Path, entry: String, java: Boolean)
  private final case class RawDoc(doc: String, java: Boolean)

  // Actual definitions, at the beginning of a line (not in comments, say), capturing the name
  // of what's defined, alphanumeric or symbolic (like `::`)
  private val Definition = {
    val modifiers =
      """(?:(?:private|protected|final|sealed|abstract|case|implicit|open|transparent)(?:\[[^\]]*\])?\s+)*"""
    ("""(?m)^\s*""" + modifiers + """(?:class|trait|object)\s+(\w+|[^\w\s\[\](){}]+)""").r
  }

  /** A map that keeps its most recently used entries only */
  private final class Lru[K, V](maxSize: Int, onEvict: V => Unit = (_: V) => ()) {
    private val map = new JLinkedHashMap[K, V](16, 0.75f, true) {
      override def removeEldestEntry(eldest: JMap.Entry[K, V]): Boolean = {
        val evict = size() > maxSize
        if (evict) onEvict(eldest.getValue)
        evict
      }
    }
    def getOrElseUpdate(key: K, value: => V): V =
      if (map.containsKey(key)) map.get(key)
      else {
        val value0 = value
        map.put(key, value0)
        value0
      }
    def clear(): Unit = {
      map.values().asScala.foreach(onEvict)
      map.clear()
    }
  }

  private val primitives = Map(
    "Int"     -> "int",
    "Long"    -> "long",
    "Short"   -> "short",
    "Byte"    -> "byte",
    "Char"    -> "char",
    "Boolean" -> "boolean",
    "Float"   -> "float",
    "Double"  -> "double",
    "Any"     -> "Object",
    "AnyRef"  -> "Object"
  )

  // The Java-like simple names of the parameter types of a method, to pick the right overload
  private def paramTypeNames(tpe: Type)(using Context): List[String] = {
    def name(t: Type): String = {
      val t0 = t.widenDealias
      if (t0.isRef(defn.ArrayClass) || t0.isRef(defn.RepeatedParamClass))
        t0.argInfos.headOption.fold("Object")(name) + "[]"
      else {
        val cls  = t0.classSymbol
        val base = if (cls.exists) cls.name.stripModuleClassSuffix.toString else "Object"
        primitives.getOrElse(base, base)
      }
    }
    tpe match {
      case pt: PolyType   => paramTypeNames(pt.resType)
      case mt: MethodType => mt.paramInfos.map(name) ++ paramTypeNames(mt.resType)
      case _              => Nil
    }
  }

  private def arities(tpe: Type)(using Context): List[Int] =
    tpe match {
      case pt: PolyType   => arities(pt.resType)
      case mt: MethodType => mt.paramNames.length :: arities(mt.resType)
      case _              => Nil
    }
}
