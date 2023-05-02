package almond.interpreter

import almond.protocol.RawJson

/** Completion result
  *
  * @param from:
  *   position from which one of the completion can be substituted
  * @param until:
  *   position up to which one the completion can be substituted (not including the character at
  *   position `until`)
  * @param completions:
  *   possible replacements between indices `from` until `to`
  */
final case class Completion(
  from: Int,
  until: Int,
  completions: Seq[String],
  metadata: RawJson
)

object Completion {
  def apply(from: Int, until: Int, completions: Seq[String]): Completion =
    Completion(from, until, completions, RawJson.emptyObj)
  def empty(pos: Int): Completion =
    Completion(pos, pos, Nil, RawJson.emptyObj)
}
