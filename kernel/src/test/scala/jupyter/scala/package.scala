package jupyter

import ammonite.shell.is210

package object scala {

  // FIXME Duplicate of things in ammonium tests
  val wrapper: (Int, Int) => String =
    if (is210)
      (_, _) => "INSTANCE.$ref$"
    else
      (_, _) => ""

}
