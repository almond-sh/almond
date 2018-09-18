package scala.tools.nsc.interpreter

import java.io.File

import com.google.auth.Credentials
import com.spotify.scio.bigquery.BigQueryClient

/**
  * Temporary workaround to make scio's BigQueryClient think we're in a REPL
  * (requires scala.tools.nsc.interpreter somewhere in the stack)
  */
object Helper {

  def bigQueryClient(project: String): BigQueryClient = {
    val secret = sys.props(BigQueryClient.SECRET_KEY)
    if (secret == null)
      BigQueryClient(project)
    else
      BigQueryClient(project, new File(secret))
  }

  def bigQueryClient(project: String, credentials: Credentials): BigQueryClient =
    BigQueryClient(project, credentials)

  def bigQueryClient(project: String, secretFile: File): BigQueryClient =
    BigQueryClient(project, secretFile)

}
