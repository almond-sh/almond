package almond.widgets.internal

import almond.widgets.JsonValue

import scala.collection.immutable.ListMap

/** Moves binary data in and out of JSON values
  *
  * Widget messages send binary data as binary buffers alongside their JSON content. The JSON
  * content then has a `buffer_paths` field, listing where each buffer belongs in it. Binary values
  * in objects are removed from them, while those in arrays are replaced by `null`.
  */
private[widgets] object Buffers {

  /** Removes the binary values of `value`
    *
    * @return
    *   `value` without its binary values, along with the paths of these (as JSON, ready to be sent
    *   in `buffer_paths`), and the binary values themselves
    */
  def split(value: JsonValue): (JsonValue, JsonValue.Arr, Seq[Array[Byte]]) = {
    val paths   = Vector.newBuilder[JsonValue]
    val buffers = Vector.newBuilder[Array[Byte]]
    def helper(value: JsonValue, path: List[JsonValue]): Option[JsonValue] =
      value match {
        case JsonValue.Bytes(bytes) =>
          paths += JsonValue.Arr(path.reverse.toVector)
          buffers += bytes
          None
        case JsonValue.Arr(values) =>
          val values0 = values.zipWithIndex.map {
            case (value, idx) =>
              helper(value, JsonValue.Num(idx) :: path).getOrElse(JsonValue.Null)
          }
          Some(JsonValue.Arr(values0))
        case JsonValue.Obj(fields) =>
          val fields0 = fields.flatMap {
            case (key, value) =>
              helper(value, JsonValue.Str(key) :: path).map(key -> _).toSeq
          }
          Some(JsonValue.Obj(ListMap(fields0.toSeq: _*)))
        case other =>
          Some(other)
      }
    val value0 = helper(value, Nil).getOrElse(JsonValue.Null)
    (value0, JsonValue.Arr(paths.result()), buffers.result())
  }

  /** Puts binary buffers back in a JSON value, at the paths listed in `paths` */
  def put(value: JsonValue, paths: Seq[JsonValue], buffers: Seq[Array[Byte]]): JsonValue =
    paths.zip(buffers).foldLeft(value) {
      case (acc, (JsonValue.Arr(path), buffer)) =>
        insert(acc, path.toList, JsonValue.Bytes(buffer))
      case (acc, _) =>
        acc
    }

  private def insert(value: JsonValue, path: List[JsonValue], leaf: JsonValue): JsonValue =
    path match {
      case Nil => leaf
      case JsonValue.Str(key) :: rest =>
        value match {
          case obj: JsonValue.Obj =>
            obj + (key -> insert(obj.get(key).getOrElse(JsonValue.Null), rest, leaf))
          case _ =>
            value
        }
      case JsonValue.Num(idx0) :: rest =>
        val idx = idx0.toInt
        value match {
          case JsonValue.Arr(values) if idx >= 0 && idx < values.length =>
            JsonValue.Arr(values.updated(idx, insert(values(idx), rest, leaf)))
          case _ =>
            value
        }
      case _ =>
        value
    }
}
