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
          env = ActualMap(Map("COURSIER_REPOSITORIES" -> "repo-dummy"))
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

    test("replies have a status") {
      val okStatus = """"status":"ok""""
      test("shutdown_reply") {
        val json = writeToString(Shutdown.Reply(restart = false))(Shutdown.replyCodec)
        assert(json == """{"restart":false,"status":"ok"}""")
      }
      test("interrupt_reply") {
        val json = writeToString(Interrupt.Reply())(Interrupt.replyCodec)
        assert(json == """{"status":"ok"}""")
      }
      test("history_reply") {
        val simple = writeToString[History.Reply](History.Reply.Simple(Nil))(History.replyCodec)
        assert(simple == """{"history":[],"status":"ok"}""")
        val withOutput = writeToString[History.Reply](
          History.Reply.WithOutput(List((1, 2, ("in", "out"))))
        )(History.replyCodec)
        assert(withOutput.contains(okStatus))
      }
      test("comm_info_reply") {
        val json = writeToString(CommInfo.Reply(Map.empty[String, CommInfo.Info]))(
          CommInfo.replyCodec
        )
        assert(json == """{"comms":{},"status":"ok"}""")
      }
      test("connect_reply") {
        val json = writeToString(Connect.Reply(1, 2, 3, 4, 5))(Connect.replyCodec)
        assert(
          json ==
            """{"shell_port":1,"iopub_port":2,"stdin_port":3,"hb_port":4,"control_port":5,"status":"ok"}"""
        )
      }
    }

    test("execute_reply") {
      test("preserve empty user_expressions field in json reply") {
        val reply: Execute.Reply = Execute.Reply.Success(1, Map.empty, Nil)
        val json                 = writeToString(reply)(Execute.replyCodec)
        assert(json.contains(""""user_expressions":{}"""))
        val decoded = readFromString(json)(Execute.replyCodec)
        assert(decoded == reply)
      }
    }
  }

}
