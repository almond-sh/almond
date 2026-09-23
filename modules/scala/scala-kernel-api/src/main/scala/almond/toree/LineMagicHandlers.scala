package almond.toree

import ammonite.util.Ref
import almond.api.JupyterApi
import almond.toree.internals.IsScala2

import java.io.{InputStream, OutputStream}
import java.net.{URI, URL}
import java.nio.file.attribute.{FileAttribute, PosixFilePermission, PosixFilePermissions}
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._
import scala.util.Properties

object LineMagicHandlers {

  class AddDepHandler extends LineMagicHandler {
    import AddDepHandler._
    def handle(name: String, values: Seq[String]): Either[JupyterApi.ExecuteHookResult, String] =
      values match {
        case Seq(org, name, ver, other @ _*) =>
          val params     = parseParams(other.toList, Params())
          val depSuffix  = if (params.transitive) "" else ",intransitive"
          val repoSuffix = params.repositories.map(repo => s"; import $$repo.`$repo`").mkString

          if (params.trace)
            System.err.println(s"Warning: ignoring unsupported %AddDeps argument --trace")
          if (params.verbose)
            System.err.println(s"Warning: ignoring unsupported %AddDeps argument --verbose")
          if (params.abortOnResolutionErrors)
            System.err.println(
              s"Warning: ignoring unsupported %AddDeps argument --abort-on-resolution-errors"
            )

          Right(s"import $$ivy.`$org:$name:$ver$depSuffix`$repoSuffix")
        case _ =>
          System.err.println(
            s"Warning: ignoring malformed %AddDeps Toree magic (expected '%AddDeps org name version [optional-arguments*]')"
          )
          Right("")
      }
  }

  object AddDepHandler {
    // not 100% sure about the default values, but https://github.com/apache/incubator-toree/blob/5b19aac2e56a56d35c888acc4ed5e549b1f4ed7c/etc/examples/notebooks/magic-tutorial.ipynb
    // seems to imply these are correct
    private case class Params(
      transitive: Boolean = false,
      trace: Boolean = false,
      verbose: Boolean = false,
      abortOnResolutionErrors: Boolean = false,
      repositories: Seq[String] = Nil
    )

    private def parseParams(args: List[String], params: Params): Params =
      args match {
        case Nil                 => params
        case "--trace" :: t      => parseParams(t, params.copy(trace = true))
        case "--verbose" :: t    => parseParams(t, params.copy(verbose = true))
        case "--transitive" :: t => parseParams(t, params.copy(transitive = true))
        case "--abort-on-resolution-errors" :: t =>
          parseParams(t, params.copy(abortOnResolutionErrors = true))
        case "--repository" :: repo :: t =>
          parseParams(t, params.copy(repositories = params.repositories :+ repo))
        case other :: t =>
          System.err.println(s"Warning: ignoring unrecognized %AddDeps argument '$other'")
          parseParams(t, params)
      }
  }

  class AddJarHandler extends LineMagicHandler {
    import AddJarHandler._
    def handle(name: String, values: Seq[String]): Either[JupyterApi.ExecuteHookResult, String] =
      values match {
        case Seq(url, other @ _*) =>
          val uri    = new URI(url)
          val params = parseParams(other.toList, Params())
          if (params.magic)
            System.err.println(s"Warning: ignoring unsupported %AddJar argument --magic")

          val q = "\""
          val path =
            if (uri.getScheme == "file")
              Paths.get(uri).toString
            else if (uri.getScheme == "http" || uri.getScheme == "https") {
              // Mark as "changing" if params.force is true.
              // This makes the cache check for updates if the download (or last check) is older than the coursier TTL.
              // So it's not really "forcing" an update for now...
              val artifact = coursierapi.Artifact.of(uri.toASCIIString, params.force, false)
              val file     = coursierapi.Cache.create().get(artifact)
              file.toString
            }
            else {
              // Let it fail with MalformedURLException if there's no handler for it, so that users know something's wrong
              val url  = uri.toURL
              val file = writeUrlToTmpFile(url)
              file.toString
            }

          val maybeEscapedPath =
            // seems the handling of backslash between backticks changed in Scala 3…
            if (IsScala2.isScala2) path
            else path.replace("\\", "\\\\")
          Right(s"import $$cp.`$maybeEscapedPath`")

        case _ =>
          System.err.println(
            s"Warning: ignoring malformed %AddJar Toree magic (expected '%AddJar url [optional-arguments*]')"
          )
          Right("")
      }
  }

