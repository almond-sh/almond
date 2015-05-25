package jupyter.scala.nbtest

import utest._

object NbTest extends TestSuite {

  val tests = TestSuite{
    'csvNb{
      Notebook("../examples/libraries/PureCSV.ipynb")
    }
    'pspStdNb{
      Notebook("../examples/libraries/psp-std.ipynb")
    }
  }

}
