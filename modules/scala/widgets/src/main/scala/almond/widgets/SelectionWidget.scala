package almond.widgets

/** Widgets letting users pick one or several values among options
  *
  * Options can be any values. Only their labels and the selected indices are sent to the front-end.
  * Labels are the `toString` of the options, unless custom labels are passed.
  *
  * @tparam T
  *   type of the options
  * @tparam F
  *   shape of the selection: a single optional value (`Option`), a single value
  *   ([[SelectionWidget.Id]]), several values (`Seq`), or a range ([[SelectionWidget.Pair]])
  */
trait SelectionWidget[T, F[_]] extends ValueWidget[F[T]] {

  /** The indices of the selected options */
  def index: StateProp[F[Int]]

  protected def optionsLabels: StateProp[Seq[String]]

  protected def mapSelection[A, B](selection: F[A])(f: A => B): F[B]

  /** The selection, upon creation, or when options change */
  protected def initialIndex(optionCount: Int): F[Int]

  @volatile private var options0: Vector[T] = Vector.empty

  /** The options users can select */
  final def options: Seq[T] =
    options0

  /** The labels of the options, as shown to users */
  final def labels: Seq[String] =
    optionsLabels()

  /** Changes the options users can select
    *
    * This resets the selection (selects the first option, or nothing).
    *
    * @param labels
    *   labels of the options, defaults to the `toString` of the options if empty
    */
  final def setOptions(options: Seq[T], labels: Seq[String] = Nil): Unit = {
    require(
      labels.isEmpty || labels.length == options.length,
      s"Got ${labels.length} labels for ${options.length} options"
    )
    holdSync {
      options0 = options.toVector
      optionsLabels() = if (labels.isEmpty) options.map(String.valueOf(_)) else labels
      index() = initialIndex(options.length)
    }
  }

  private def indexOf(value: T): Int = {
    val idx = options0.indexOf(value)
    if (idx < 0)
      throw new NoSuchElementException(s"$value is not one of the options of $this")
    idx
  }

  private def indexOfLabel(label: String): Int = {
    val idx = optionsLabels().indexOf(label)
    if (idx < 0)
      throw new NoSuchElementException(s"$label is not one of the labels of $this")
    idx
  }

  /** The selected values */
  lazy val value: DerivedProp[F[T]] =
    derivedProp(index, optionsLabels) {
      val options = options0
      mapSelection(index())(options(_))
    } { value =>
      index() = mapSelection(value)(indexOf)
    }

  /** The labels of the selected values */
  lazy val label: DerivedProp[F[String]] =
    derivedProp(index, optionsLabels) {
      val labels = optionsLabels()
      mapSelection(index())(labels(_))
    } { label =>
      index() = mapSelection(label)(indexOfLabel)
    }
}

object SelectionWidget {
  type Id[A]   = A
  type Pair[A] = (A, A)
}

/** Widgets letting users pick at most one value (drop-downs, radio buttons, …) */
trait SingleSelectionWidget[T] extends SelectionWidget[T, Option] {
  protected def mapSelection[A, B](selection: Option[A])(f: A => B): Option[B] =
    selection.map(f)
  protected def initialIndex(optionCount: Int): Option[Int] =
    if (optionCount > 0) Some(0) else None

  private[widgets] final def initSelection(
    options: Seq[T],
    labels: Seq[String],
    value: Option[T]
  ): Unit = {
    setOptions(options, labels)
    for (v <- value)
      this.value() = Some(v)
  }
}

/** Selection sliders, that always have a value selected */
trait SliderSelectionWidget[T] extends SelectionWidget[T, SelectionWidget.Id] {
  protected def mapSelection[A, B](selection: A)(f: A => B): B =
    f(selection)
  protected def initialIndex(optionCount: Int): Int =
    0

  private[widgets] final def initSelection(options: Seq[T], labels: Seq[String], value: Option[T])
    : Unit = {
    setOptions(options, labels)
    for (v <- value)
      this.value() = v
  }
}

/** Widgets letting users pick several values */
trait MultipleSelectionWidget[T] extends SelectionWidget[T, Seq] {
  protected def mapSelection[A, B](selection: Seq[A])(f: A => B): Seq[B] =
    selection.map(f)
  protected def initialIndex(optionCount: Int): Seq[Int] =
    Nil

  private[widgets] final def initSelection(
    options: Seq[T],
    labels: Seq[String],
    value: Seq[T]
  ): Unit = {
    setOptions(options, labels)
    if (value.nonEmpty)
      this.value() = value
  }
}

/** Widgets letting users pick a range of values */
trait RangeSelectionWidget[T] extends SelectionWidget[T, SelectionWidget.Pair] {
  protected def mapSelection[A, B](selection: (A, A))(f: A => B): (B, B) =
    (f(selection._1), f(selection._2))
  protected def initialIndex(optionCount: Int): (Int, Int) =
    (0, 0)

  private[widgets] final def initSelection(
    options: Seq[T],
    labels: Seq[String],
    value: Option[(T, T)]
  ): Unit = {
    setOptions(options, labels)
    for (v <- value)
      this.value() = v
  }
}
