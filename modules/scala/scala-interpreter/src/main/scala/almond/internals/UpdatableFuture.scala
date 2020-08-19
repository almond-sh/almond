package almond.internals

import almond.api.JupyterApi
import ammonite.repl.api.ReplAPI

import scala.concurrent.{ExecutionContext, Future}

object UpdatableFuture {

  def setup(
    replApi: ReplAPI,
    jupyterApi: JupyterApi,
    ec: ExecutionContext
  ): Unit = {
    import jupyterApi.updatableResults._
    val previous = replApi.pprinter.live()
    replApi.pprinter.bind {
      val p = previous()
      p.copy(
        additionalHandlers = p.additionalHandlers.orElse {
          case f: Future[_] =>
            implicit val ec0 = ec

            val messageColor = Some(p.colorLiteral)
              .filter(_ == fansi.Attrs.Empty)
              .getOrElse(fansi.Color.LightGray)

            val value = updatable(messageColor("[running]").render)
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

}
