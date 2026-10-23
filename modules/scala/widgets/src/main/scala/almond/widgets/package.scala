package almond

import almond.display.Display

/** Jupyter widgets, for the almond Scala kernel
  *
  * Scala counterparts of the widgets of [ipywidgets](https://ipywidgets.readthedocs.io) 8.
  * {{{
  *   import almond.widgets._
  *
  *   val slider = IntSlider(value = 5, min = 0, max = 10, description = "x")
  *   slider.value.observe { x => println(s"x = $x") }
  *   slider // displays the slider
  * }}}
  */
package object widgets {

  /** Links two properties in the front-end: changes of one are applied to the other
    *
    * This works even when the kernel is busy, or after it's gone.
    */
  def jslink[T](source: StateProp[T], target: StateProp[T]): Link =
    Link(source = (source.widget, source.name), target = (target.widget, target.name)).open()

  /** Links a property to another one in the front-end: changes of `source` are applied to `target`
    *
    * This works even when the kernel is busy, or after it's gone.
    */
  def jsdlink[T](source: StateProp[T], target: StateProp[T]): DirectionalLink =
    DirectionalLink(source = (source.widget, source.name), target = (target.widget, target.name))
      .open()

  /** Links two properties in the kernel: changes of one are applied to the other
    *
    * `b` gets the value of `a` straightaway.
    */
  def link[T](a: Prop[T], b: Prop[T]): Subscription = {
    b() = a()
    Subscription.combine(
      Seq(
        a.observe(value => b() = value),
        b.observe(value => a() = value)
      )
    )
  }

  /** Links a property to another one in the kernel: changes of `source` are applied to `target`
    *
    * `target` gets the value of `source` straightaway.
    */
  def dlink[T](source: Prop[T], target: Prop[T]): Subscription =
    dlink(source, target, (value: T) => value)

  /** Links a property to another one in the kernel: changes of `source` are applied to `target`,
    * via `transform`
    *
    * `target` gets the value of `source` straightaway.
    */
  def dlink[T, U](source: Prop[T], target: Prop[U], transform: T => U): Subscription = {
    target() = transform(source())
    source.observe(value => target() = transform(value))
  }

  private def interactive(controls: Seq[ValueWidget[_]])(run: () => Any): VBox = {
    val out = Output()
    def update(): Unit = {
      out.clearOutput()
      out.capture {
        run() match {
          case ()               =>
          case display: Display => out.appendDisplay(display)
          case other            => out.appendStdout(String.valueOf(other) + System.lineSeparator())
        }
      }
    }
    for (control <- controls)
      control.value.observe(_ => update())
    update()
    VBox(children = controls :+ out)
  }

  /** Calls `f` with the value of `a` each time it changes, showing its output below `a`
    *
    * The returned box contains `a` and an [[Output]] widget, showing what `f` prints, and the value
    * it returns.
    * {{{
    *   interact(IntSlider(value = 5, max = 20)) { n =>
    *     (1 to n).product
    *   }
    * }}}
    */
  def interact[A](a: ValueWidget[A])(f: A => Any): VBox =
    interactive(Seq(a))(() => f(a.value()))

  /** Calls `f` with the values of `a` and `b` each time one of them changes, showing its output
    * below them
    */
  def interact[A, B](a: ValueWidget[A], b: ValueWidget[B])(f: (A, B) => Any): VBox =
    interactive(Seq(a, b))(() => f(a.value(), b.value()))

  /** Calls `f` with the values of `a`, `b`, and `c` each time one of them changes, showing its
    * output below them
    */
  def interact[A, B, C](
    a: ValueWidget[A],
    b: ValueWidget[B],
    c: ValueWidget[C]
  )(f: (A, B, C) => Any): VBox =
    interactive(Seq(a, b, c))(() => f(a.value(), b.value(), c.value()))

  /** Calls `f` with the values of `a`, `b`, `c`, and `d` each time one of them changes, showing its
    * output below them
    */
  def interact[A, B, C, D](
    a: ValueWidget[A],
    b: ValueWidget[B],
    c: ValueWidget[C],
    d: ValueWidget[D]
  )(f: (A, B, C, D) => Any): VBox =
    interactive(Seq(a, b, c, d))(() => f(a.value(), b.value(), c.value(), d.value()))
}
