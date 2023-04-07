package almond.kernel.util

import java.util.Locale

sealed abstract class OS extends Product with Serializable

object OS {

  sealed abstract class Unix extends OS

  case object Linux   extends Unix
  case object Mac     extends Unix
  case object Windows extends OS
  case object BSD     extends Unix

  lazy val current = {

    // adapted from https://github.com/soc/directories-jvm/blob/d302b1e93963c81ed511e072a52e95251b5d078b/src/main/java/io/github/soc/directories/Util.java#L14-L33

    val os = sys.props("os.name").toLowerCase(Locale.ROOT)

    if (os.contains("linux"))
      Linux
    else if (os.contains("mac"))
      Mac
    else if (os.contains("windows"))
      Windows
    else if (os.contains("bsd"))
      BSD
    else
      throw new Exception(s"Unsupported operating system $os")
  }

}
