package almond.widgets

import java.nio.charset.StandardCharsets
import java.util.Arrays

import scala.collection.immutable.ListMap

/** JSON values, as found in the state of widgets
  *
  * On top of the usual JSON values, these can contain binary data ([[JsonValue.Bytes]]). These are
  * sent and received as binary buffers along with the JSON messages of widgets, rather than in the
  * JSON content of the messages.
  */
sealed abstract class JsonValue extends Product with Serializable {
  final def render: String =
    JsonValue.render(this)
}

object JsonValue {

  case object Null extends JsonValue

  final case class Bool(value: Boolean) extends JsonValue

  final case class Num(value: Double) extends JsonValue

  final case class Str(value: String) extends JsonValue

  final case class Arr(values: Seq[JsonValue]) extends JsonValue
  object Arr {
    def apply(values: JsonValue*)(implicit dummy: DummyImplicit): Arr =
      Arr(values.toVector)
  }

  final case class Obj(fields: ListMap[String, JsonValue]) extends JsonValue {
    def get(key: String): Option[JsonValue] =
      fields.get(key)
    def +(field: (String, JsonValue)): Obj =
      Obj(fields + field)
  }
  object Obj {
    def apply(fields: (String, JsonValue)*): Obj =
      Obj(ListMap(fields: _*))
    val empty: Obj = Obj(ListMap.empty[String, JsonValue])
  }

  /** Binary data, sent as binary buffers alongside JSON messages */
  final case class Bytes(value: Array[Byte]) extends JsonValue {
    override def equals(obj: Any): Boolean =
      obj match {
        case other: Bytes => Arrays.equals(value, other.value)
        case _            => false
      }
    override def hashCode(): Int =
      "Bytes".## + 37 * Arrays.hashCode(value)
    override def toString: String =
      s"Bytes(${value.length} bytes)"
  }

  /** Renders a value as JSON
    *
    * [[Bytes]] values can't be represented in JSON. These are rendered as `null`, and should rather
    * be extracted beforehand.
    */
  def render(value: JsonValue): String = {
    val b = new StringBuilder
    def write(value: JsonValue): Unit =
      value match {
        case Null        => b.append("null")
        case Bool(value) => b.append(if (value) "true" else "false")
        case Num(value)  => b.append(renderNumber(value))
        case Str(value)  => writeString(value, b)
        case _: Bytes    => b.append("null")
        case Arr(values) =>
          b.append('[')
          var first = true
          for (value <- values) {
            if (first) first = false
            else b.append(',')
            write(value)
          }
          b.append(']')
        case Obj(fields) =>
          b.append('{')
          var first = true
          for ((key, value) <- fields) {
            if (first) first = false
            else b.append(',')
            writeString(key, b)
            b.append(':')
            write(value)
          }
          b.append('}')
      }
    write(value)
    b.result()
  }

  private def renderNumber(value: Double): String =
    if (value.isNaN || value.isInfinity) "null"
    else if (value == math.rint(value) && math.abs(value) < 1e15) value.toLong.toString
    else value.toString

