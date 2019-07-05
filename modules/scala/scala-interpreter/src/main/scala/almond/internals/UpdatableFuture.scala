package almond.internals

import almond.api.JupyterApi
import almond.internals.AmmCompat._

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
