package jupyter.scala

import ammonite.shell.classwrapper.LocalSparkTests

object LocalSpark12Tests extends LocalSparkTests(ScalaInterpreterChecker(), (1, 2), requisiteResult = "")
object LocalSpark13Tests extends LocalSparkTests(ScalaInterpreterChecker(), (1, 3), requisiteResult = "")
