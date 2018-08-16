package almond.interpreter

import argonaut.Json

final case class Inspection(data: Map[String, Json], metadata: Map[String, Json] = Map.empty)
