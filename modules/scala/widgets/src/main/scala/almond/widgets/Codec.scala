package almond.widgets

import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, ZoneOffset}

import scala.reflect.ClassTag

/** Converts values to and from their representation in the state of widgets */
trait Codec[T] { self =>
  def encode(value: T): JsonValue

  /** @throws IllegalArgumentException
    *   if `json` can't be decoded as a `T`
    */
  def decode(json: JsonValue): T

  /** The widgets referenced by a value, that need to be opened before it's sent to the front-end
    */
  def references(value: T): Seq[Widget] = Nil

  def bimap[U](to: T => U, from: U => T): Codec[U] =
    new Codec[U] {
      def encode(value: U)              = self.encode(from(value))
      def decode(json: JsonValue)       = to(self.decode(json))
      override def references(value: U) = self.references(from(value))
    }
}

object Codec {

  def apply[T](implicit codec: Codec[T]): Codec[T] = codec

  private def unexpected(expected: String, json: JsonValue): Nothing =
    throw new IllegalArgumentException(s"Expected $expected, got ${json.render}")

  implicit val json: Codec[JsonValue] =
    new Codec[JsonValue] {
      def encode(value: JsonValue) = value
      def decode(json: JsonValue)  = json
    }

  implicit val boolean: Codec[Boolean] =
    new Codec[Boolean] {
      def encode(value: Boolean) = JsonValue.Bool(value)
      def decode(json: JsonValue) =
        json match {
          case JsonValue.Bool(value) => value
          case _                     => unexpected("a boolean", json)
        }
    }

  implicit val double: Codec[Double] =
    new Codec[Double] {
      def encode(value: Double) = JsonValue.Num(value)
      def decode(json: JsonValue) =
        json match {
          case JsonValue.Num(value) => value
          // what Python sends for these
          case JsonValue.Str("NaN")       => Double.NaN
          case JsonValue.Str("Infinity")  => Double.PositiveInfinity
          case JsonValue.Str("-Infinity") => Double.NegativeInfinity
          case _                          => unexpected("a number", json)
        }
    }

  implicit val int: Codec[Int] =
    new Codec[Int] {
      def encode(value: Int) = JsonValue.Num(value)
      def decode(json: JsonValue) =
        json match {
          case JsonValue.Num(value) => math.round(value).toInt
          case _                    => unexpected("a number", json)
        }
    }

  implicit val long: Codec[Long] =
    new Codec[Long] {
      def encode(value: Long) = JsonValue.Num(value.toDouble)
      def decode(json: JsonValue) =
        json match {
          case JsonValue.Num(value) => math.round(value)
          case _                    => unexpected("a number", json)
        }
    }

  implicit val string: Codec[String] =
    new Codec[String] {
      def encode(value: String) = JsonValue.Str(value)
      def decode(json: JsonValue) =
        json match {
          case JsonValue.Str(value) => value
          case _                    => unexpected("a string", json)
        }
    }

  /** Strings that can be `null` in the widget state, where `null` and the empty string mean the
    * same thing (no value), like for CSS properties
    */
  val optionalString: Codec[String] =
    new Codec[String] {
      def encode(value: String) =
        if (value == null || value.isEmpty) JsonValue.Null else JsonValue.Str(value)
      def decode(json: JsonValue) =
        json match {
          case JsonValue.Null       => ""
          case JsonValue.Str(value) => value
          case _                    => unexpected("a string or null", json)
        }
    }

  implicit val bytes: Codec[Array[Byte]] =
    new Codec[Array[Byte]] {
      def encode(value: Array[Byte]) = JsonValue.Bytes(value)
      def decode(json: JsonValue) =
        json match {
          case JsonValue.Bytes(value) => value
          case JsonValue.Null         => Array.emptyByteArray
          case _                      => unexpected("binary data", json)
        }
    }

  implicit def option[T](implicit codec: Codec[T]): Codec[Option[T]] =
    new Codec[Option[T]] {
      def encode(value: Option[T]) =
        value.fold[JsonValue](JsonValue.Null)(codec.encode)
      def decode(json: JsonValue) =
        json match {
          case JsonValue.Null => None
          case _              => Some(codec.decode(json))
        }
      override def references(value: Option[T]) =
        value.toSeq.flatMap(codec.references)
    }

