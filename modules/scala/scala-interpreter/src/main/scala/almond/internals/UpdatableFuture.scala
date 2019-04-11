package almond.internals

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
      import jupyterApi.updatableResults._
      val p = replApi.pprinter()
      p.copy(
        additionalHandlers = p.additionalHandlers.orElse {
          case f: Future[_] =>
            implicit val ec0 = ec
            val value = updatable(fansi.Color.LightGray("[running]").render)
            f.onComplete { t =>
              update(
                value,
                replApi.pprinter().tokenize(t).mkString,
                last = true
              )
            }
            pprint.Tree.Literal(value)
        }
      )
    }

}