  private def writeString(s: String, b: StringBuilder): Unit = {
    b.append('"')
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      c match {
        case '"'  => b.append("\\\"")
        case '\\' => b.append("\\\\")
        case '\n' => b.append("\\n")
        case '\r' => b.append("\\r")
        case '\t' => b.append("\\t")
        case '\b' => b.append("\\b")
        case '\f' => b.append("\\f")
        // escaping the line and paragraph separators, that some JavaScript parsers reject
        case c if c < ' ' || c.toInt == 0x2028 || c.toInt == 0x2029 =>
          b.append("\\u")
          val hex = Integer.toHexString(c.toInt)
          b.append("0" * (4 - hex.length))
          b.append(hex)
        case c => b.append(c)
      }
      i += 1
    }
    b.append('"')
  }

  final class ParseException(message: String) extends Exception(message)

  def parse(bytes: Array[Byte]): JsonValue =
    parse(new String(bytes, StandardCharsets.UTF_8))

  /** Parses JSON
    *
    * @throws ParseException
    *   if the input isn't valid JSON
    */
  def parse(input: String): JsonValue = {
    val parser = new Parser(input)
    val value  = parser.value()
    parser.skipWhitespace()
    if (!parser.atEnd)
      parser.fail("Unexpected trailing content")
    value
  }

  private final class Parser(input: String) {
    private var pos = 0

    def atEnd: Boolean = pos >= input.length

    def fail(message: String): Nothing =
      throw new ParseException(s"$message at offset $pos")

    def skipWhitespace(): Unit =
      while (!atEnd && " \t\n\r".indexOf(input.charAt(pos).toInt) >= 0)
        pos += 1

    private def expect(s: String): Unit =
      if (input.startsWith(s, pos)) pos += s.length
      else fail(s"Expected '$s'")

    def value(): JsonValue = {
      skipWhitespace()
      if (atEnd) fail("Unexpected end of input")
      input.charAt(pos) match {
        case 'n' => expect("null"); Null
        case 't' => expect("true"); Bool(true)
        case 'f' => expect("false"); Bool(false)
        case '"' => Str(string())
        case '[' =>
          pos += 1
          val values = Vector.newBuilder[JsonValue]
          skipWhitespace()
          if (!atEnd && input.charAt(pos) == ']') pos += 1
          else {
            var done = false
            while (!done) {
              values += value()
              skipWhitespace()
              if (atEnd) fail("Unexpected end of input")
              input.charAt(pos) match {
                case ',' => pos += 1
                case ']' => pos += 1; done = true
                case _   => fail("Expected ',' or ']'")
              }
            }
          }
          Arr(values.result())
        case '{' =>
          pos += 1
          var fields = ListMap.empty[String, JsonValue]
          skipWhitespace()
          if (!atEnd && input.charAt(pos) == '}') pos += 1
          else {
            var done = false
            while (!done) {
              skipWhitespace()
              if (atEnd || input.charAt(pos) != '"') fail("Expected a string key")
              val key = string()
              skipWhitespace()
              expect(":")
              fields = fields + (key -> value())
              skipWhitespace()
              if (atEnd) fail("Unexpected end of input")
              input.charAt(pos) match {
                case ',' => pos += 1
                case '}' => pos += 1; done = true
                case _   => fail("Expected ',' or '}'")
              }
            }
          }
          Obj(fields)
        case c if c == '-' || (c >= '0' && c <= '9') => number()
        case c                                       => fail(s"Unexpected character '$c'")
      }
    }

    private def number(): JsonValue = {
      val start = pos
      while (!atEnd && "+-0123456789.eE".indexOf(input.charAt(pos).toInt) >= 0)
        pos += 1
      val s = input.substring(start, pos)
      try Num(s.toDouble)
      catch {
        case _: NumberFormatException =>
          pos = start
          fail(s"Invalid number '$s'")
      }
    }

    private def string(): String = {
      pos += 1 // opening quote
      val b    = new StringBuilder
      var done = false
      while (!done) {
        if (atEnd) fail("Unterminated string")
        val c = input.charAt(pos)
        pos += 1
        c match {
          case '"' => done = true
          case '\\' =>
            if (atEnd) fail("Unterminated string")
            val e = input.charAt(pos)
            pos += 1
            e match {
              case '"'  => b.append('"')
              case '\\' => b.append('\\')
              case '/'  => b.append('/')
              case 'b'  => b.append('\b')
              case 'f'  => b.append('\f')
              case 'n'  => b.append('\n')
              case 'r'  => b.append('\r')
              case 't'  => b.append('\t')
              case 'u' =>
                if (pos + 4 > input.length) fail("Invalid unicode escape")
                val hex = input.substring(pos, pos + 4)
                val code =
                  try Integer.parseInt(hex, 16)
                  catch {
                    case _: NumberFormatException => fail(s"Invalid unicode escape '$hex'")
                  }
                b.append(code.toChar)
                pos += 4
              case other => fail(s"Invalid escape character '$other'")
            }
          case c => b.append(c)
        }
      }
      b.result()
    }
  }
}
