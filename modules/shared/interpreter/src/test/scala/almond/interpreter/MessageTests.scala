package almond.interpreter

import almond.channels.{Message => RawMessage}
import almond.interpreter.TestInterpreter.StringBOps
import almond.protocol.{Header, RawJson}
import utest._

object MessageTests extends TestSuite {

  import TestInterpreter._

  val tests = Tests {
    "metadata" - {
      "empty" - {
        val rawMsg = RawMessage(
          Nil,
          """{"username":"","version":"5.2","session":"66fee418-b43a-42b2-bba9-cc91ffac014a","msg_id":"40fe2409-d5ad-4a5d-a71c-31411eeb2ea5","msg_type":"execute_request","date":"2018-09-06T08:27:35.616295Z"}""".bytes,
          "{}".bytes,
          "{}".bytes,
          """{"silent":false,"store_history":true}""".bytes
        )

        val res = Message.parse[RawJson](rawMsg)
        val expectedRes = Right(
          Message(
            Header(
              "40fe2409-d5ad-4a5d-a71c-31411eeb2ea5",
              "",
              "66fee418-b43a-42b2-bba9-cc91ffac014a",
              "execute_request",
              Some("5.2")
            ),
            RawJson("""{"silent":false,"store_history":true}""".bytes)
          )
        )

        assert(res == expectedRes)
      }

      "jupyterlab-like" - {
        val rawMsg = RawMessage(
          Nil,
          """{"username":"","version":"5.2","session":"66fee418-b43a-42b2-bba9-cc91ffac014a","msg_id":"40fe2409-d5ad-4a5d-a71c-31411eeb2ea5","msg_type":"execute_request","date":"2018-09-06T08:27:35.616295Z"}""".bytes,
          "{}".bytes,
          """{"deletedCells":[],"cellId":"7ff41107-c89a-4272-8ea2-d6433f918a6d"}""".bytes,
          """{"silent":false,"store_history":true}""".bytes
        )

        val res = Message.parse[RawJson](rawMsg)

        val expectedRes = Right(
          Message(
            Header(
              "40fe2409-d5ad-4a5d-a71c-31411eeb2ea5",
              "",
              "66fee418-b43a-42b2-bba9-cc91ffac014a",
              "execute_request",
              Some("5.2")
            ),
            RawJson("""{"silent":false,"store_history":true}""".bytes)
          )
        )

        val metadata = res.map(_.metadata)
        val expectedMetadata = Right(
          RawJson("""{"deletedCells":[],"cellId":"7ff41107-c89a-4272-8ea2-d6433f918a6d"}""".bytes)
        )

        assert(res.map(_.clearMetadata) == expectedRes.map(_.clearMetadata))
        assert(metadata == expectedMetadata)
      }
    }
  }

}
