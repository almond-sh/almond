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
    'spark12LocalNb{
      Notebook("../examples/tests/spark-1.2-local.ipynb")
    }
    'spark13LocalNb{
      Notebook("../examples/tests/spark-1.3-local.ipynb")
    }
  }

}