  implicit def seq[T](implicit codec: Codec[T]): Codec[Seq[T]] =
    new Codec[Seq[T]] {
      def encode(value: Seq[T]) =
        JsonValue.Arr(value.map(codec.encode).toVector)
      def decode(json: JsonValue) =
        json match {
          case JsonValue.Arr(values) => values.map(codec.decode).toVector
          case JsonValue.Null        => Nil
          case _                     => unexpected("an array", json)
        }
      override def references(value: Seq[T]) =
        value.flatMap(codec.references)
    }

  implicit def tuple2[A, B](implicit codecA: Codec[A], codecB: Codec[B]): Codec[(A, B)] =
    new Codec[(A, B)] {
      def encode(value: (A, B)) =
        JsonValue.Arr(Vector(codecA.encode(value._1), codecB.encode(value._2)))
      def decode(json: JsonValue) =
        json match {
          case JsonValue.Arr(Seq(a, b)) => (codecA.decode(a), codecB.decode(b))
          case _                        => unexpected("an array of 2 elements", json)
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
        if (value == null) JsonValue.Null
        else JsonValue.Str(modelPrefix + value.id)
      def decode(json: JsonValue) =
        json match {
          case JsonValue.Null => null.asInstanceOf[W]
          case JsonValue.Str(ref) if ref.startsWith(modelPrefix) =>
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
          case _ => unexpected("a widget reference", json)
        }
      override def references(value: W) =
        Option(value).toSeq
    }

  implicit def widgetCodec[W <: Widget: ClassTag]: Codec[W] =
    widget[W]

  // see https://github.com/jupyter-widgets/ipywidgets/blob/8.1.7/python/ipywidgets/ipywidgets/widgets/trait_types.py

  private def obj(json: JsonValue, what: String): JsonValue.Obj =
    json match {
      case obj: JsonValue.Obj => obj
      case _                  => unexpected(what, json)
    }
  private def intField(obj: JsonValue.Obj, key: String): Int =
    obj.get(key).map(int.decode).getOrElse(0)

  /** Dates, sent as `{"year": …, "month": …, "date": …}`, with 0-based months */
  implicit val localDate: Codec[LocalDate] =
    new Codec[LocalDate] {
      def encode(value: LocalDate) =
        JsonValue.Obj(
          "year"  -> JsonValue.Num(value.getYear),
          "month" -> JsonValue.Num(value.getMonthValue - 1),
          "date"  -> JsonValue.Num(value.getDayOfMonth)
        )
      def decode(json: JsonValue) = {
        val o = obj(json, "a date")
        LocalDate.of(intField(o, "year"), intField(o, "month") + 1, intField(o, "date"))
      }
    }

  /** Times, sent as `{"hours": …, "minutes": …, "seconds": …, "milliseconds": …}` */
  implicit val localTime: Codec[LocalTime] =
    new Codec[LocalTime] {
      def encode(value: LocalTime) =
        JsonValue.Obj(
          "hours"        -> JsonValue.Num(value.getHour),
          "minutes"      -> JsonValue.Num(value.getMinute),
          "seconds"      -> JsonValue.Num(value.getSecond),
          "milliseconds" -> JsonValue.Num(value.getNano / 1000000)
        )
      def decode(json: JsonValue) = {
        val o = obj(json, "a time")
        LocalTime.of(
          intField(o, "hours"),
          intField(o, "minutes"),
          intField(o, "seconds"),
          intField(o, "milliseconds") * 1000000
        )
      }
    }

  /** Date-times without time zone (what the `NaiveDatetime` widget handles) */
  implicit val localDateTime: Codec[LocalDateTime] =
    new Codec[LocalDateTime] {
      def encode(value: LocalDateTime) = {
        val date = localDate.encode(value.toLocalDate).asInstanceOf[JsonValue.Obj]
        val time = localTime.encode(value.toLocalTime).asInstanceOf[JsonValue.Obj]
        JsonValue.Obj(date.fields ++ time.fields)
      }
      def decode(json: JsonValue) =
        LocalDateTime.of(localDate.decode(json), localTime.decode(json))
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
        value.fold[JsonValue](JsonValue.Str("any"))(codec.encode)
      def decode(json: JsonValue) =
        json match {
          case JsonValue.Str("any") => None
          case _                    => Some(codec.decode(json))
        }
    }
}
