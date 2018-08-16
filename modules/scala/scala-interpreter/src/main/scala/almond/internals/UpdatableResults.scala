package almond.internals

import almond.interpreter.api.DisplayData
import almond.util.OptionalLogger
import ammonite.util.Ref

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class UpdatableResults(ec: ExecutionContext, updateData: DisplayData => Unit) {

  private val log = OptionalLogger(getClass)

  val refs = new mutable.HashMap[String, Ref[(DisplayData, Map[String, String])]]

  def add(data: DisplayData, variables: Map[String, String]): DisplayData = {
    val ref = Ref((data, variables))
    refs ++= variables.keysIterator.map { k =>
      k -> ref
    }
    UpdatableResults.substituteVariables(data, variables)
  }

  def update(k: String, v: String, last: Boolean): Unit =
    Future(
      refs.get(k) match {
        case None =>
          log.warn(s"Updatable variable $k not found")
          throw new NoSuchElementException(s"Result variable $k")
        case Some(ref) =>
          log.info(s"Updating variable $k with $v")
          val (data0, m0) = ref()
          val m = m0 + (k -> v)
          val data = UpdatableResults.substituteVariables(data0, m)
          ref() = (data, m)
          updateData(data)
          if (last)
            refs -= k
      }
    )(ec) // FIXME Log failures

}

object UpdatableResults {

  def substituteVariables(d: DisplayData, m: Map[String, String]): DisplayData =
    d.copy(
      data = d.data.map {
        case ("text/plain", t) =>
          "text/plain" -> m.foldLeft(t) {
            case (acc, (k, v)) =>
              // ideally, we should keep the pprint tree instead of plain text here, for things to get reflowed if
              // needed
              acc.replace(k, v)
          }
        case kv => kv
      }
    )

}
