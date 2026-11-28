package almond.debugger

import utest._

object Murmur2Tests extends TestSuite {

  // expected values computed with ipykernel.compiler.murmur2_x86, that matches the hash JupyterLab
  // computes to name the files of cells
  val expected = Seq(
    ""                      -> 3990065800L,
    "a"                     -> 2167009006L,
    "ab"                    -> 2805137849L,
    "abc"                   -> 3350977461L,
    "abcd"                  -> 804720481L,
    "val x = 1\nprintln(x)" -> 497228180L,
    "é ü 😀 code"           -> 3137452790L
  )

  val tests = Tests {
    test("same as ipykernel") {
      for ((input, hash) <- expected)
        assert(Murmur2.hash(input, Murmur2.defaultSeed) == hash)
    }
  }
}
