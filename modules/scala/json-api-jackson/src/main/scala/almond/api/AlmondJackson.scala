package almond.api

import almond.interpreter.api.{DisplayData, OutputHandler}
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.{ClassTagExtensions, DefaultScalaModule}

/** Various utilities to extend the Almond API with the help of the Jackson library */
object AlmondJackson {

  private var defaultMapper0: JsonMapper = createDefaultMapper()

  /** Creates a new instance of the default Jackson mapper used to serialize classes */
  def createDefaultMapper(): JsonMapper =
    JsonMapper.builder().addModule(DefaultScalaModule).build() :: ClassTagExtensions

  /** Sets the default Jackson mapper to be used to serialize classes
    *
    * This mapper is used to serialize classes passed to the various extension methods of
    * `AlmondJackson.Extensions`
    */
  def setDefaultMapper(mapper: JsonMapper): Unit =
    synchronized {
      defaultMapper0 = mapper
    }

  /** The default mapper used by the extension methods of `AlmondJackson.Extensions` */
  def defaultMapper(): JsonMapper = {

    if (defaultMapper0 == null)
      synchronized {
        if (defaultMapper0 == null)
          defaultMapper0 = createDefaultMapper()
      }

    defaultMapper0
  }

  /** Import the content of this object to get Jackson-based extension methods for Almond APIs */
  object Extensions {

    /** Extension methods for `DisplayData` */
    implicit class AlmondJacksonDisplayDataExtensions(private val data: DisplayData) {

      /** Adds JSON value to the `DisplayData`
        *
        * @param mimeType
        *   MIME type of the passed value
        * @param value
        *   arbitrary class instance converted to JSON with Jackson
        * @return
        *   A new `DisplayData` instance with the passed value added to it
        */
      def addJson(mimeType: String, value: Object): DisplayData =
        addJson(mimeType, value, defaultMapper())

      /** Adds JSON value to the `DisplayData`
        *
        * @param mimeType
        *   MIME type of the passed value
        * @param value
        *   arbitrary class instance converted to JSON with Jackson
        * @param mapper
        *   Jackson mapper to use to serialize `value`
        * @return
        *   A new `DisplayData` instance with the passed value added to it
        */
      def addJson(mimeType: String, value: Object, mapper: JsonMapper): DisplayData =
        data.addStringifiedJson(
          mimeType,
          mapper.writeValueAsString(value)
        )
    }

    /** Extension methods for `OutputHandler` */
    implicit class AlmondJacksonOutputHandlerExtensions(private val handler: OutputHandler) {

      /** Pushes the passed map-like object to the front-end
        *
        * Once serialized to JSON by Jackson, the passed `data` must be a JSON object with MIME
        * types as keys and either strings or JSON objects as values
        *
        * @param data
        *   map-like object, like a Java or a Scala Map
        */
      def displayDataObject(data: Object): Unit =
        displayDataObject(data, defaultMapper())

      /** Pushes the passed map-like object to the front-end
        *
        * Once serialized to JSON by Jackson, the passed `data` must be a JSON object with MIME
        * types as keys and either strings or JSON objects as values
        *
        * @param data
        *   map-like object, like a Java or a Scala Map
        */
      def displayDataObject(data: Object, mapper: JsonMapper): Unit = {
        val node = mapper.valueToTree[JsonNode](data)
        val map  = mapper.convertValue(node, classOf[Map[String, Any]]).map {
          case (k, v) =>
            (k, DisplayData.Value.Json(mapper.writeValueAsString(v)))
        }
        handler.display(DisplayData().withDetailedData(map))
      }

      /** Displays data from an arbitrary class instance to be serialized as JSON
        *
        * @param mimeType
        *   MIME type of the resulting data
        * @param data
        *   arbitrary class instance to be serialized as JSON using Jackson
        */
      def displayData(mimeType: String, data: Object): Unit =
        displayData(mimeType, data, defaultMapper())

      /** Displays data from an arbitrary class instance to be serialized as JSON
        *
        * @param mimeType
        *   MIME type of the resulting data
        * @param data
        *   arbitrary class to be serialized as JSON using Jackson
        * @param mapper
        *   Jackson mapper to use to serialize `data`
        */
      def displayData(mimeType: String, data: Object, mapper: JsonMapper): Unit = {
        val map = Map(mimeType -> DisplayData.Value.Json(mapper.writeValueAsString(data)))
        handler.display(DisplayData().withDetailedData(map))
      }

      /** Adds a payload object to the execute response of the currently running cell
        *
        * @param payload
        *   arbitrary class instance to be serialized as JSON using Jackson and used as payload
        */
      def addPayloadObject(payload: Object): Unit =
        addPayloadObject(payload, defaultMapper())

      /** Adds a payload object to the execute response of the currently running cell
        *
        * @param payload
        *   arbitrary class instance to be serialized as JSON using Jackson and used as payload
        * @param mapper
        *   Jackson mapper to use to serialize `payload`
        */
      def addPayloadObject(payload: Object, mapper: JsonMapper): Unit = {
        val payloadAsString = mapper.writeValueAsString(payload)
        handler.addPayload(payloadAsString)
      }
    }
  }
}
