package almond.internals

import ammonite.interp.CompilerLifecycleManager
import ammonite.runtime.{Frame, Storage}

object AmmCompat {

  type History = ammonite.repl.api.History
  type FrontEnd = ammonite.repl.api.FrontEnd
  type ReplAPI = ammonite.repl.api.ReplAPI
  type ReplLoad = ammonite.repl.api.ReplLoad

  class CustomCompilerLifecycleManager(
    storage: Storage,
    headFrame: => Frame,
    dependencyCompleteOpt: => Option[String => (Int, Seq[String])]
  ) extends CompilerLifecycleManager(storage, headFrame, dependencyCompleteOpt, Set.empty, headFrame.classloader)

}
