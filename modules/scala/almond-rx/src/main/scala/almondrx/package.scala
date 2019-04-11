
import java.util.UUID

import almond.api.JupyterApi
import ammonite.repl.ReplAPI
import rx._

package object almondrx {

  def setup(
   ownerCtx: Ctx.Owner = Ctx.Owner.Unsafe.Unsafe
  )(implicit
    replApi: ReplAPI,
    jupyterApi: JupyterApi
  ): Unit = {

    implicit val ownerCtx0 = ownerCtx

    // only really needed when the code wrapper passed to ScalaInterpreter is CodeClassWrapper
    replApi.load("import _root_.rx.Ctx.Owner.Unsafe.Unsafe")

    replApi.pprinter() = {
      val p = replApi.pprinter()
      p.copy(
        additionalHandlers = p.additionalHandlers.orElse {
          case f: Rx[_] =>
            val id = "<rx-" + UUID.randomUUID() + ">"
            val current = replApi.pprinter().tokenize(f.now).mkString
            jupyterApi.updatableResults.addVariable(id, current)
            f.foreach { t =>
              jupyterApi.updatableResults.updateVariable(
                id,
                replApi.pprinter().tokenize(t).mkString,
                last = false
              )
            }
            pprint.Tree.Literal(id)
        }
      )
    }
  }

}
