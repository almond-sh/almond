package almond.widgets

import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, ZoneOffset}

import scala.reflect.ClassTag

/** Converts values to and from their representation in the state of widgets */
trait Codec[T] { self =>
  def encode(value: T): Encoded

  /** @throws IllegalArgumentException
    *   if `value` can't be decoded as a `T`
    */
  def decode(value: Encoded): T

  /** The widgets referenced by a value, that need to be opened before it's sent to the front-end
    */
  def references(value: T): Seq[Widget] = Nil

  def bimap[U](to: T => U, from: U => T): Codec[U] =
    new Codec[U] {
      def encode(value: U)              = self.encode(from(value))
      def decode(value: Encoded)        = to(self.decode(value))
      override def references(value: U) = self.references(from(value))
    }
}

object Codec {

  def apply[T](implicit codec: Codec[T]): Codec[T] = codec

  private def unexpected(expected: String, json: ujson.Value): Nothing =
    throw new IllegalArgumentException(s"Expected $expected, got ${json.render()}")

  /** Codecs of values that are plain JSON, without binary data */
  private def fromJson[T](enc: T => ujson.Value)(dec: ujson.Value => T): Codec[T] =
    new Codec[T] {
      def encode(value: T)       = Encoded(enc(value))
      def decode(value: Encoded) = dec(value.json)
    }

  /** Raw values, with their binary data */
  implicit val encoded: Codec[Encoded] =
    new Codec[Encoded] {
      def encode(value: Encoded) = value
      def decode(value: Encoded) = value
    }

  /** JSON values (binary data are `null` in these, use [[encoded]] to get them) */
  implicit val json: Codec[ujson.Value] =
    // copying values, so that changing them doesn't change the state of widgets
    fromJson[ujson.Value](ujson.copy(_))(ujson.copy(_))

  implicit val boolean: Codec[Boolean] =
    fromJson[Boolean](ujson.Bool(_)) {
      case ujson.Bool(value) => value
      case json              => unexpected("a boolean", json)
    }

  implicit val double: Codec[Double] =
    fromJson[Double](ujson.Num(_)) {
      case ujson.Num(value) => value
      // what Python sends for these
      case ujson.Str("NaN")       => Double.NaN
      case ujson.Str("Infinity")  => Double.PositiveInfinity
      case ujson.Str("-Infinity") => Double.NegativeInfinity
      case json                   => unexpected("a number", json)
    }

  implicit val int: Codec[Int] =
    fromJson[Int](ujson.Num(_)) {
      case ujson.Num(value) => math.round(value).toInt
      case json             => unexpected("a number", json)
    }

  implicit val long: Codec[Long] =
    fromJson[Long](value => ujson.Num(value.toDouble)) {
      case ujson.Num(value) => math.round(value)
      case json             => unexpected("a number", json)
    }

  implicit val string: Codec[String] =
    fromJson[String](ujson.Str(_)) {
      case ujson.Str(value) => value
      case json             => unexpected("a string", json)
    }

  /** Strings that can be `null` in the widget state, where `null` and the empty string mean the
    * same thing (no value), like for CSS properties
    */
  val optionalString: Codec[String] =
    fromJson[String](value =>
      if (value == null || value.isEmpty) ujson.Null else ujson.Str(value)
    ) {
      case ujson.Null       => ""
      case ujson.Str(value) => value
      case json             => unexpected("a string or null", json)
    }

  implicit val bytes: Codec[Array[Byte]] =
    new Codec[Array[Byte]] {
      def encode(value: Array[Byte]) = Encoded.bytes(value)
      def decode(value: Encoded) =
        value.bytes.getOrElse {
          value.json match {
            case ujson.Null => Array.emptyByteArray
            case json       => unexpected("binary data", json)
          }
        }
    }

  implicit def option[T](implicit codec: Codec[T]): Codec[Option[T]] =
    new Codec[Option[T]] {
      def encode(value: Option[T]) =
        value.fold(Encoded.Null)(codec.encode)
      def decode(value: Encoded) =
        if (value.json.isNull && value.buffers.isEmpty) None
        else Some(codec.decode(value))
      override def references(value: Option[T]) =
        value.toSeq.flatMap(codec.references)
    }

  implicit def seq[T](implicit codec: Codec[T]): Codec[Seq[T]] =
    new Codec[Seq[T]] {
      def encode(value: Seq[T]) =
        Encoded.arr(value.map(codec.encode))
      def decode(value: Encoded) =
        value.json match {
          case ujson.Arr(values) =>
            values.indices.toVector.map(idx => codec.decode(value.element(idx)))
          case ujson.Null => Nil
          case json       => unexpected("an array", json)
        }
      override def references(value: Seq[T]) =
        value.flatMap(codec.references)
    }

  implicit def tuple2[A, B](implicit codecA: Codec[A], codecB: Codec[B]): Codec[(A, B)] =
    new Codec[(A, B)] {
      def encode(value: (A, B)) =
        Encoded.arr(Seq(codecA.encode(value._1), codecB.encode(value._2)))
      def decode(value: Encoded) =
        value.json match {
          case ujson.Arr(values) if values.length == 2 =>
            (codecA.decode(value.element(0)), codecB.decode(value.element(1)))
          case json => unexpected("an array of 2 elements", json)
        }
      override def references(value: (A, B)) =
        codecA.references(value._1) ++ codecB.references(value._2)
    }

