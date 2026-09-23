package almond.interpreter.api.internal

import almond.interpreter.api.DisplayData

trait DisplayDataCompat

object DisplayDataCompat {
  trait Companion {
    protected def data0(data: DisplayData): Map[String, String]
    protected def metadata0(data: DisplayData): Map[String, String]
    @deprecated("Binary-compatibility stub", "0.14.2")
    def unapply(displayData: DisplayData)
      : Option[(Map[String, String], Map[String, String], Option[String])] =
      Some((data0(displayData), metadata0(displayData), displayData.idOpt))
  }
}
