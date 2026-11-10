// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/Scoped.scala

package almond.directivehandler

final case class Scoped[+T](path: ScopePath, value: T) {
  def appliesTo(candidate: ScopePath): Boolean =
    path.root == candidate.root &&
    candidate.subPath.startsWith(path.subPath)
  def valueFor(candidate: ScopePath): Option[T] =
    if (appliesTo(candidate)) Some(value) else None

  def map[U](f: T => U): Scoped[U] =
    copy(value = f(value))
}
