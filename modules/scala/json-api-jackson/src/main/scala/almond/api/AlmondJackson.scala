package almond.api

import almond.interpreter.api.{DisplayData, OutputHandler}
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.{ClassTagExtensions, DefaultScalaModule}

object AlmondJackson {

  private var defaultMapper0: JsonMapper = createDefaultMapper()

  def createDefaultMapper(): JsonMapper =
    JsonMapper.builder().addModule(DefaultScalaModule).build() :: ClassTagExtensions

  def setDefaultMapper(mapper: JsonMapper): Unit =
    synchronized {
      defaultMapper0 = mapper
    }

  def defaultMapper(): JsonMapper = {

    if (defaultMapper0 == null)
      synchronized {
        if (defaultMapper0 == null)
          defaultMapper0 = createDefaultMapper()
      }

    defaultMapper0
  }

  object Extensions {
    implicit class AlmondJacksonDisplayDataExtensions(private val data: DisplayData) {
      def addJson(mimeType: String, value: Object): DisplayData =
        addJson(mimeType, value, defaultMapper())
      def addJson(mimeType: String, value: Object, mapper: JsonMapper): DisplayData =
        data.addStringifiedJson(
          mimeType,
          mapper.writeValueAsString(value)
        )
    }

    implicit class AlmondJacksonOutputHandlerExtensions(private val handler: OutputHandler) {
      def displayDataObject(data: Object, mapper: JsonMapper): Unit = {
        val node = mapper.valueToTree[JsonNode](data)
        val map = mapper.convertValue(node, classOf[Map[String, Any]]).map {
          case (k, v) =>
            (k, DisplayData.Value.Json(mapper.writeValueAsString(v)))
        }
        handler.display(DisplayData().withDetailedData(map))
      }

      def displayDataObject(data: Object): Unit =
        displayDataObject(data, defaultMapper())

      def displayData(mimeType: String, data: Object, mapper: JsonMapper): Unit = {
        val map = Map(mimeType -> DisplayData.Value.Json(mapper.writeValueAsString(data)))
        handler.display(DisplayData().withDetailedData(map))
      }

      def displayData(mimeType: String, data: Object): Unit =
        displayData(mimeType, data, defaultMapper())

      def addPayloadObject(payload: Object, mapper: JsonMapper): Unit = {
        val payloadAsString = mapper.writeValueAsString(payload)
        handler.addPayload(payloadAsString)
      }

      def addPayloadObject(payload: Object): Unit =
        addPayloadObject(payload, defaultMapper())
    }
  }
}
