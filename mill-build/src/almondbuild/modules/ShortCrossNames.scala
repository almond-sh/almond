package almondbuild.modules

import almondbuild.ScalaVersions
import mill.api.Cross

import scala.collection.mutable

/** Lets the instances of a cross module over Scala versions be referred to by shorter versions from
  * the command line, when that's not ambiguous: its Scala 2.12 one as `foo.2_12` or `foo[2.12]`
  * rather than `foo.2_12_8` / `foo[2.12.8]` when it has a single 2.12 instance, its Scala 3.3 one
  * as `foo.3_3` when it has a single 3.3 one, or even as `foo.3` when it has a single Scala 3
  * instance, … (see [[ScalaVersions.shortVersions]]).
  *
  * Mill resolves the cross segments of the command line - `foo[2.12]`, or `foo.2_12`, that it tries
  * as `foo[2.12]` when `foo` has no `2_12` child - by looking them up in
  * [[mill.api.Cross.segmentsToModules]], that we add the short versions to here. These map to the
  * very module instances of the full versions, so that these keep their full version in their
  * segments, that Mill uses for their output directories and in its output. The `__` / `_`
  * wildcards rely on [[mill.api.Cross.items]] instead, so that they don't list the instances under
  * their short versions too, and while `foo[_]` goes through [[mill.api.Cross.segmentsToModules]],
  * Mill de-duplicates the modules it gets that way.
  */
trait ShortCrossNames[M <: Cross.Module[?]] extends Cross[M] {
  override val segmentsToModules: collection.MapView[List[String], M] = {
    val map = new mutable.LinkedHashMap[List[String], () => M]
    for (item <- items)
      map(item.crossSegments) = item.module
    val aliases = items
      .flatMap { item =>
        item.crossSegments match {
          case List(sv) => ScalaVersions.shortVersions(sv).map(List(_) -> item)
          case _        => Nil
        }
      }
      .filter { case (alias, _) => !map.contains(alias) }
    val aliasCounts = aliases.groupMapReduce(_._1)(_ => 1)(_ + _)
    for ((alias, item) <- aliases if aliasCounts(alias) == 1)
      map(alias) = item.module
    map.view.mapValues(_())
  }
}
