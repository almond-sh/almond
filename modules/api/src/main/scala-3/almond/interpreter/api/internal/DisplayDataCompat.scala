package almond.interpreter.api.internal

import almond.interpreter.api.DisplayData
import scala.deriving.Mirror

trait DisplayDataCompat {
  protected def data0: Map[String, String]
  protected def metadata0: Map[String, String]
  def idOpt: Option[String]

  @deprecated("Prefer using detailedData instead", "0.14.2")
  def _1: Map[String, String] =
    data0
  @deprecated("Prefer using detailedMetadata instead", "0.14.2")
  def _2: Map[String, String] =
    metadata0
  @deprecated("Prefer using idOpt instead", "0.14.2")
  def _3: Option[String] =
    idOpt
}

object DisplayDataCompat {
  trait Companion extends Mirror.Product {
    @deprecated("Binary-compatibility stub", "0.14.2")
    def unapply(displayData: DisplayData): DisplayData =
      displayData

    type MirroredMonoType = DisplayData

    protected def withData0(displayData: DisplayData, data: Map[String, String]): DisplayData
    protected def withMetadata0(
      displayData: DisplayData,
      metadata: Map[String, String]
    ): DisplayData
    @deprecated("Prefer using the with* methods", "0.14.2")
    def fromProduct(product: Product): DisplayData = {
      var d = DisplayData()
      d = withData0(d, product.productElement(0).asInstanceOf[Map[String, String]])
      d = withMetadata0(d, product.productElement(1).asInstanceOf[Map[String, String]])
      d = d.withId(product.productElement(2).asInstanceOf[Option[String]])
      d
    }
  }
}
