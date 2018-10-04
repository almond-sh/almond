package almond.api

import java.util.Properties

object Properties {

  private lazy val props = {

    val p = new Properties

    try {
      p.load(
        getClass
          .getClassLoader
          .getResourceAsStream("almond/almond.properties")
      )
    } catch  {
      case _: NullPointerException =>
    }

    p
  }

  lazy val version = Option(props.getProperty("version")).getOrElse("[unknown]")
  lazy val commitHash = Option(props.getProperty("commit-hash")).getOrElse("[unknown]")

}
