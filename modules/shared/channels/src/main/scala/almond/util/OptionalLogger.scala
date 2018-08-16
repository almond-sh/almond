package almond.util

import java.lang.{Boolean => JBoolean}

import com.typesafe.scalalogging.Logger
import org.slf4j.helpers.NOPLogger

object OptionalLogger {

  private lazy val nopLogger =
    Logger(new NOPLogger {})

  @volatile private var enabled: JBoolean = null

  /**
    * Enable optional logging.
    *
    * Call this prior to any call to [[apply()]] to enable or disable optional logging. If not called,
    * optional logging is disabled.
    */
  def enable(enable: Boolean = true): Unit = {

    def nope =
      throw new Exception("Logging already enabled or initialized")

    if (enabled == null)
      synchronized {
        if (enabled == null)
          enabled = enable
        else
          nope
      }
    else
      nope
  }

  /**
    * Like [[Logger]], with logging enabled if and only if [[enable()]] was called.
    *
    * Logback is quite painful to configure programmatically, so this is used as a workaround.
    */
  def apply(clazz: Class[_]): Logger = {

    if (enabled == null)
      synchronized {
        if (enabled == null) {
          enabled = false
        }
      }

    if (enabled)
      Logger(clazz)
    else
      nopLogger
  }

}
