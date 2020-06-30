package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core._
import utest._

object ProtocolTests extends TestSuite {

  val tests = Tests {
    test("history_request") {
      test("simple") {
        val input = """{"raw":true,"output":false,"hist_access_type":"tail","n":1000}"""
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
  }

}