  object AddJarHandler {
    private case class Params(
      force: Boolean = false,
      magic: Boolean = false
    )

    private def parseParams(args: List[String], params: Params): Params =
      args match {
        case Nil            => params
        case "-f" :: t      => parseParams(t, params.copy(force = true))
        case "--magic" :: t => parseParams(t, params.copy(magic = true))
        case other :: t =>
          System.err.println(s"Warning: ignoring unrecognized %AddJar argument '$other'")
          parseParams(t, params)
      }

    private def writeUrlToTmpFile(url: URL): Path = {
      var is: InputStream  = null
      var os: OutputStream = null
      try {
        is = url.openStream()

        val perms: Seq[FileAttribute[_]] =
          if (Properties.isWin) Nil
          else
            Seq(
              PosixFilePermissions.asFileAttribute(
                Set(
                  PosixFilePermission.OWNER_READ,
                  PosixFilePermission.OWNER_WRITE,
                  PosixFilePermission.OWNER_EXECUTE
                ).asJava
              )
            )
        // FIXME Get suffix from the URL?
        val tmpFile = Files.createTempFile(
          "almond-add-jar",
          ".jar",
          perms: _*
        )
        tmpFile.toFile.deleteOnExit()
        os = Files.newOutputStream(tmpFile)
        val buf  = Array.ofDim[Byte](128 * 1024)
        var read = 0
        while ({
          read = is.read(buf)
          read >= 0
        })
          os.write(buf, 0, read)
        tmpFile
      }
      finally {
        if (os != null)
          os.close()
        if (is != null)
          is.close()
      }
    }
  }

  class LsMagicHandler extends LineMagicHandler {
    def handle(name: String, values: Seq[String]): Either[JupyterApi.ExecuteHookResult, String] = {
      if (values.nonEmpty)
        System.err.println(
          s"Warning: ignoring unrecognized values passed to %LsMagic: ${values.mkString(" ")}"
        )

      println("Available line magics:")
      val lineKeys = (handlerKeys ++ LineMagicHook.userHandlers.keys).toVector.sorted.distinct
      println(lineKeys.map("%" + _).mkString(" "))
      println()

      println("Available cell magics:")
      val cellKeys =
        (CellMagicHandlers.handlerKeys ++ CellMagicHook.userHandlers.keys).toVector.sorted
      println(cellKeys.map("%%" + _).mkString(" "))
      println()

      Right("")
    }
  }

  class TruncationHandler(pprinter: Ref[pprint.PPrinter]) extends LineMagicHandler {
    private def enabled() = {
      val current = pprinter()
      current.defaultWidth != Int.MaxValue && current.defaultHeight != Int.MaxValue
    }
    private var formerWidth  = pprinter().defaultWidth
    private var formerHeight = pprinter().defaultHeight
    private def disable(): Unit =
      if (enabled()) {
        formerWidth = pprinter().defaultWidth
        formerHeight = pprinter().defaultHeight

        pprinter.update {
          pprinter().copy(
            defaultWidth = Int.MaxValue,
            defaultHeight = Int.MaxValue
          )
        }
      }
    private def enable(): Unit =
      if (!enabled())
        pprinter.update {
          pprinter().copy(
            defaultWidth = formerWidth,
            defaultHeight = formerHeight
          )
        }
    def handle(name: String, values: Seq[String]): Either[JupyterApi.ExecuteHookResult, String] = {
      values match {
        case Seq() =>
          val state = if (enabled()) "on" else "off"
          println(s"Truncation is currently $state")
        case Seq("on") =>
          enable()
          println("Output WILL be truncated.")
        case Seq("off") =>
          disable()
          println("Output will NOT be truncated")
        case _ =>
          System.err.println(
            s"Warning: ignoring %truncation magic with unrecognized parameters ${values.mkString(" ")}"
          )
      }
      Right("")
    }
  }

  def handlers(pprinter: Ref[pprint.PPrinter]) = Map(
    "adddeps"    -> new AddDepHandler,
    "addjar"     -> new AddJarHandler,
    "lsmagic"    -> new LsMagicHandler,
    "truncation" -> new TruncationHandler(pprinter)
  )
  def handlerKeys: Iterable[String] =
    handlers(Ref(pprint.PPrinter())).keys
}