  private def modelPrefix = "IPY_MODEL_"

  private val resolver = new ThreadLocal[Option[String => Option[Widget]]] {
    override def initialValue(): Option[String => Option[Widget]] = None
  }

  /** Runs `f`, looking up the widgets referenced by the values it decodes with `resolve`
    *
    * Widgets not found by `resolve` are looked up by the resolvers of the enclosing calls, if any,
    * or among the open widgets of the current [[WidgetManager]].
    */
  def withResolver[T](resolve: String => Option[Widget])(f: => T): T = {
    val previous = resolver.get()
    resolver.set(Some(id => resolve(id).orElse(previous.flatMap(_(id)))))
    try f
    finally resolver.set(previous)
  }

  private def resolve(id: String): Option[Widget] = {
    val resolved = resolver.get() match {
      case Some(f) => f(id)
      case None    => None
    }
    resolved.orElse(WidgetManager.currentOpt.flatMap(_.widget(id)))
  }

  /** References to other widgets, sent as `"IPY_MODEL_<id>"` */
  def widget[W <: Widget](implicit classTag: ClassTag[W]): Codec[W] =
    new Codec[W] {
      def encode(value: W) =
        if (value == null) Encoded.Null
        else Encoded(ujson.Str(modelPrefix + value.id))
      def decode(value: Encoded) =
        value.json match {
          case ujson.Null => null.asInstanceOf[W]
          case ujson.Str(ref) if ref.startsWith(modelPrefix) =>
            val id = ref.stripPrefix(modelPrefix)
            resolve(id) match {
              case Some(w: W) => w
              case Some(w) =>
                throw new IllegalArgumentException(
                  s"Widget $id is a ${w.getClass.getSimpleName}, " +
                    s"expected a ${classTag.runtimeClass.getSimpleName}"
                )
              case None =>
                throw new IllegalArgumentException(s"Unknown widget $id")
            }
          case json => unexpected("a widget reference", json)
        }
      override def references(value: W) =
        Option(value).toSeq
    }

  implicit def widgetCodec[W <: Widget: ClassTag]: Codec[W] =
    widget[W]

  // see https://github.com/jupyter-widgets/ipywidgets/blob/8.1.7/python/ipywidgets/ipywidgets/widgets/trait_types.py

  private def obj(json: ujson.Value, what: String): ujson.Obj =
    json match {
      case obj: ujson.Obj => obj
      case _              => unexpected(what, json)
    }
  private def intField(obj: ujson.Obj, key: String): Int =
    obj.value.get(key).map(json => int.decode(Encoded(json))).getOrElse(0)

  /** Dates, sent as `{"year": …, "month": …, "date": …}`, with 0-based months */
  implicit val localDate: Codec[LocalDate] =
    fromJson[LocalDate] { value =>
      ujson.Obj(
        "year"  -> ujson.Num(value.getYear),
        "month" -> ujson.Num(value.getMonthValue - 1),
        "date"  -> ujson.Num(value.getDayOfMonth)
      )
    } { json =>
      val o = obj(json, "a date")
      LocalDate.of(intField(o, "year"), intField(o, "month") + 1, intField(o, "date"))
    }

  /** Times, sent as `{"hours": …, "minutes": …, "seconds": …, "milliseconds": …}` */
  implicit val localTime: Codec[LocalTime] =
    fromJson[LocalTime] { value =>
      ujson.Obj(
        "hours"        -> ujson.Num(value.getHour),
        "minutes"      -> ujson.Num(value.getMinute),
        "seconds"      -> ujson.Num(value.getSecond),
        "milliseconds" -> ujson.Num(value.getNano / 1000000)
      )
    } { json =>
      val o = obj(json, "a time")
      LocalTime.of(
        intField(o, "hours"),
        intField(o, "minutes"),
        intField(o, "seconds"),
        intField(o, "milliseconds") * 1000000
      )
    }

  /** Date-times without time zone (what the `NaiveDatetime` widget handles) */
  implicit val localDateTime: Codec[LocalDateTime] =
    new Codec[LocalDateTime] {
      def encode(value: LocalDateTime) = {
        val date = localDate.encode(value.toLocalDate).json.obj
        val time = localTime.encode(value.toLocalTime).json.obj
        Encoded(ujson.Obj.from(date.toSeq ++ time.toSeq))
      }
      def decode(value: Encoded) =
        LocalDateTime.of(localDate.decode(value), localTime.decode(value))
    }

  /** Instants (what the `Datetime` widget handles), sent as UTC date-times */
  implicit val instant: Codec[Instant] =
    localDateTime.bimap[Instant](
      _.toInstant(ZoneOffset.UTC),
      LocalDateTime.ofInstant(_, ZoneOffset.UTC)
    )

  /** Numbers that can also be `"any"` (sent as `"any"` for `None`) */
  def orAny[T](codec: Codec[T]): Codec[Option[T]] =
    new Codec[Option[T]] {
      def encode(value: Option[T]) =
        value.fold(Encoded(ujson.Str("any")))(codec.encode)
      def decode(value: Encoded) =
        value.json match {
          case ujson.Str("any") => None
          case _                => Some(codec.decode(value))
        }
    }
}
