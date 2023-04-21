package almond.toree

import ammonite.util.Ref
import almond.api.JupyterApi

import java.net.URI
import java.nio.file.Paths

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
          if (params.force)
            System.err.println(s"Warning: ignoring unsupported %AddJar argument -f")
          if (params.magic)
            System.err.println(s"Warning: ignoring unsupported %AddJar argument --magic")
          if (uri.getScheme == "file") {
            val path = Paths.get(uri)
            val q    = "\""
            Right(s"interp.load.cp(os.Path($q$q$q$path$q$q$q))")
          }
          else if (uri.getScheme == "http" || uri.getScheme == "https") {
            val file = coursierapi.Cache.create().get(coursierapi.Artifact.of(uri.toASCIIString))
            val q    = "\""
            Right(s"interp.load.cp(os.Path($q$q$q$file$q$q$q))")
          }
          else {
            System.err.println(
              s"Warning: ignoring %AddJar URL $url (unsupported protocol ${uri.getScheme})"
            )
            Right("")
          }
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
  }

  class LsMagicHandler extends LineMagicHandler {
    def handle(name: String, values: Seq[String]): Either[JupyterApi.ExecuteHookResult, String] = {
      if (values.nonEmpty)
        System.err.println(
          s"Warning: ignoring unrecognized values passed to %LsMagic: ${values.mkString(" ")}"
        )

      println("Available line magics:")
      println(handlerKeys.toVector.sorted.map("%" + _).mkString(" "))
      println()

      println("Available cell magics:")
      println(CellMagicHandlers.handlerKeys.toVector.sorted.map("%%" + _).mkString(" "))
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
