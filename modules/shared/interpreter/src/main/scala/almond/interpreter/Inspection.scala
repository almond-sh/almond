package almond.interpreter

import almond.interpreter.api.DisplayData
import almond.interpreter.util.DisplayDataOps.toDisplayDataOps
import argonaut.Json

final case class Inspection(data: Map[String, Json], metadata: Map[String, Json] = Map.empty)

object Inspection {
  def fromDisplayData(data: DisplayData): Inspection =
    Inspection(data.jsonData, data.jsonMetadata)
}
