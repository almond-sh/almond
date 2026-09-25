package almond.amm

import ammonite.runtime.ImportHook
import ammonite.util.{ImportTree, Imports, Name}
import ammonite.util.Util.CodeSource

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scala.collection.mutable

/** Wraps Ammonite's `$file` and `$exec` import hooks, so that scripts that changed since they were
  * last loaded get loaded again.
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
final class ReloadingSourceHook(underlying: ImportHook) extends ImportHook {

  // script path -> (hash of its content, version used in its wrapper name) when it was last loaded
  private val loaded = mutable.Map.empty[os.Path, (String, Int)]

  def handle(
    source: CodeSource,
    tree: ImportTree,
    interp: ImportHook.InterpreterInterface,
    wrapperPath: Seq[Name]
  ): Either[String, Seq[ImportHook.Result]] =
    underlying.handle(source, tree, interp, wrapperPath).map { results =>
      results.map {
        case res: ImportHook.Result.Source => versioned(res)
        case other                         => other
      }
    }

  private def versioned(res: ImportHook.Result.Source): ImportHook.Result.Source = synchronized {
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

object ReloadingSourceHook {

  /** Ammonite's `$file` and `$exec` import hooks, wrapped in [[ReloadingSourceHook]]s */
  def importHooks: Map[Seq[String], ImportHook] =
    ImportHook.defaults ++ Seq(
      Seq("file") -> new ReloadingSourceHook(ImportHook.File),
      Seq("exec") -> new ReloadingSourceHook(ImportHook.Exec)
    )

  private def hash(code: String): String = {
    val md = MessageDigest.getInstance("MD5")
    val b  = md.digest(code.getBytes(StandardCharsets.UTF_8))
    new BigInteger(1, b).toString(16)
  }
}
