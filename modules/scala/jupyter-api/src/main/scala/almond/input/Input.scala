package almond.input

import almond.api.JupyterApi

final class Input private (
  val prompt: String,
  val password: Boolean
) {

  private def copy(
    prompt: String = prompt,
    password: Boolean = password
  ): Input =
    new Input(prompt, password)

  def withPrompt(prompt: String): Input =
    copy(prompt = prompt)
  def withPassword(password: Boolean): Input =
    copy(password = password)
  def withPassword(): Input =
    copy(password = true)

  // TODO Also allow to return result via a future?
  def request()(implicit api: JupyterApi): String =
    api.stdin(prompt, password)

}

object Input {
  def apply(): Input =
    new Input("", password = false)
  def apply(prompt: String): Input =
    new Input(prompt, password = false)
  def password(): Input =
    new Input("", password = true)
  def password(prompt: String): Input =
    new Input(prompt, password = true)
}
