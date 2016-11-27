package jupyter

import _root_.scala.tools.nsc.interpreter.Helper

import java.io.File

import com.google.api.client.auth.oauth2.Credential
import com.spotify.scio.bigquery.BigQueryClient

package object scio {

  val JupyterScioContext: com.spotify.scio.jupyter.JupyterScioContext.type =
    com.spotify.scio.jupyter.JupyterScioContext

  def bigQueryClient(project: String): BigQueryClient =
    Helper.bigQueryClient(project)

  def bigQueryClient(project: String, credential: Credential): BigQueryClient =
    Helper.bigQueryClient(project, credential)

  def bigQueryClient(project: String, secretFile: File): BigQueryClient =
    Helper.bigQueryClient(project, secretFile)

}
