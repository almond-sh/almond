package almond.display

import almond.interpreter.api.{DisplayData, OutputHandler}

trait UpdatableDisplay extends Display {
  def displayId: String
  protected override def displayData(): DisplayData =
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

  def generateId(): String =
    almond.api.helpers.Display.newId()

}
