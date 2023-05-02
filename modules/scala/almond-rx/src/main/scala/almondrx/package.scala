import almond.api.JupyterApi
import ammonite.repl.api.ReplAPI
import rx._

package object almondrx {

  def setup(
    ownerCtx: Ctx.Owner = Ctx.Owner.Unsafe.Unsafe
  )(implicit
    replApi: ReplAPI,
    jupyterApi: JupyterApi
  ): Unit = {

    import jupyterApi.updatableResults._

    implicit val ownerCtx0 = ownerCtx

    // only really needed when the code wrapper passed to ScalaInterpreter is CodeClassWrapper
    replApi.load("import _root_.rx.Ctx.Owner.Unsafe.Unsafe")

    replApi.pprinter() = {
      val p = replApi.pprinter()
      p.copy(
        additionalHandlers = p.additionalHandlers.orElse {
          case f: Rx[_] =>
            val value = updatable(
              replApi.pprinter().tokenize(f.now).mkString
            )
            f.foreach { t =>
              update(
                value,
                replApi.pprinter().tokenize(t).mkString,
                last = false
              )
            }
            pprint.Tree.Literal(value)
        }
      )
    }
  }

}
