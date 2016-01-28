package jupyter

import ammonite.shell.is210

package object scala {

  // FIXME Duplicate of things in ammonium tests
  val wrapper =
    if (is210)
      "INSTANCE.$ref$"
    else
      ""

}
