package almond.amm

import ammonite.directives.UsingDirective
import ammonite.runtime.{DirectiveHook, ImportHook}
import ammonite.util.{ImportTree, Imports, Name}
import ammonite.util.Util.CodeSource

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

import scala.collection.mutable

/** Wraps Ammonite's `$file` and `$exec` import hooks (and its `//> using script` directive hook,
  * see [[ReloadingSourceHook.Directive]]), so that scripts that changed since they were last loaded
  * get loaded again.
  *
  * Ammonite loads a given script at most once per session: it caches loaded scripts in memory,
  * keyed on their wrapper name. That is fine when running a script once from the command line, but
  * not in a notebook, where users edit scripts and re-run the cells importing them, in a session
  * that can stay alive for hours.
  *
  * Ammonite class loaders being parent-first, a changed script cannot be re-defined under its
  * original wrapper class name either (the old class would keep being loaded). So when the content
  * of a script changed, this hook gives its wrapper a new name (`Foo$v1`, `Foo$v2`, …), while still
  * bringing it in scope under its original name (`Foo`). This mimics what happens when one
  * re-defines a value in a notebook: cells run after that see the new definition.
  */
final class ReloadingSourceHook(
  underlying: ImportHook,
  versions: ReloadingSourceHook.Versions
) extends ImportHook {

  def handle(
    source: CodeSource,
    tree: ImportTree,
    interp: ImportHook.InterpreterInterface,
    wrapperPath: Seq[Name]
  ): Either[String, Seq[ImportHook.Result]] =
    underlying.handle(source, tree, interp, wrapperPath).map(versions.versionedAll)
}

object ReloadingSourceHook {

  /** Versions of the scripts loaded so far, shared by the hooks that load scripts */
  final class Versions {

    // script path -> (hash of its content, version used in its wrapper name) when it was last
    // loaded
    private val loaded = mutable.Map.empty[Path, (String, Int)]

    def versionedAll(results: Seq[ImportHook.Result]): Seq[ImportHook.Result] =
      results.map {
        case res: ImportHook.Result.Source => versioned(res)
        case other                         => other
      }

    def versioned(res: ImportHook.Result.Source): ImportHook.Result.Source = synchronized {
      res.codeSource.path match {
        case None => res
        case Some(path) =>
          val hash = ReloadingSourceHook.hash(res.code)
          val version = loaded.get(path) match {
            case None                 => 0
            case Some((`hash`, ver))  => ver
            case Some((_, formerVer)) => formerVer + 1
          }
          loaded(path) = (hash, version)
          if (version == 0) res
          else {
            val codeSource     = res.codeSource
            val wrapperName    = codeSource.wrapperName
            val newWrapperName = Name(s"${wrapperName.raw}$$v$version")
            // hookImports look like `import ammonite.$sess.Foo.{instance => Foo}`: the wrapper name
            // sits right after the package name in the import prefix
            val wrapperIdx = codeSource.pkgRoot.length + codeSource.flexiblePkgName.length
            val newImports = Imports(
              res.hookImports.value.map { data =>
                if (data.prefix.lift(wrapperIdx).contains(wrapperName))
                  data.copy(prefix = data.prefix.updated(wrapperIdx, newWrapperName))
                else
                  data
              }
            )
            res.copy(
              codeSource = codeSource.copy(wrapperName = newWrapperName),
              hookImports = newImports
            )
          }
      }
    }
  }

  /** Wraps a directive hook loading scripts, like [[ReloadingSourceHook]] does for import hooks */
  final class Directive(underlying: DirectiveHook, versions: Versions) extends DirectiveHook {
    def handle(
      source: CodeSource,
      directive: UsingDirective,
      interp: ImportHook.InterpreterInterface,
      wrapperPath: Seq[Name]
    ): Either[String, Seq[ImportHook.Result]] =
      underlying.handle(source, directive, interp, wrapperPath).map(versions.versionedAll)
    override def complete(
      interp: ammonite.interp.Interpreter,
      directory: os.Path,
      prefix: String
    ): (Int, Seq[String]) =
      underlying.complete(interp, directory, prefix)
  }

  /** Hooks that load scripts, sharing the same script versions
    *
    * @param importHooks
    *   Ammonite's import hooks, with the `$file` and `$exec` ones wrapped in
    *   [[ReloadingSourceHook]]s
    * @param directiveHooks
    *   Ammonite's directive hooks, with the `//> using script` ones wrapped in
    *   [[ReloadingSourceHook.Directive]]s
    */
  final case class Hooks(
    importHooks: Map[Seq[String], ImportHook],
    directiveHooks: Map[String, DirectiveHook]
  )

  def hooks(): Hooks = {
    val versions = new Versions
    val scripts  = new Directive(DirectiveHook.Scripts, versions)
    Hooks(
      importHooks = ImportHook.defaults ++ Seq(
        Seq("file") -> new ReloadingSourceHook(ImportHook.File, versions),
        Seq("exec") -> new ReloadingSourceHook(ImportHook.Exec, versions)
      ),
      directiveHooks = DirectiveHook.defaults.map {
        case (key, DirectiveHook.Scripts) => key -> scripts
        case other                        => other
      }
    )
  }

  private def hash(code: String): String = {
    val md = MessageDigest.getInstance("MD5")
    val b  = md.digest(code.getBytes(StandardCharsets.UTF_8))
    new BigInteger(1, b).toString(16)
  }
}
