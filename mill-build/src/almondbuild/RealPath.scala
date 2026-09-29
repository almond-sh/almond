package almondbuild

import mill.api.PathRef

import java.nio.file.{Files, Path}

import scala.annotation.tailrec

object RealPath {

  /** Real on-disk absolute path of `path`, with symlinks resolved, as a string.
    *
    * Use this rather than `PathRef.toAbsString` for paths that end up in the values of cached
    * tasks, like `forkArgs` or `forkEnv`. When Mill runs without a daemon (`./mill -i`), paths
    * under the workspace go through a `out/mill-no-daemon/<run-id>/mill-workspace` symlink, which
    * only lives as long as that Mill run. `PathRef.toAbsString` keeps that symlink in the paths it
    * returns, so that the cached values of later runs point to directories that don't exist any
    * more.
    *
    * `path` doesn't have to exist: the symlinks of its longest existing parent are resolved.
    */
  def string(path: os.Path): String = {
    val absPath = PathRef.toAbsNioPath(path)
    @tailrec
    def resolve(existing: Path, missing: List[Path]): Path =
      if (existing == null) absPath
      else if (Files.exists(existing)) missing.foldLeft(existing.toRealPath())(_.resolve(_))
      else resolve(existing.getParent, existing.getFileName :: missing)
    resolve(absPath, Nil).toString
  }
}
