package almondbuild.modules

import mill.*
import mill.api.*
import mill.javalib.*

/** Libraries whose sources we ship, rather than depending on them.
  *
  * Listed, as `org:name:version`, among the dependencies users get from us (see
  * [[UserDependencyList]]), so that the kernel leaves them out of the dependencies users add -
  * their classes are already on the user class path, along with ours.
  */
trait VendoredDependencies extends JavaModule {
  def vendoredDependencies: T[Seq[String]]
}
