package almond.internals

import ammonite.interp.CompilerLifecycleManager
import ammonite.runtime.{Frame, Storage}

object AmmCompat {

  type History = ammonite.runtime.History
  type FrontEnd = ammonite.repl.FrontEnd
  type ReplAPI = ammonite.repl.ReplAPI
  type ReplLoad = ammonite.repl.ReplLoad

  class CustomCompilerLifecycleManager(
    storage: Storage,
    headFrame: => Frame,
    dependencyCompleteOpt: => Option[String => (Int, Seq[String])]
  ) extends CompilerLifecycleManager(storage, headFrame, dependencyCompleteOpt)

}
