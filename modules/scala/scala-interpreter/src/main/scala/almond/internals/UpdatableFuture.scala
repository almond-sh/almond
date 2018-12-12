package almond.internals

import java.util.UUID

import almond.api.JupyterApi
import ammonite.repl.ReplAPI

import scala.concurrent.{ExecutionContext, Future}

object UpdatableFuture {

  def setup(
    replApi: ReplAPI,
    jupyterApi: JupyterApi,
    ec: ExecutionContext
  ): Unit =
    replApi.pprinter() = {
      val p = replApi.pprinter()
      p.copy(
        additionalHandlers = p.additionalHandlers.orElse {
          case f: Future[_] =>
            implicit val ec0 = ec
            val id = "<future-" + UUID.randomUUID() + ">"
            jupyterApi.updatableResults.addVariable(id, "[running future]")
            f.onComplete { t =>
              jupyterApi.updatableResults.updateVariable(
                id,
                replApi.pprinter().tokenize(t).mkString,
                last = true
              )
            }
            pprint.Tree.Literal(id)
        }
      )
    }

}
