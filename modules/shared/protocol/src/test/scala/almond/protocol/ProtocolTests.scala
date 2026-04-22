package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core._
import utest._

object ProtocolTests extends TestSuite {

  val tests = Tests {
    test("kernel_spec") {
      test("env serialized as JSON object not array") {
        // Regression test for https://github.com/almond-sh/almond/issues/1499:
        // env must be a JSON object (dict) not a JSON array, because Jupyter's
        // KernelSpec requires env to be a dict.
        val spec = KernelSpec(
          argv = List("java", "--connection-file", "{connection_file}"),
          display_name = "Scala",
          language = "scala",
          env = Map("COURSIER_REPOSITORIES" -> "repo-dummy")
        )
        val json = writeToString(spec)
        assert(json.contains(""""env":{"COURSIER_REPOSITORIES":"repo-dummy"}"""))
        val decoded = readFromString(json)(KernelSpec.codec)
        assert(decoded == spec)
      }
    }
    test("history_request") {
      test("simple") {
        val input  = """{"raw":true,"output":false,"hist_access_type":"tail","n":1000}"""
        val result = readFromString(input)(History.requestCodec)
        val expected = History.Request(
          output = false,
          raw = true,
          hist_access_type = History.AccessType.Tail,
          n = Some(1000)
        )
        assert(result == expected)
      }
    }

    test("complete_reply") {
      test("preserve matches field in json reply even if no match found") {
        val reply = Complete.Reply(
          matches = Nil,
          cursor_start = 0,
          cursor_end = 7,
          metadata = RawJson.emptyObj
        )
        val json = writeToString(reply)
        assert(json.contains(""""matches":[]"""))
      }
    }
  }

}
