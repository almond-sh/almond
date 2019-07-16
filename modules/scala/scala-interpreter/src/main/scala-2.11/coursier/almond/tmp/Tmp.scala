package coursier.almond.tmp

import coursier.Fetch
import coursier.params.ResolutionParams

object Tmp {

  def resolutionParams[F[_]](f: Fetch[F]): ResolutionParams = {
    val m = f.getClass.getDeclaredMethods.find(_.toString.contains("resolveParams")).getOrElse(
      f.getClass.getDeclaredMethod("resolveParams")
    )
    m.setAccessible(true)
    m.invoke(f)
      .asInstanceOf[coursier.Resolve.Params[F]]
      .resolutionParams
  }

}
