package almond.interpreter

import almond.interpreter.api.DisplayData
import almond.interpreter.util.DisplayDataOps.toDisplayDataOps
import almond.protocol.RawJson

final case class Inspection(data: Map[String, RawJson], metadata: Map[String, RawJson] = Map.empty)

object Inspection {
  def fromDisplayData(data: DisplayData): Inspection =
    Inspection(data.jsonData, data.jsonMetadata)
}
