package almond.display

import java.util.concurrent.ConcurrentHashMap

/** How the Scala kernel shows the results of cells, depending on their type
  *
  * By default, the kernel shows both the values of bare expressions, like `res0: Int = 2` for
  * `1 + 1`, and the ones of named definitions, like `n: Int = 2` for `val n = 1 + 1`. Values that
  * have a rich representation ([[Display]] instances, values with a jvm-repr displayer) get
  * displayed via it, rather than printed as text.
  *
  * Libraries can change that for their own types, like
  * {{{
  *   // handles, that aren't worth printing unless they're assigned to a variable
  *   ResultDisplay.register(classOf[Handle], expressions = ResultDisplay.Mode.Hidden)
  *   // widgets, only displayed when they're the result of a bare expression
  *   ResultDisplay.register(classOf[Widget], definitions = ResultDisplay.Mode.Text)
  * }}}
  *
  * Like jvm-repr displayers, modes are looked up from the static type of values, and apply to
  * sub-classes of the class they're registered for.
  */
object ResultDisplay {

  sealed abstract class Mode extends Product with Serializable
  object Mode {

    /** Displays values via their rich representation if they have one, and prints them otherwise */
    case object Default extends Mode

    /** Prints values as text, even if they have a rich representation */
    case object Text extends Mode

    /** Doesn't show values at all */
    case object Hidden extends Mode
  }

  private final case class Modes(expressions: Mode, definitions: Mode)

  private val registered = new ConcurrentHashMap[Class[_], Modes]

  /** Sets how values of `cls`, and of its sub-classes, are shown
    *
    * @param expressions
    *   how values resulting from bare expressions are shown
    * @param definitions
    *   how values of named definitions (`val`, `var`) are shown
    */
  def register(
    cls: Class[_],
    expressions: Mode = Mode.Default,
    definitions: Mode = Mode.Default
  ): Unit =
    registered.put(cls, Modes(expressions, definitions))

  /** Removes what [[register]] set for `cls` */
  def unregister(cls: Class[_]): Unit =
    registered.remove(cls)

  /** How a value whose static type is `cls` is shown
    *
    * Like jvm-repr, looks for what was registered for `cls`, its interfaces, its super-classes and
    * their interfaces, breadth-first, and uses the first registration it finds.
    *
    * @param expression
    *   whether the value results from a bare expression, rather than from a named definition
    */
  def mode(cls: Class[_], expression: Boolean): Mode =
    if (registered.isEmpty) Mode.Default
    else {
      val visited = new java.util.HashSet[Class[_]]
      visited.add(classOf[Object])
      val queue = new java.util.ArrayDeque[Class[_]]
      queue.add(cls)
      var found = Option.empty[Modes]
      while (found.isEmpty && !queue.isEmpty) {
        val c = queue.poll()
        found = Option(registered.get(c))
        for (c0 <- c.getInterfaces.iterator ++ Option(c.getSuperclass).iterator)
          if (visited.add(c0))
            queue.add(c0)
      }
      found match {
        case Some(modes) => if (expression) modes.expressions else modes.definitions
        case None        => Mode.Default
      }
    }
}
