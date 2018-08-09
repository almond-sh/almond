package org.apache.spark.sql.almondinternals

import almond.interpreter.api.{CommHandler, OutputHandler}
import almond.api.helpers.Display.html
import ammonite.interp.InterpAPI
import ammonite.repl.ReplAPI
import org.apache.spark.sql.ammonitesparkinternals.AmmoniteSparkSessionBuilder

class NotebookSparkSessionBuilder
 (implicit
   interpApi: InterpAPI,
   replApi: ReplAPI,
   publish: OutputHandler,
   commHandler: CommHandler
 ) extends AmmoniteSparkSessionBuilder {

  private var progress0 = true
  private var keep0 = true

  def progress(enable: Boolean = true, keep: Boolean = true): this.type = {
    progress0 = enable
    keep0 = keep
    this
  }

  override def getOrCreate() = {

    val session = super.getOrCreate()

    for (url <- session.sparkContext.uiWebUrl)
      html(s"""<a href="$url">Spark UI</a>""")

    session.sparkContext.addSparkListener(
      new ProgressSparkListener(session, keep0, progress0)
    )

    session
  }

}
