package almond.internals

/** Removes the prefixes of the wrappers of the code of cells from the types printed in inspections
  */
object CellWrappers {

  // The prefixes of the types defined in the current cell (wrapped in InspectWrapper by
  // ScalaInterpreterInspections), or in previous ones (wrapped by Ammonite, and printed like
  // `instance.Foo` or `ammonite.$sess.cmd2.Helper.Foo`, or `ammonite.$sess.cmd2$.Helper$.Foo` for
  // the cells that only define objects, that Ammonite wraps in objects rather than classes). This
  // is a heuristic on printed types: a bare `instance.` prefix is only removed before a
  // capitalized type name, so that a user value named `instance` is at worst printed without its
  // prefix in path-dependent types.
  private val WrapperPrefix =
    """\bInspectWrapper\$?\.|\bammonite\.\$sess\.cmd\d+\$?\.(?:Helper\$?\.|instance\.)?|(?<![\w$.])instance\.(?=[A-Z])""".r

  def withoutWrappers(s: String): String =
    WrapperPrefix.replaceAllIn(s, "")
}
