// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/ShadowingSeq.scala

package almond.directivehandler

import dependency.AnyDependency

import scala.collection.mutable

/** Seq ensuring some of its values are unique according to some key */
final case class ShadowingSeq[T] private (values: Seq[Seq[T]]) {
  lazy val toSeq: Seq[T] = values.flatten
  def map[U](f: T => U)(implicit key: ShadowingSeq.KeyOf[U]): ShadowingSeq[U] =
    ShadowingSeq.empty[U] ++ toSeq.map(f)
  def mapSubSeq[U](f: Seq[T] => Seq[U]): ShadowingSeq[U] =
    ShadowingSeq[U](values.map(f))
  def filter(f: T => Boolean)(implicit key: ShadowingSeq.KeyOf[T]): ShadowingSeq[T] =
    ShadowingSeq.empty ++ toSeq.filter(f)
  def filterSubSeq(f: Seq[T] => Boolean): ShadowingSeq[T] =
    ShadowingSeq(values.filter(f))
  def filterKeys(f: T => Boolean): ShadowingSeq[T] =
    filterSubSeq {
      case Seq(head, _*) => f(head)
      case _             => true
    }
  def keys: Seq[T] = values.map(_.head)
  def ++(other: Seq[T])(implicit key: ShadowingSeq.KeyOf[T]): ShadowingSeq[T] =
    addGroups(ShadowingSeq.groups(other, key.groups(other)))
  private def addGroups(other: Seq[Seq[T]])(implicit key: ShadowingSeq.KeyOf[T]): ShadowingSeq[T] =
    if (other.isEmpty) this
    else {
      val l    = new mutable.ListBuffer[Seq[T]]
      val seen = new mutable.HashSet[String]

      for (group <- values.iterator ++ other.iterator) {
        assert(group.nonEmpty)
        val keyOpt = key.get(group.head)
        if (!keyOpt.exists(seen.contains)) {
          l += group
          for (key <- keyOpt)
            seen += key
        }
      }

      ShadowingSeq(l.toList)
    }
  def keyValueMap: Map[T, Seq[T]] =
    values
      .flatMap {
        case Seq(head, tail @ _*) => Some(head -> tail)
        case _                    => None
      }
      .toMap

  def get(key: T): Seq[T] =
    keyValueMap
      .get(key)
      .toSeq.flatten
}

object ShadowingSeq {

  final case class KeyOf[T](
    get: T => Option[String],
    /** The indices at which sub-groups of values start */
    groups: Seq[T] => Seq[Int]
  )
  object KeyOf {
    implicit val keyOfAnyDependency: KeyOf[AnyDependency] =
      KeyOf(dep => Some(dep.module.render), _.indices)
  }

  def empty[T]: ShadowingSeq[T] = ShadowingSeq(Nil)

  def from[T](values: Seq[T])(implicit key: KeyOf[T]): ShadowingSeq[T] =
    empty[T] ++ values

  private def groups[T](values: Seq[T], indices: Seq[Int]): Seq[Seq[T]] = {
    val safeIndices = Seq(0) ++ indices ++ Seq(values.length)
    safeIndices
      .sliding(2)
      .map {
        case Seq(start, end) =>
          values.slice(start, end)
      }
      .filter(_.nonEmpty)
      .toVector
  }
}
