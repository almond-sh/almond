package almond.interpreter

import almond.channels.{Message => RawMessage}
import almond.protocol.Header
import argonaut.Json
import utest._

object MessageTests extends TestSuite {

  val tests = Tests {
    "metadata" - {
      "empty" - {
        val rawMsg = RawMessage(
          Nil,
          """{"username":"","version":"5.2","session":"66fee418-b43a-42b2-bba9-cc91ffac014a","msg_id":"40fe2409-d5ad-4a5d-a71c-31411eeb2ea5","msg_type":"execute_request","date":"2018-09-06T08:27:35.616295Z"}""",
          "{}",
          "{}",
          """{"silent":false,"store_history":true}"""
        )

        val res = Message.parse[Json](rawMsg)
        val expectedRes = Right(
          Message(
            Header(
              "40fe2409-d5ad-4a5d-a71c-31411eeb2ea5",
              "",
              "66fee418-b43a-42b2-bba9-cc91ffac014a",
              "execute_request",
              Some("5.2")
            ),
            Json.obj(
              "silent" -> Json.jBool(false),
              "store_history" -> Json.jBool(true)
            )
          )
        )

        assert(res == expectedRes)
      }

      "jupyterlab-like" - {
        val rawMsg = RawMessage(
          Nil,
          """{"username":"","version":"5.2","session":"66fee418-b43a-42b2-bba9-cc91ffac014a","msg_id":"40fe2409-d5ad-4a5d-a71c-31411eeb2ea5","msg_type":"execute_request","date":"2018-09-06T08:27:35.616295Z"}""",
          "{}",
          """{"deletedCells":[],"cellId":"7ff41107-c89a-4272-8ea2-d6433f918a6d"}""",
          """{"silent":false,"store_history":true}"""
        )

        val res = Message.parse[Json](rawMsg)
        val expectedRes = Right(
          Message(
            Header(
              "40fe2409-d5ad-4a5d-a71c-31411eeb2ea5",
              "",
              "66fee418-b43a-42b2-bba9-cc91ffac014a",
              "execute_request",
              Some("5.2")
            ),
            Json.obj(
              "silent" -> Json.jBool(false),
              "store_history" -> Json.jBool(true)
            ),
            metadata = Map(
              "deletedCells" -> Json.array(),
              "cellId" -> Json.jString("7ff41107-c89a-4272-8ea2-d6433f918a6d")
            )
          )
        )

        assert(res == expectedRes)
      }
    }
  }

}
