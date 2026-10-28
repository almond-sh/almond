package almond.widgets

import java.util.Arrays

/** A value of the state of a widget, as sent to and received from the front-end
  *
  * On top of JSON, the state of widgets can contain binary data. Binary values are sent as binary
  * buffers alongside the JSON content of messages, along with their paths in it. Here, binary
  * values are `null` in [[json]], and are kept in [[buffers]], along with their paths in [[json]].
  *
  * Treat [[json]] as immutable: copy it with `ujson.copy` before changing it.
  */
final case class Encoded(json: ujson.Value, buffers: Seq[Encoded.Buffer] = Nil) {

  /** The value of field `key`, if this is an object (`null` if it doesn't have that field) */
  def field(key: String): Encoded = {
    val value = json.objOpt.flatMap(_.get(key)).getOrElse(ujson.Null)
    Encoded(value, buffers.flatMap(_.under(Encoded.PathElem.Key(key))))
  }

  /** The value at index `idx`, if this is an array (`null` if it doesn't have that index) */
  def element(idx: Int): Encoded = {
    val value = json.arrOpt.flatMap(_.lift(idx)).getOrElse(ujson.Null)
    Encoded(value, buffers.flatMap(_.under(Encoded.PathElem.Index(idx))))
  }

  /** The binary value at the root of this value, if there's one */
  def bytes: Option[Array[Byte]] =
    buffers.collectFirst { case b if b.path.isEmpty => b.data }

  /** The names of the fields of this value, if it's an object, including those of binary values */
  def fieldNames: Seq[String] = {
    val fromJson = json.objOpt.toSeq.flatMap(_.keys)
    val fromBuffers = buffers.flatMap(_.path.headOption).collect { case Encoded.PathElem.Key(k) =>
      k
    }
    (fromJson ++ fromBuffers).distinct
  }

  override def toString: String =
    if (buffers.isEmpty) s"Encoded(${json.render()})"
    else s"Encoded(${json.render()}, ${buffers.mkString(", ")})"
}

object Encoded {

  /** A step of the path of a binary value: a field of an object, or an index of an array */
  sealed abstract class PathElem extends Product with Serializable {
    def json: ujson.Value
  }
  object PathElem {
    final case class Key(key: String) extends PathElem {
      def json: ujson.Value = ujson.Str(key)
    }
    final case class Index(idx: Int) extends PathElem {
      def json: ujson.Value = ujson.Num(idx)
    }

    def fromJson(json: ujson.Value): Option[PathElem] =
      json match {
        case ujson.Str(key)                    => Some(Key(key))
        case ujson.Num(n) if n == math.rint(n) => Some(Index(n.toInt))
        case _                                 => None
      }
  }

  /** A binary value, at `path` in the JSON content it goes with */
  final case class Buffer(path: Seq[PathElem], data: Array[Byte]) {
    private[widgets] def under(elem: PathElem): Option[Buffer] =
      path match {
        case Seq(`elem`, rest @ _*) => Some(Buffer(rest, data))
        case _                      => None
      }
    override def equals(obj: Any): Boolean =
      obj match {
        case other: Buffer => path == other.path && Arrays.equals(data, other.data)
        case _             => false
      }
    override def hashCode(): Int =
      (path, Arrays.hashCode(data)).##
    override def toString: String =
      s"Buffer(${path.map(_.json.render()).mkString("/")}, ${data.length} bytes)"
  }

  val Null: Encoded = Encoded(ujson.Null)

  /** A binary value */
  def bytes(data: Array[Byte]): Encoded =
    Encoded(ujson.Null, Seq(Buffer(Nil, data)))

  /** An object, whose fields are `fields` */
  def obj(fields: (String, Encoded)*): Encoded =
    Encoded(
      ujson.Obj.from(fields.map { case (k, v) => k -> v.json }),
      fields.flatMap { case (k, v) => v.buffers.map(b => b.copy(path = PathElem.Key(k) +: b.path)) }
    )

  /** An array, whose elements are `values` */
  def arr(values: Seq[Encoded]): Encoded =
    Encoded(
      ujson.Arr.from(values.map(_.json)),
      values.zipWithIndex.flatMap {
        case (v, idx) => v.buffers.map(b => b.copy(path = PathElem.Index(idx) +: b.path))
      }
    )

  /** The JSON content and binary buffers of a message, from its JSON content, its `buffer_paths`
    * (in `paths`), and its binary buffers
    */
  def fromMessage(json: ujson.Value, paths: Seq[ujson.Value], buffers: Seq[Array[Byte]])
    : Encoded = {
    val buffers0 = paths.zip(buffers).flatMap {
      case (ujson.Arr(path), data) =>
        val path0 = path.toVector.map(PathElem.fromJson)
        if (path0.forall(_.nonEmpty)) Seq(Buffer(path0.flatten, data))
        else Nil
      case _ => Nil
    }
    val json0 =
      if (buffers0.isEmpty) json
      else {
        // binary values are removed from objects in messages, we put them back as null
        val json0 = ujson.copy(json)
        for (buffer <- buffers0)
          putNull(json0, buffer.path)
        json0
      }
    Encoded(json0, buffers0)
  }

  /** The content of a message: JSON content, `buffer_paths`, and binary buffers
    *
    * Like ipywidgets does, binary values are removed from objects, and are `null` in arrays.
    */
  def toMessage(value: Encoded): (ujson.Value, ujson.Arr, Seq[Array[Byte]]) = {
    val json =
      if (value.buffers.isEmpty) value.json
      else {
        val json0 = ujson.copy(value.json)
        for (buffer <- value.buffers)
          remove(json0, buffer.path)
        json0
      }
    val paths = ujson.Arr.from(value.buffers.map(b => ujson.Arr.from(b.path.map(_.json))))
    (json, paths, value.buffers.map(_.data))
  }

  private def putNull(json: ujson.Value, path: Seq[PathElem]): Unit =
    path match {
      case Seq(PathElem.Key(key)) =>
        for (obj <- json.objOpt)
          obj(key) = ujson.Null
      case Seq(PathElem.Key(key), rest @ _*) =>
        for (obj <- json.objOpt; value <- obj.get(key))
          putNull(value, rest)
      case Seq(PathElem.Index(idx), rest @ _*) =>
        for (arr <- json.arrOpt if idx >= 0 && idx < arr.length)
          putNull(arr(idx), rest)
      case _ =>
    }

  private def remove(json: ujson.Value, path: Seq[PathElem]): Unit =
    path match {
      case Seq(PathElem.Key(key)) =>
        for (obj <- json.objOpt)
          obj.remove(key)
      case Seq(PathElem.Key(key), rest @ _*) =>
        for (obj <- json.objOpt; value <- obj.get(key))
          remove(value, rest)
      case Seq(PathElem.Index(idx), rest @ _*) =>
        for (arr <- json.arrOpt if idx >= 0 && idx < arr.length)
          remove(arr(idx), rest)
      case _ =>
    }
}
