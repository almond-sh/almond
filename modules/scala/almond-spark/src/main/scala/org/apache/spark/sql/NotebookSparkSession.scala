package org.apache.spark.sql

import almond.interpreter.api.{CommHandler, OutputHandler}
import ammonite.repl.api.ReplAPI
import ammonite.interp.api.InterpAPI
import org.apache.spark.sql.almondinternals.NotebookSparkSessionBuilder

object NotebookSparkSession {

  def builder()(implicit
    interpApi: InterpAPI,
    replApi: ReplAPI,
    publish: OutputHandler,
    commHandler: CommHandler
  ): NotebookSparkSessionBuilder =
    new NotebookSparkSessionBuilder

  def sync(session: SparkSession = null)(implicit replApi: ReplAPI): SparkSession =
    AmmoniteSparkSession.sync(session)

}
