package almond.display

import java.util.{Locale, UUID}
import java.util.concurrent.atomic.AtomicInteger

import almond.interpreter.api.{DisplayData, OutputHandler}

trait UpdatableDisplay extends Display {
  def displayId: String
  override def displayData(): DisplayData =
    DisplayData(data(), metadata = metadata(), idOpt = Some(displayId))
  protected def emptyDisplayData(): DisplayData = {
    val data = displayData()
    data.copy(data = data.data.mapValues(_ => "").toMap)
  }

  def update()(implicit output: OutputHandler): Unit =
    output.updateDisplay(displayData())

  def clear()(implicit output: OutputHandler): Unit =
    output.updateDisplay(emptyDisplayData())
}

object UpdatableDisplay {

  private lazy val useRandomIds0: Boolean =
    Option(System.getenv("ALMOND_USE_RANDOM_IDS"))
      .orElse(sys.props.get("almond.ids.random"))
      .forall(s => s == "1" || s.toLowerCase(Locale.ROOT) == "true")

  def useRandomIds(): Boolean =
    useRandomIds0

  private val idCounter  = new AtomicInteger
  private val divCounter = new AtomicInteger

  def generateId(): String =
    if (useRandomIds())
      UUID.randomUUID().toString
    else
      idCounter.incrementAndGet().toString

  def generateDiv(prefix: String = "data-"): String =
    prefix + {
      if (useRandomIds())
        UUID.randomUUID().toString
      else
        divCounter.incrementAndGet().toString
    }

}
