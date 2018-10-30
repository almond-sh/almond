package almond.api.helpers

import java.util.UUID

import almond.interpreter.api.{DisplayData, OutputHandler}

final class Display(id: String, contentType: String) {
  def update(content: String)(implicit outputHandler: OutputHandler): Unit =
    outputHandler.updateDisplay(
      DisplayData(Map(contentType -> content))
        .withId(id)
    )

  override def toString =
    s"$contentType #$id"
}

object Display {

  private def newId(): String =
    UUID.randomUUID().toString

  def html(content: String)(implicit outputHandler: OutputHandler): Display = {
    val id = newId()
    outputHandler.display(
      DisplayData.html(content)
        .withId(id)
    )
    new Display(id, DisplayData.ContentType.html)
  }

  def text(content: String)(implicit outputHandler: OutputHandler): Display = {
    val id = newId()
    outputHandler.display(
      DisplayData.text(content)
        .withId(id)
    )
    new Display(id, DisplayData.ContentType.text)
  }

  def js(content: String)(implicit outputHandler: OutputHandler): Unit =
    outputHandler.display(
      DisplayData.js(content)
    )

  def jpg(content: Array[Byte])(implicit outputHandler: OutputHandler): Display = {
    val id = newId()
    outputHandler.display(
      DisplayData.jpg(content)
        .withId(id)
    )
    new Display(id, DisplayData.ContentType.jpg)
  }

  def png(content: Array[Byte])(implicit outputHandler: OutputHandler): Display = {
    val id = newId()
    outputHandler.display(
      DisplayData.png(content)
        .withId(id)
    )
    new Display(id, DisplayData.ContentType.png)
  }

  def svg(content: String)(implicit outputHandler: OutputHandler): Display = {
    val id = newId()
    outputHandler.display(
      DisplayData.svg(content)
        .withId(id)
    )
    new Display(id, DisplayData.ContentType.svg)
  }

}
